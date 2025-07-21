// daemon/cpp/uds_server.cpp
#include "uds_server.h"
#include "main.h" // [修复] 添加此头文件以访问 g_probe_fd
#include <android/log.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <algorithm>
#include <vector>
#include <cstddef>
#include <sys/select.h>
#include <thread>

#define LOG_TAG "cerberusd_uds_v11.1" 
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// [修复] 移除此处不再需要的前向声明
// extern std::atomic<int> g_probe_fd;

UdsServer::UdsServer(const std::string& socket_name)
    : socket_name_(socket_name), server_fd_(-1), is_running_(false) {}

UdsServer::~UdsServer() {
    stop();
}

void UdsServer::set_message_handler(std::function<void(int, const std::string&)> handler) {
    on_message_received_ = std::move(handler);
}

bool UdsServer::has_clients() const {
    std::lock_guard<std::mutex> lock(client_mutex_);
    return !client_fds_.empty();
}

void UdsServer::add_client(int client_fd) {
    std::lock_guard<std::mutex> lock(client_mutex_);
    client_fds_.push_back(client_fd);
    client_buffers_[client_fd] = "";
    LOGI("Client connected, fd: %d. Total clients: %zu", client_fd, client_fds_.size());

    // [修复] 此处的 g_probe_fd 现在是合法的
    std::thread([client_fd]() {
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
        // Probe连接很快，250ms后如果g_probe_fd还是-1或者不等于当前fd，说明是UI
        if (client_fd != g_probe_fd.load()) {
            LOGI("New UI client (fd: %d) connected, scheduling initial dashboard broadcast.", client_fd);
            schedule_task(RefreshDashboardTask{});
        }
    }).detach();
}

void UdsServer::set_disconnect_handler(std::function<void(int)> handler) {
    on_disconnect_ = std::move(handler);
}

void UdsServer::remove_client(int client_fd) {
    std::lock_guard<std::mutex> lock(client_mutex_);
    auto it = std::remove(client_fds_.begin(), client_fds_.end(), client_fd);
    if (it != client_fds_.end()) {
        client_fds_.erase(it, client_fds_.end());
        client_buffers_.erase(client_fd);
        close(client_fd);
        LOGI("Client disconnected, fd: %d. Total clients: %zu", client_fd, client_fds_.size());
        if (on_disconnect_) {
            on_disconnect_(client_fd);
        }
    }
}

void UdsServer::broadcast_message_except(const std::string& message, int excluded_fd) {
    std::lock_guard<std::mutex> lock(client_mutex_);
    if (client_fds_.empty()) return;

    auto clients_copy = client_fds_;
    for (int fd : clients_copy) {
        if (fd != excluded_fd) {
            send_message(fd, message);
        }
    }
}

bool UdsServer::send_message(int client_fd, const std::string& message) {
    std::string line = message + "\n";
    ssize_t bytes_sent = send(client_fd, line.c_str(), line.length(), MSG_NOSIGNAL);
    if (bytes_sent < 0) {
        if (errno == EPIPE || errno == ECONNRESET) {
            LOGW("Send to fd %d failed (connection closed), removing client.", client_fd);
            // This call is now safe because remove_client handles the lock
            remove_client(client_fd);
        } else {
            LOGE("Send to fd %d failed: %s", client_fd, strerror(errno));
        }
        return false;
    }
    return true;
}

void UdsServer::broadcast_message(const std::string& message) {
    std::lock_guard<std::mutex> lock(client_mutex_);
    if (client_fds_.empty()) return;

    auto clients_copy = client_fds_;
    for (int fd : clients_copy) {
        send_message(fd, message);
    }
}

void UdsServer::handle_client_data(int client_fd) {
    char buffer[4096];
    ssize_t bytes_read = recv(client_fd, buffer, sizeof(buffer), 0);

    if (bytes_read <= 0) {
        remove_client(client_fd);
        return;
    }

    std::string received_data(buffer, bytes_read);
    std::vector<std::string> messages_to_process;

    {
        std::lock_guard<std::mutex> lock(client_mutex_);
        auto buffer_it = client_buffers_.find(client_fd);
        if (buffer_it == client_buffers_.end()) return;
        
        buffer_it->second += received_data;
        std::string& client_buffer = buffer_it->second;

        size_t pos;
        while ((pos = client_buffer.find('\n')) != std::string::npos) {
            std::string message = client_buffer.substr(0, pos);
            if (!message.empty()) {
                messages_to_process.push_back(message);
            }
            client_buffer.erase(0, pos + 1);
        }
    }

    if (on_message_received_ && !messages_to_process.empty()) {
        for (const auto& msg : messages_to_process) {
            on_message_received_(client_fd, msg);
        }
    }
}

void UdsServer::stop() {
    if (!is_running_.exchange(false)) return;
    LOGI("Stopping UDS server...");

    if (server_fd_ != -1) {
        shutdown(server_fd_, SHUT_RDWR);
        close(server_fd_);
        server_fd_ = -1;
    }

    std::lock_guard<std::mutex> lock(client_mutex_);
    for (int fd : client_fds_) {
        close(fd);
    }
    client_fds_.clear();
    client_buffers_.clear();
    LOGI("UDS Server stopped and all clients disconnected.");
}

void UdsServer::run() {
    server_fd_ = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server_fd_ == -1) {
        LOGE("Failed to create socket: %s", strerror(errno));
        return;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, socket_name_.c_str(), sizeof(addr.sun_path) - 2);
    socklen_t addr_len = offsetof(struct sockaddr_un, sun_path) + socket_name_.length() + 1;

    if (bind(server_fd_, (struct sockaddr*)&addr, addr_len) == -1) {
        LOGE("Failed to bind abstract socket '@%s': %s", socket_name_.c_str(), strerror(errno));
        close(server_fd_);
        return;
    }

    if (listen(server_fd_, 5) == -1) {
        LOGE("Failed to listen on socket: %s", strerror(errno));
        close(server_fd_);
        return;
    }

    LOGI("Server listening on abstract UDS: @%s", socket_name_.c_str());
    is_running_ = true;

    while (is_running_) {
        fd_set read_fds;
        FD_ZERO(&read_fds);
        FD_SET(server_fd_, &read_fds);
        int max_fd = server_fd_;

        {
            std::lock_guard<std::mutex> lock(client_mutex_);
            for (int fd : client_fds_) {
                FD_SET(fd, &read_fds);
                max_fd = std::max(max_fd, fd);
            }
        }
        
        struct timeval tv { .tv_sec = 1, .tv_usec = 0 };

        int activity = select(max_fd + 1, &read_fds, nullptr, nullptr, &tv);

        if (activity < 0) {
            if (errno == EINTR) continue;
            LOGE("select() error: %s", strerror(errno));
            break;
        }

        if (!is_running_) break;
        if (activity == 0) continue;

        if (FD_ISSET(server_fd_, &read_fds)) {
            int new_socket = accept(server_fd_, nullptr, nullptr);
            if (new_socket >= 0) {
                add_client(new_socket);
            }
        }
        
        std::vector<int> clients_to_check;
        {
            std::lock_guard<std::mutex> lock(client_mutex_);
            clients_to_check = client_fds_;
        }
        for (int fd : clients_to_check) {
            if (FD_ISSET(fd, &read_fds)) {
                handle_client_data(fd);
            }
        }
    }

    LOGI("Server event loop terminated.");
}
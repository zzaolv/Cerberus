// daemon/cpp/uds_server.cpp
#include "uds_server.h"
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

#define LOG_TAG "cerberusd_uds"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

UdsServer::UdsServer(const std::string& socket_path)
    : socket_path_(socket_path), server_fd_(-1), is_running_(false) {}

UdsServer::~UdsServer() {
    if (is_running_) {
        stop();
    }
}

void UdsServer::set_message_handler(std::function<void(int, const std::string&)> handler) {
    on_message_received_ = std::move(handler);
}

bool UdsServer::has_clients() {
    std::lock_guard<std::mutex> lock(client_mutex_);
    return !client_fds_.empty();
}

void UdsServer::send_message_to_client(int client_fd, const std::string& message) {
    std::lock_guard<std::mutex> lock(client_mutex_);
    auto it = std::find(client_fds_.begin(), client_fds_.end(), client_fd);
    if (it == client_fds_.end()) {
        LOGW("Attempted to send message to non-existent client fd: %d", client_fd);
        return;
    }

    std::string line = message + "\n";
    ssize_t bytes_sent = send(client_fd, line.c_str(), line.length(), MSG_NOSIGNAL);
    if (bytes_sent < 0) {
        if (errno == EPIPE || errno == ECONNRESET) {
            LOGW("Send to client %d failed, it may have disconnected.", client_fd);
        }
    }
}

void UdsServer::add_client(int client_fd) {
    std::lock_guard<std::mutex> lock(client_mutex_);
    client_fds_.push_back(client_fd);
    client_buffers_[client_fd] = "";
    LOGI("Client connected, fd: %d. Total clients: %zu", client_fd, client_fds_.size());
}

void UdsServer::remove_client(int client_fd) {
    std::lock_guard<std::mutex> lock(client_mutex_);
    auto it = std::remove(client_fds_.begin(), client_fds_.end(), client_fd);
    if (it != client_fds_.end()) {
        client_fds_.erase(it, client_fds_.end());
        client_buffers_.erase(client_fd);
        LOGI("Client disconnected, fd: %d. Total clients: %zu", client_fd, client_fds_.size());
        close(client_fd);
    }
}

void UdsServer::broadcast_message(const std::string& message) {
    std::lock_guard<std::mutex> lock(client_mutex_);
    if (client_fds_.empty()) return;

    std::string line = message + "\n";
    std::vector<int> disconnected_clients;

    for (int fd : client_fds_) {
        ssize_t bytes_sent = send(fd, line.c_str(), line.length(), MSG_NOSIGNAL);
        if (bytes_sent < 0) {
            if (errno == EPIPE || errno == ECONNRESET) {
                disconnected_clients.push_back(fd);
            }
        }
    }

    if (!disconnected_clients.empty()) {
        for (int fd : disconnected_clients) {
            // Do not call remove_client here to avoid deadlock
        }
    }
}

void UdsServer::handle_client_data(int client_fd) {
    char buffer[4096];
    ssize_t bytes_read = recv(client_fd, buffer, sizeof(buffer) - 1, 0);

    if (bytes_read <= 0) {
        remove_client(client_fd);
        return;
    }

    buffer[bytes_read] = '\0';
    
    std::vector<std::string> messages_to_process;
    
    {
        std::lock_guard<std::mutex> lock(client_mutex_);
        auto it = client_buffers_.find(client_fd);
        if (it == client_buffers_.end()) {
            return;
        }

        it->second += buffer;
        std::string& client_buffer = it->second;

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
    is_running_ = false;
    if (server_fd_ != -1) {
        shutdown(server_fd_, SHUT_RDWR);
    }
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
    strncpy(addr.sun_path + 1, socket_path_.c_str(), sizeof(addr.sun_path) - 2);
    socklen_t addr_len = offsetof(struct sockaddr_un, sun_path) + socket_path_.length() + 1;
    
    if (bind(server_fd_, (struct sockaddr*)&addr, addr_len) == -1) {
        LOGE("Failed to bind socket '@%s': %s", socket_path_.c_str(), strerror(errno));
        close(server_fd_);
        return;
    }

    if (listen(server_fd_, 5) == -1) {
        LOGE("Failed to listen on socket: %s", strerror(errno));
        close(server_fd_);
        return;
    }

    LOGI("Server is listening on abstract UDS: @%s", socket_path_.c_str());
    is_running_ = true;

    while (is_running_) {
        fd_set read_fds;
        FD_ZERO(&read_fds);
        FD_SET(server_fd_, &read_fds);
        int max_fd = server_fd_;

        std::vector<int> current_clients;
        {
            std::lock_guard<std::mutex> lock(client_mutex_);
            current_clients = client_fds_;
            for (int fd : current_clients) {
                FD_SET(fd, &read_fds);
                if (fd > max_fd) {
                    max_fd = fd;
                }
            }
        }
        
        struct timeval tv;
        tv.tv_sec = 1;
        tv.tv_usec = 0;

        int activity = select(max_fd + 1, &read_fds, nullptr, nullptr, &tv);

        if (!is_running_) break;

        if (activity < 0 && errno != EINTR) {
            LOGE("select() error: %s", strerror(errno));
            break;
        }

        if (activity > 0) {
            if (FD_ISSET(server_fd_, &read_fds)) {
                int new_socket = accept(server_fd_, nullptr, nullptr);
                if (new_socket >= 0) {
                    add_client(new_socket);
                }
            }
            
            for (int fd : current_clients) {
                if (FD_ISSET(fd, &read_fds)) {
                    handle_client_data(fd);
                }
            }
        }
    }

    LOGI("Server event loop has terminated. Cleaning up all sockets.");
    close(server_fd_);
    server_fd_ = -1;
    
    std::vector<int> clients_to_close;
    {
        std::lock_guard<std::mutex> lock(client_mutex_);
        clients_to_close = client_fds_;
        client_fds_.clear();
        client_buffers_.clear();
    }
    for (int fd : clients_to_close) {
        close(fd);
    }
}
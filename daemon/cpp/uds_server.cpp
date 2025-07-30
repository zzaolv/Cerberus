// daemon/cpp/uds_server.cpp
#include "uds_server.h"
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h> // [核心修改] 引入网络头文件
#include <arpa/inet.h>  // [核心修改] 引入网络头文件
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <algorithm>
#include <vector>
#include <cstddef>
#include <sys/select.h>
#include <thread>

#define LOG_TAG "cerberusd_tcp_v1"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// [核心修改] 构造函数接受端口
UdsServer::UdsServer(int port)
    : port_(port), server_fd_(-1), is_running_(false) {}

UdsServer::~UdsServer() {
    stop();
}

void UdsServer::set_message_handler(std::function<void(int, const std::string&)> handler) {
    on_message_received_ = std::move(handler);
}

void UdsServer::set_disconnect_handler(std::function<void(int)> handler) {
    on_disconnect_ = std::move(handler);
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
    LOGI("Stopping TCP server...");
    
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
    LOGI("TCP Server stopped and all clients disconnected.");
}

void UdsServer::run() {
    // [核心修改] 创建 AF_INET (TCP) socket
    server_fd_ = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server_fd_ == -1) {
        LOGE("Failed to create TCP socket: %s", strerror(errno));
        return;
    }

    // [核心修改] 设置 SO_REUSEADDR 允许服务器快速重启
    int opt = 1;
    if (setsockopt(server_fd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)) < 0) {
        LOGE("setsockopt(SO_REUSEADDR) failed: %s", strerror(errno));
        close(server_fd_);
        return;
    }

    // [核心修改] 设置 TCP 地址和端口
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = inet_addr("127.0.0.1"); // 只监听本地回环地址
    addr.sin_port = htons(port_); // 将端口号从主机字节序转换到网络字节序

    if (bind(server_fd_, (struct sockaddr*)&addr, sizeof(addr)) == -1) {
        LOGE("Failed to bind TCP socket to 127.0.0.1:%d : %s", port_, strerror(errno));
        close(server_fd_);
        return;
    }

    if (listen(server_fd_, 5) == -1) {
        LOGE("Failed to listen on TCP socket: %s", strerror(errno));
        close(server_fd_);
        return;
    }

    LOGI("Server listening on TCP 127.0.0.1:%d", port_);
    is_running_ = true;

    // 主循环 select/accept/handle_client_data 逻辑完全保持不变
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
            struct sockaddr_in client_addr;
            socklen_t client_len = sizeof(client_addr);
            int new_socket = accept(server_fd_, (struct sockaddr*)&client_addr, &client_len);
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
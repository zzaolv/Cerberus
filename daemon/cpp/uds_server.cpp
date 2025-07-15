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

#define LOG_TAG "cerberusd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

UdsServer::UdsServer(const std::string& socket_path)
    : socket_path_(socket_path), server_fd_(-1), is_running_(false) {}

UdsServer::~UdsServer() {
    // 确保在析构时停止服务器
    if (is_running_) {
        stop();
    }
}

void UdsServer::add_client(int client_fd) {
    std::lock_guard<std::mutex> lock(client_mutex_);
    client_fds_.push_back(client_fd);
    LOGI("Client connected, fd: %d. Total clients: %zu", client_fd, client_fds_.size());
}

void UdsServer::remove_client(int client_fd) {
    std::lock_guard<std::mutex> lock(client_mutex_);
    auto it = std::remove(client_fds_.begin(), client_fds_.end(), client_fd);
    if (it != client_fds_.end()) {
        client_fds_.erase(it, client_fds_.end());
        LOGI("Client disconnected, fd: %d. Total clients: %zu", client_fd, client_fds_.size());
        close(client_fd);
    }
}

void UdsServer::broadcast_message(const std::string& message) {
    std::lock_guard<std::mutex> lock(client_mutex_);
    if (client_fds_.empty()) return;

    // JSON Lines 协议要求每条消息以换行符结尾
    std::string line = message + "\n";
    std::vector<int> disconnected_clients;

    for (int fd : client_fds_) {
        ssize_t bytes_sent = send(fd, line.c_str(), line.length(), MSG_NOSIGNAL);
        if (bytes_sent < 0) {
            if (errno == EPIPE || errno == ECONNRESET) {
                LOGW("Client fd %d write failed (Connection closed), marking for removal.", fd);
                disconnected_clients.push_back(fd);
            } else {
                LOGE("Failed to send to client fd %d: %s", fd, strerror(errno));
            }
        }
    }

    // 从主列表安全地移除已断开的客户端
    if (!disconnected_clients.empty()) {
        for (int fd : disconnected_clients) {
             auto it = std::remove(client_fds_.begin(), client_fds_.end(), fd);
             if (it != client_fds_.end()) {
                client_fds_.erase(it, client_fds_.end());
                close(fd);
             }
        }
        LOGI("Cleaned up %zu disconnected clients. Current clients: %zu", disconnected_clients.size(), client_fds_.size());
    }
}

void UdsServer::stop() {
    LOGI("Stopping UDS server...");
    is_running_ = false;

    if (server_fd_ != -1) {
        // 关闭服务器套接字，这将导致阻塞的 accept() 调用返回-1
        shutdown(server_fd_, SHUT_RDWR);
        close(server_fd_);
        server_fd_ = -1;
    }

    // 关闭所有客户端连接
    std::lock_guard<std::mutex> lock(client_mutex_);
    for (int fd : client_fds_) {
        close(fd);
    }
    client_fds_.clear();
    LOGI("All client connections closed.");
}

void UdsServer::run() {
    LOGI("Attempting to create UDS socket...");
    server_fd_ = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0); // 使用 SOCK_CLOEXEC
    if (server_fd_ == -1) {
        LOGE("Failed to create socket: %s", strerror(errno));
        return;
    }
    LOGI("Socket created successfully (fd: %d).", server_fd_);

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    // 使用抽象命名空间，第一个字节必须是 '\0'
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, socket_path_.c_str(), sizeof(addr.sun_path) - 2);

    LOGI("Attempting to bind to abstract socket '@%s'...", socket_path_.c_str());
    if (bind(server_fd_, (struct sockaddr*)&addr, sizeof(addr)) == -1) {
        LOGE("Failed to bind socket '@%s': %s", socket_path_.c_str(), strerror(errno));
        close(server_fd_);
        return;
    }
    LOGI("Socket bound successfully.");

    if (listen(server_fd_, 5) == -1) {
        LOGE("Failed to listen on socket: %s", strerror(errno));
        close(server_fd_);
        return;
    }

    LOGI("Server is listening on abstract UDS: @%s", socket_path_.c_str());
    is_running_ = true;

    while (is_running_) {
        LOGI("Waiting for a new client connection...");
        int client_fd = accept(server_fd_, nullptr, nullptr);
        if (client_fd == -1) {
            // 如果 is_running_ 为 false，这是由 stop() 引起的正常退出
            if (is_running_) {
                LOGE("Failed to accept connection: %s", strerror(errno));
            }
            continue; // 继续循环，如果 is_running_ 为 false，循环将终止
        }
        add_client(client_fd);
        // 当前设计是单向广播，所以我们不为每个客户端创建读取线程。
        // 如果未来需要双向通信，可以在这里创建一个读线程。
    }

    LOGI("Server accept loop has terminated.");
}
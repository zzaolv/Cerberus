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
#include <cstddef> // For offsetof

#define LOG_TAG "cerberusd"
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
        shutdown(server_fd_, SHUT_RDWR);
        close(server_fd_);
        server_fd_ = -1;
    }

    std::lock_guard<std::mutex> lock(client_mutex_);
    for (int fd : client_fds_) {
        close(fd);
    }
    client_fds_.clear();
    LOGI("All client connections closed.");
}

void UdsServer::run() {
    LOGI("Attempting to create UDS socket...");
    server_fd_ = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server_fd_ == -1) {
        LOGE("Failed to create socket: %s", strerror(errno));
        return;
    }
    LOGI("Socket created successfully (fd: %d).", server_fd_);

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, socket_path_.c_str(), sizeof(addr.sun_path) - 2);

    // 【核心修复】计算正确的地址长度
    socklen_t addr_len = offsetof(struct sockaddr_un, sun_path) + socket_path_.length() + 1;

    LOGI("Attempting to bind to abstract socket '@%s' with length %d...", socket_path_.c_str(), addr_len);
    if (bind(server_fd_, (struct sockaddr*)&addr, addr_len) == -1) {
        LOGE("Failed to bind socket '@%s': %s", socket_path_.c_str(), strerror(errno));
        close(server_fd_);
        return; // 进程将在此处退出
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
            if (is_running_) {
                LOGE("Failed to accept connection: %s", strerror(errno));
            }
            continue;
        }
        add_client(client_fd);
    }

    LOGI("Server accept loop has terminated.");
}
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
    stop();
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
            // EPIPE 表示客户端已关闭连接
            if (errno == EPIPE || errno == ECONNRESET) {
                LOGW("Client fd %d write failed (EPIPE/ECONNRESET), marking for removal.", fd);
                disconnected_clients.push_back(fd);
            } else {
                LOGE("Failed to send to client fd %d: %s", fd, strerror(errno));
            }
        }
    }

    // 从主列表移除已断开的客户端
    for (int fd : disconnected_clients) {
        // 这里不加锁，因为 remove_client 内部会加锁
        // 为了避免死锁，创建一个临时副本来迭代
        remove_client(fd);
    }
}

void UdsServer::stop() {
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
}

void UdsServer::run() {
    server_fd_ = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server_fd_ == -1) {
        LOGE("Failed to create socket: %s", strerror(errno));
        return;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    // 使用抽象命名空间，第一个字节必须是 \0
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, socket_path_.c_str(), sizeof(addr.sun_path) - 2);

    if (bind(server_fd_, (struct sockaddr*)&addr, sizeof(addr)) == -1) {
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
        int client_fd = accept(server_fd_, nullptr, nullptr);
        if (client_fd == -1) {
            if (is_running_) { // 如果不是主动停止，就报错
                LOGE("Failed to accept connection: %s", strerror(errno));
            }
            continue;
        }
        add_client(client_fd);
        // 我们不为每个客户端创建读线程，因为当前阶段只关心广播
    }
}
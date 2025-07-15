#include "uds_server.h"
#include <android/log.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
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

void UdsServer::broadcast_message(const std::string& message) {
    // 在这个测试版本中，我们不实现广播
}

void UdsServer::stop() {
    is_running_ = false;
    if (server_fd_ != -1) {
        // 关闭服务器socket会解除accept的阻塞
        shutdown(server_fd_, SHUT_RDWR);
        close(server_fd_);
        server_fd_ = -1;
        LOGI("Server socket shut down.");
    }
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
    addr.sun_path[0] = '\0'; // Abstract namespace
    strncpy(addr.sun_path + 1, socket_path_.c_str(), sizeof(addr.sun_path) - 2);

    // SO_REUSEADDR 对 UDS 意义不大，但加上无害
    int reuse = 1;
    setsockopt(server_fd_, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

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
        LOGI("Waiting for a new connection... (blocking on accept)");
        int client_fd = accept(server_fd_, nullptr, nullptr);
        
        if (client_fd == -1) {
            // 如果不是因为我们主动停止服务器而导致的错误
            if (is_running_) {
                LOGE("Failed to accept connection: %s", strerror(errno));
            }
            continue; // 继续下一次循环
        }

        // 如果成功接受连接
        LOGI("===========================================");
        LOGI(">>> Client connected! File descriptor: %d", client_fd);
        LOGI("===========================================");
        
        // 在这个测试版本中，我们接受连接后立即关闭它，以准备接受下一个。
        // 这足以测试连接是否成功。
        close(client_fd);
        LOGI("<<< Client %d disconnected (connection closed by server).", client_fd);
    }

    LOGI("Exiting server run loop.");
}
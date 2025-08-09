// daemon/cpp/uds_server.cpp
#include "uds_server.h"
#include <android/log.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <algorithm>
#include <vector>
#include <cstddef>
#include <sys/select.h>
#include <thread>

#define LOG_TAG "cerberusd_dev_socket_v1"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

UdsServer::UdsServer(const std::string& uds_socket_name, int tcp_port)
    : uds_socket_name_(uds_socket_name),
      tcp_port_(tcp_port),
      server_fd_uds_(-1),
      server_fd_tcp_(-1),
      is_running_(false) {}

// ... (所有非 run/stop 的函数保持不变) ...
UdsServer::~UdsServer() {
    stop();
}

void UdsServer::set_message_handler(std::function<void(int, const std::string&)> handler) {
    on_message_received_ = std::move(handler);
}

void UdsServer::set_disconnect_handler(std::function<void(int)> handler) {
    on_disconnect_ = std::move(handler);
}

void UdsServer::identify_client_as_ui(int client_fd) {
    std::lock_guard<std::mutex> lock(client_mutex_);
    LOGI("Client fd %d identified as UI.", client_fd);
    ui_client_fds_.insert(client_fd);
}

void UdsServer::broadcast_message_to_ui(const std::string& message) {
    std::lock_guard<std::mutex> lock(client_mutex_);
    if (ui_client_fds_.empty()) return;

    auto ui_clients_copy = ui_client_fds_;
    for (int fd : ui_clients_copy) {
        send_message(fd, message);
    }
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
        ui_client_fds_.erase(client_fd);
        close(client_fd);
        LOGI("Client disconnected, fd: %d. Total clients: %zu, UI clients: %zu", client_fd, client_fds_.size(), ui_client_fds_.size());
        if (on_disconnect_) {
            on_disconnect_(client_fd);
        }
    }
}

void UdsServer::schedule_client_removal(int client_fd) {
    std::lock_guard<std::mutex> lock(clients_to_remove_mutex_);
    if (std::find(clients_to_remove_.begin(), clients_to_remove_.end(), client_fd) == clients_to_remove_.end()) {
        clients_to_remove_.push_back(client_fd);
    }
}

void UdsServer::process_clients_to_remove() {
    std::vector<int> to_remove;
    {
        std::lock_guard<std::mutex> lock(clients_to_remove_mutex_);
        if (clients_to_remove_.empty()) {
            return;
        }
        to_remove.swap(clients_to_remove_);
    }

    for (int fd : to_remove) {
        remove_client(fd);
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
            LOGW("Send to fd %d failed (connection closed), scheduling for removal.", client_fd);
            schedule_client_removal(client_fd);
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
        schedule_client_removal(client_fd);
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


void UdsServer::run() {
    // 步骤1: 初始化 UDS Socket (文件系统路径)
    server_fd_uds_ = socket(AF_LOCAL, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server_fd_uds_ == -1) {
        LOGE("Failed to create UDS socket: %s", strerror(errno));
        return;
    }

    struct sockaddr_un uds_addr;
    memset(&uds_addr, 0, sizeof(uds_addr));
    uds_addr.sun_family = AF_LOCAL;
    // uds_socket_name_ is now a full path, e.g., /dev/socket/cerberusd
    strncpy(uds_addr.sun_path, uds_socket_name_.c_str(), sizeof(uds_addr.sun_path) - 1);

    unlink(uds_socket_name_.c_str());

    if (bind(server_fd_uds_, (struct sockaddr*)&uds_addr, sizeof(uds_addr)) == -1) {
        LOGE("Failed to bind UDS socket to path '%s': %s", uds_socket_name_.c_str(), strerror(errno));
        close(server_fd_uds_);
        return;
    }
    
    if (chmod(uds_socket_name_.c_str(), 0666) == -1) {
        LOGE("Failed to chmod UDS socket file '%s': %s", uds_socket_name_.c_str(), strerror(errno));
        close(server_fd_uds_);
        unlink(uds_socket_name_.c_str());
        return;
    }

    if (listen(server_fd_uds_, 5) == -1) {
        LOGE("Failed to listen on UDS socket: %s", strerror(errno));
        close(server_fd_uds_);
        unlink(uds_socket_name_.c_str());
        return;
    }
    LOGI("Server listening on UDS path: %s (permissions set to 0666)", uds_socket_name_.c_str());

    // 步骤2: 初始化 TCP Socket (保持不变)
    server_fd_tcp_ = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server_fd_tcp_ == -1) {
        LOGE("Failed to create TCP socket: %s", strerror(errno));
        close(server_fd_uds_);
        return;
    }
    int opt = 1;
    if (setsockopt(server_fd_tcp_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)) < 0) {
        LOGW("setsockopt(SO_REUSEADDR) failed for TCP: %s", strerror(errno));
    }
    struct sockaddr_in tcp_addr;
    memset(&tcp_addr, 0, sizeof(tcp_addr));
    tcp_addr.sin_family = AF_INET;
    tcp_addr.sin_addr.s_addr = inet_addr("127.0.0.1");
    tcp_addr.sin_port = htons(tcp_port_);
    if (bind(server_fd_tcp_, (struct sockaddr*)&tcp_addr, sizeof(tcp_addr)) == -1) {
        LOGE("Failed to bind TCP socket to 127.0.0.1:%d : %s", tcp_port_, strerror(errno));
        close(server_fd_uds_);
        close(server_fd_tcp_);
        return;
    }
    if (listen(server_fd_tcp_, 5) == -1) {
        LOGE("Failed to listen on TCP socket: %s", strerror(errno));
        close(server_fd_uds_);
        close(server_fd_tcp_);
        return;
    }
    LOGI("Server listening on TCP 127.0.0.1:%d", tcp_port_);

    is_running_ = true;

    // 步骤3: 主循环同时监控两个监听Socket (保持不变)
    while (is_running_) {
        process_clients_to_remove();

        fd_set read_fds;
        FD_ZERO(&read_fds);
        FD_SET(server_fd_uds_, &read_fds);
        FD_SET(server_fd_tcp_, &read_fds);
        int max_fd = std::max(server_fd_uds_, server_fd_tcp_);

        {
            std::lock_guard<std::mutex> lock(client_mutex_);
            for (int fd : client_fds_) {
                FD_SET(fd, &read_fds);
                max_fd = std::max(max_fd, fd);
            }
        }
        
        struct timeval tv { .tv_sec = 1, .tv_usec = 0 };
        int activity = select(max_fd + 1, &read_fds, nullptr, nullptr, &tv);

        if (!is_running_) break;
        if (activity < 0) {
            if (errno == EINTR) continue;
            LOGE("select() error: %s", strerror(errno));
            break;
        }
        if (activity == 0) continue;

        if (FD_ISSET(server_fd_uds_, &read_fds)) {
            struct sockaddr_un client_addr;
            socklen_t client_len = sizeof(client_addr);
            int new_socket = accept(server_fd_uds_, (struct sockaddr*)&client_addr, &client_len);
            if (new_socket >= 0) {
                LOGI("Accepted new UDS connection.");
                add_client(new_socket);
            }
        }
        
        if (FD_ISSET(server_fd_tcp_, &read_fds)) {
            struct sockaddr_in client_addr;
            socklen_t client_len = sizeof(client_addr);
            int new_socket = accept(server_fd_tcp_, (struct sockaddr*)&client_addr, &client_len);
            if (new_socket >= 0) {
                LOGI("Accepted new TCP connection.");
                int nodelay_opt = 1;
                setsockopt(new_socket, IPPROTO_TCP, TCP_NODELAY, &nodelay_opt, sizeof(nodelay_opt));
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


void UdsServer::stop() {
    if (!is_running_.exchange(false)) return;
    LOGI("Stopping Dual-Protocol server...");
    
    if (server_fd_uds_ != -1) {
        shutdown(server_fd_uds_, SHUT_RDWR);
        close(server_fd_uds_);
        server_fd_uds_ = -1;
    }
    unlink(uds_socket_name_.c_str());

    if (server_fd_tcp_ != -1) {
        shutdown(server_fd_tcp_, SHUT_RDWR);
        close(server_fd_tcp_);
        server_fd_tcp_ = -1;
    }
    
    process_clients_to_remove(); 
    
    std::lock_guard<std::mutex> lock(client_mutex_);
    for (int fd : client_fds_) {
        close(fd);
    }
    client_fds_.clear();
    ui_client_fds_.clear();
    client_buffers_.clear();
    LOGI("Server stopped and all clients disconnected.");
}
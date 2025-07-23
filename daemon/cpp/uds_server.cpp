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
#include <sys/epoll.h> // [新]
#include <sys/eventfd.h> // [新]
#include <fcntl.h>       // [新] fcntl for non-blocking

#define LOG_TAG "cerberusd_uds_epoll" // 新版本Tag
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)


UdsServer::UdsServer(const std::string& socket_name)
    : socket_name_(socket_name) {}

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
    return !clients_.empty();
}

void UdsServer::add_client(int client_fd) {
    std::lock_guard<std::mutex> lock(client_mutex_);
    clients_[client_fd] = ClientState{};
    LOGI("Client connected, fd: %d. Total clients: %zu", client_fd, clients_.size());
}

void UdsServer::remove_client(int client_fd) {
    if (client_fd < 0) return;
    
    // 从epoll中移除
    if (epoll_fd_ != -1) {
        epoll_ctl(epoll_fd_, EPOLL_CTL_DEL, client_fd, nullptr);
    }

    close(client_fd);

    bool removed = false;
    {
        std::lock_guard<std::mutex> lock(client_mutex_);
        if (clients_.erase(client_fd) > 0) {
            removed = true;
        }
    }
    
    if (removed) {
        LOGI("Client disconnected, fd: %d.", client_fd);
        if (on_disconnect_) {
            on_disconnect_(client_fd);
        }
    }
}

bool UdsServer::send_message(int client_fd, const std::string& message) {
    std::string line = message + "\n";
    ssize_t bytes_sent = send(client_fd, line.c_str(), line.length(), MSG_NOSIGNAL);
    if (bytes_sent < 0) {
        if (errno == EPIPE || errno == ECONNRESET || errno == ENOTCONN) {
            LOGW("Send to fd %d failed (connection closed), scheduling removal.", client_fd);
            // 这里不直接调用remove_client，因为可能在epoll循环中，让循环自己处理
        } else {
            LOGE("Send to fd %d failed: %s", client_fd, strerror(errno));
        }
        return false;
    }
    return true;
}

void UdsServer::broadcast_message_except(const std::string& message, int excluded_fd) {
    std::vector<int> client_fds;
    {
        std::lock_guard<std::mutex> lock(client_mutex_);
        if (clients_.empty()) return;
        for(const auto& pair : clients_) {
            client_fds.push_back(pair.first);
        }
    }

    for (int fd : client_fds) {
        if (fd != excluded_fd) {
            send_message(fd, message);
        }
    }
}

void UdsServer::broadcast_message(const std::string& message) {
     broadcast_message_except(message, -1);
}

void UdsServer::handle_client_data(int client_fd) {
    char buffer[4096];
    
    while (true) {
        ssize_t bytes_read = recv(client_fd, buffer, sizeof(buffer), 0);
        
        if (bytes_read > 0) {
             std::string received_data(buffer, bytes_read);
             std::vector<std::string> messages_to_process;
             {
                 std::lock_guard<std::mutex> lock(client_mutex_);
                 auto it = clients_.find(client_fd);
                 if (it == clients_.end()) return;

                 it->second.read_buffer += received_data;
                 std::string& client_buffer = it->second.read_buffer;

                 size_t pos;
                 while ((pos = client_buffer.find('\n')) != std::string::npos) {
                     messages_to_process.push_back(client_buffer.substr(0, pos));
                     client_buffer.erase(0, pos + 1);
                 }
             }
             if (on_message_received_ && !messages_to_process.empty()) {
                for (const auto& msg : messages_to_process) {
                    on_message_received_(client_fd, msg);
                }
             }

        } else if (bytes_read == 0) {
            // Connection closed by peer
            remove_client(client_fd);
            break;
        } else { // bytes_read < 0
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // No more data to read
                break;
            }
            // Real error
            LOGE("recv() error on fd %d: %s", client_fd, strerror(errno));
            remove_client(client_fd);
            break;
        }
    }
}

void UdsServer::stop() {
    if (!is_running_.exchange(false)) return;
    
    LOGI("Stopping UDS server...");
    if (event_fd_ != -1) {
        uint64_t val = 1;
        write(event_fd_, &val, sizeof(val));
    }

    if (server_thread_.joinable()) {
        server_thread_.join();
    }
    
    // 清理所有剩余资源
    std::lock_guard<std::mutex> lock(client_mutex_);
    for (const auto& pair : clients_) {
        close(pair.first);
    }
    clients_.clear();
    
    if (server_fd_ != -1) {
        close(server_fd_);
        server_fd_ = -1;
    }
    if (epoll_fd_ != -1) {
        close(epoll_fd_);
        epoll_fd_ = -1;
    }
    if(event_fd_ != -1) {
        close(event_fd_);
        event_fd_ = -1;
    }
    
    LOGI("UDS Server stopped and all resources cleaned up.");
}

void UdsServer::set_socket_non_blocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags == -1) {
        LOGE("fcntl(F_GETFL) failed for fd %d: %s", fd, strerror(errno));
        return;
    }
    if (fcntl(fd, F_SETFL, flags | O_NONBLOCK) == -1) {
        LOGE("fcntl(F_SETFL, O_NONBLOCK) failed for fd %d: %s", fd, strerror(errno));
    }
}


void UdsServer::run() {
    server_thread_ = std::thread(&UdsServer::server_loop, this);
}

void UdsServer::server_loop() {
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
    
    unlink(addr.sun_path); // In case of abstract namespace, this is harmless but good practice for file-based UDS

    if (bind(server_fd_, (struct sockaddr*)&addr, addr_len) == -1) {
        LOGE("Failed to bind abstract socket '@%s': %s", socket_name_.c_str(), strerror(errno));
        close(server_fd_);
        return;
    }

    if (listen(server_fd_, SOMAXCONN) == -1) {
        LOGE("Failed to listen on socket: %s", strerror(errno));
        close(server_fd_);
        return;
    }

    epoll_fd_ = epoll_create1(EPOLL_CLOEXEC);
    if (epoll_fd_ == -1) {
        LOGE("epoll_create1 failed: %s", strerror(errno));
        close(server_fd_);
        return;
    }
    
    event_fd_ = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if(event_fd_ == -1) {
        LOGE("eventfd failed: %s", strerror(errno));
        close(server_fd_);
        close(epoll_fd_);
        return;
    }

    epoll_event ev;
    ev.events = EPOLLIN;
    ev.data.fd = server_fd_;
    if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, server_fd_, &ev) == -1) {
        LOGE("epoll_ctl: listen_sock failed: %s", strerror(errno));
        close(server_fd_);
        close(epoll_fd_);
        close(event_fd_);
        return;
    }
    
    ev.events = EPOLLIN;
    ev.data.fd = event_fd_;
    if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, event_fd_, &ev) == -1) {
        LOGE("epoll_ctl: event_fd failed: %s", strerror(errno));
        // ... cleanup ...
        return;
    }

    LOGI("Server listening on abstract UDS: @%s using epoll", socket_name_.c_str());
    is_running_ = true;

    std::vector<epoll_event> events(64);

    while (is_running_) {
        int n = epoll_wait(epoll_fd_, events.data(), events.size(), -1);
        if (n < 0) {
            if (errno == EINTR) continue;
            LOGE("epoll_wait failed: %s", strerror(errno));
            break;
        }

        for (int i = 0; i < n; ++i) {
            if (events[i].data.fd == server_fd_) {
                // New connection
                int client_fd = accept4(server_fd_, nullptr, nullptr, SOCK_CLOEXEC);
                if (client_fd >= 0) {
                    set_socket_non_blocking(client_fd);
                    ev.events = EPOLLIN | EPOLLET; // Edge-Triggered
                    ev.data.fd = client_fd;
                    if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, client_fd, &ev) != -1) {
                        add_client(client_fd);
                    } else {
                        LOGE("epoll_ctl: add client failed: %s", strerror(errno));
                        close(client_fd);
                    }
                }
            } else if (events[i].data.fd == event_fd_) {
                 // Stop signal
                 is_running_ = false;
                 break;
            } else {
                // Data from client
                handle_client_data(events[i].data.fd);
            }
        }
    }
    LOGI("Server event loop terminated.");
}
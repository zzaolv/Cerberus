// daemon/cpp/uds_server.h
#ifndef CERBERUSD_UDS_SERVER_H
#define CERBERUSD_UDS_SERVER_H

#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <functional>
#include <map>
#include <thread>

// [新] 用于管理每个客户端连接状态的结构体
struct ClientState {
    std::string read_buffer;
};

class UdsServer {
public:
    explicit UdsServer(const std::string& socket_name);
    ~UdsServer();

    UdsServer(const UdsServer&) = delete;
    UdsServer& operator=(const UdsServer&) = delete;

    void run();
    void stop();
    
    void broadcast_message(const std::string& message);
    void set_message_handler(std::function<void(int client_fd, const std::string&)> handler);
    bool send_message(int client_fd, const std::string& message);
    bool has_clients() const;
    void broadcast_message_except(const std::string& message, int excluded_fd);
    void set_disconnect_handler(std::function<void(int client_fd)> handler);

private:
    void server_loop();
    void add_client(int client_fd);
    void remove_client(int client_fd);
    void handle_client_data(int client_fd);
    void set_socket_non_blocking(int fd);

    std::string socket_name_;
    int server_fd_ = -1;
    int epoll_fd_ = -1;  // [新] epoll 文件描述符
    int event_fd_ = -1;  // [新] 用于优雅停机的 eventfd

    std::atomic<bool> is_running_;
    std::thread server_thread_;
    
    mutable std::mutex client_mutex_;
    std::map<int, ClientState> clients_; // [修改] 使用map管理客户端状态

    std::function<void(int, const std::string&)> on_message_received_;
    std::function<void(int)> on_disconnect_;
};

#endif //CERBERUSD_UDS_SERVER_H
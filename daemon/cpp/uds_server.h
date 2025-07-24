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
#include <deque>

// [STABILITY_FIX] 客户端状态现在包含读和写缓冲区
struct ClientState {
    std::string read_buffer;
    std::deque<char> write_buffer; // 使用deque作为写缓冲区，方便从头部移除已发送数据
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
    void send_message(int client_fd, const std::string& message); // 接口不变，实现改变
    bool has_clients() const;
    void broadcast_message_except(const std::string& message, int excluded_fd);
    void set_disconnect_handler(std::function<void(int client_fd)> handler);

private:
    void server_loop();
    void add_client(int client_fd);
    void remove_client(int client_fd);
    void handle_client_data(int client_fd);
    void handle_client_write(int client_fd); // [STABILITY_FIX] 新增：处理可写事件
    void set_socket_non_blocking(int fd);
    void modify_epoll_events(int fd, uint32_t events); // [STABILITY_FIX] 新增：辅助函数修改epoll监听事件

    std::string socket_name_;
    int server_fd_ = -1;
    int epoll_fd_ = -1;
    int event_fd_ = -1;

    std::atomic<bool> is_running_;
    std::thread server_thread_;
    
    mutable std::mutex client_mutex_;
    std::map<int, ClientState> clients_; 

    std::function<void(int, const std::string&)> on_message_received_;
    std::function<void(int)> on_disconnect_;
};

#endif //CERBERUSD_UDS_SERVER_H
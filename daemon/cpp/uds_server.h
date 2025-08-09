// daemon/cpp/uds_server.h
#ifndef CERBERUSD_UDS_SERVER_H
#define CERBERUSD_UDS_SERVER_H

#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <functional>
#include <map>
#include <set>

class UdsServer {
public:
    // 构造函数现在接收两种地址信息
    explicit UdsServer(const std::string& uds_socket_name, int tcp_port);
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

    void broadcast_message_to_ui(const std::string& message);
    void identify_client_as_ui(int client_fd);

private:
    void add_client(int client_fd);
    void remove_client(int client_fd);
    void handle_client_data(int client_fd);
    void schedule_client_removal(int client_fd);
    void process_clients_to_remove();

    // 成员变量更新，以支持双协议
    std::string uds_socket_name_;
    int tcp_port_;
    int server_fd_uds_; // UDS 监听 fd
    int server_fd_tcp_; // TCP 监听 fd
    std::atomic<bool> is_running_;
    
    std::vector<int> client_fds_;
    std::set<int> ui_client_fds_;
    mutable std::mutex client_mutex_;

    std::vector<int> clients_to_remove_;
    std::mutex clients_to_remove_mutex_;

    std::function<void(int, const std::string&)> on_message_received_;
    std::function<void(int)> on_disconnect_;
    std::map<int, std::string> client_buffers_;
};

#endif // CERBERUSD_UDS_SERVER_H
// daemon/cpp/uds_server.h
#ifndef CERBERUSD_UDS_SERVER_H
#define CERBERUSD_UDS_SERVER_H

#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <functional>
#include <map>

// UDS 服务器，用于和 UI/Probe 通信
class UdsServer {
public:
    explicit UdsServer(const std::string& socket_name);
    ~UdsServer();

    UdsServer(const UdsServer&) = delete;
    UdsServer& operator=(const UdsServer&) = delete;

    // 运行服务器的事件循环（阻塞）
    void run();
    // 停止服务器
    void stop();
    
    // 向所有连接的客户端广播消息
    void broadcast_message(const std::string& message);
    
    // 设置收到完整消息（以\n结尾）时的回调
    void set_message_handler(std::function<void(int client_fd, const std::string&)> handler);
    
    // 向指定客户端发送消息
    bool send_message(int client_fd, const std::string& message);
    
    // 检查是否有客户端连接
    bool has_clients() const;

    // [NEW] 向除指定fd外的所有客户端广播
    void broadcast_message_except(const std::string& message, int excluded_fd);
    
    // [NEW] 设置客户端断开连接时的回调
    void set_disconnect_handler(std::function<void(int client_fd)> handler);

private:
    void add_client(int client_fd);
    void remove_client(int client_fd);
    void handle_client_data(int client_fd);

    std::string socket_name_;
    int server_fd_;
    std::atomic<bool> is_running_;
    
    std::vector<int> client_fds_;
    mutable std::mutex client_mutex_;

    // 回调函数，int是来源客户端的fd
    std::function<void(int, const std::string&)> on_message_received_;
    std::function<void(int)> on_disconnect_;
    // 每个客户端的接收缓冲区，用于处理粘包
    std::map<int, std::string> client_buffers_;
};

#endif //CERBERUSD_UDS_SERVER_H
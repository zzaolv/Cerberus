// daemon/cpp/uds_server.h
#ifndef CERBERUSD_UDS_SERVER_H
#define CERBERUSD_UDS_SERVER_H

#include <string>
#include <vector>
#include <thread>
#include <mutex>
#include <atomic>
#include <functional> // 【新增】
#include <map>        // 【新增】

class UdsServer {
public:
    explicit UdsServer(const std::string& socket_path);
    ~UdsServer();

    void run();
    void stop();
    void broadcast_message(const std::string& message);

    // 【新增】设置消息处理回调函数
    void set_message_handler(std::function<void(const std::string&)> handler);

private:
    void add_client(int client_fd);
    void remove_client(int client_fd);
    void handle_client_data(int client_fd); // 【新增】

    std::string socket_path_;
    int server_fd_;
    std::atomic<bool> is_running_;
    
    std::vector<int> client_fds_;
    std::mutex client_mutex_;

    // 【新增】消息处理回调
    std::function<void(const std::string&)> on_message_received_;
    // 【新增】为每个客户端维护一个缓冲区，处理不完整的消息
    std::map<int, std::string> client_buffers_;
};

#endif //CERBERUSD_UDS_SERVER_H
#ifndef CERBERUSD_UDS_SERVER_H
#define CERBERUSD_UDS_SERVER_H

#include <string>
#include <vector>
#include <thread>
#include <mutex>
#include <atomic>

class UdsServer {
public:
    explicit UdsServer(const std::string& socket_path);
    ~UdsServer();

    // 启动服务器的主循环
    void run();
    // 停止服务器
    void stop();
    // 向所有连接的客户端广播消息
    void broadcast_message(const std::string& message);

private:
    void add_client(int client_fd);
    void remove_client(int client_fd);

    std::string socket_path_;
    int server_fd_;
    std::atomic<bool> is_running_;
    
    // 客户端文件描述符列表
    std::vector<int> client_fds_;
    std::mutex client_mutex_;
};

#endif //CERBERUSD_UDS_SERVER_H
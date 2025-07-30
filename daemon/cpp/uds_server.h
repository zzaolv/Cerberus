// daemon/cpp/uds_server.h
#ifndef CERBERUSD_UDS_SERVER_H
#define CERBERUSD_UDS_SERVER_H

#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <functional>
#include <map>
#include <set> // [新增] 用于存放UI客户端

// 为了减少文件重命名，我们继续使用这个类名，但它现在是一个TCP服务器
class UdsServer {
public:
    // 构造函数现在接受一个端口号
    explicit UdsServer(int port);
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

    // [新增] 专门用于心跳等UI特定广播
    void broadcast_message_to_ui(const std::string& message);
    // [新增] 外部调用的方法，用于识别客户端类型
    void identify_client_as_ui(int client_fd);

private:
    void add_client(int client_fd);
    void remove_client(int client_fd);
    void handle_client_data(int client_fd);

    int port_;
    int server_fd_;
    std::atomic<bool> is_running_;
    
    std::vector<int> client_fds_;
    // [新增] 专门存放UI客户端的fd，用于发送心跳
    std::set<int> ui_client_fds_;
    mutable std::mutex client_mutex_;

    std::function<void(int, const std::string&)> on_message_received_;
    std::function<void(int)> on_disconnect_;
    std::map<int, std::string> client_buffers_;
};

#endif //CERBERUSD_UDS_SERVER_H
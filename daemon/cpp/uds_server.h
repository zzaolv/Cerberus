// daemon/cpp/uds_server.h
#ifndef CERBERUSD_UDS_SERVER_H
#define CERBERUSD_UDS_SERVER_H

#include <string>
#include <vector>
#include <thread>
#include <mutex>
#include <atomic>
#include <functional>
#include <map>

class UdsServer {
public:
    explicit UdsServer(const std::string& socket_path);
    ~UdsServer();

    void run();
    void stop();
    void broadcast_message(const std::string& message);
    void set_message_handler(std::function<void(const std::string&)> handler);

    /**
     * @brief 检查当前是否有任何客户端连接。
     * @return 如果至少有一个客户端连接，则返回 true，否则返回 false。
     */
    bool has_clients();

private:
    void add_client(int client_fd);
    void remove_client(int client_fd);
    void handle_client_data(int client_fd);

    std::string socket_path_;
    int server_fd_;
    std::atomic<bool> is_running_;
    
    std::vector<int> client_fds_;
    std::mutex client_mutex_;

    std::function<void(const std::string&)> on_message_received_;
    std::map<int, std::string> client_buffers_;
};

#endif //CERBERUSD_UDS_SERVER_H
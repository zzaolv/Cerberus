// daemon/cpp/uds_server.h
#ifndef CERBERUSD_UDS_SERVER_H
#define CERBERUSD_UDS_SERVER_H

#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <functional>
#include <map>

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
    void add_client(int client_fd);
    void remove_client(int client_fd);
    void handle_client_data(int client_fd);

    std::string socket_name_;
    int server_fd_;
    std::atomic<bool> is_running_;
    
    std::vector<int> client_fds_;
    mutable std::mutex client_mutex_;

    std::function<void(int, const std::string&)> on_message_received_;
    std::function<void(int)> on_disconnect_;
    std::map<int, std::string> client_buffers_;
    std::string socket_path_; // [核心新增]
};

#endif //CERBERUSD_UDS_SERVER_H
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
    
    void set_message_handler(std::function<void(int client_fd, const std::string&)> handler);

    void send_message_to_client(int client_fd, const std::string& message);

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

    std::function<void(int, const std::string&)> on_message_received_;
    std::map<int, std::string> client_buffers_;
};

#endif //CERBERUSD_UDS_SERVER_H
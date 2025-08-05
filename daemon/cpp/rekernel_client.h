// daemon/cpp/rekernel_client.h
#ifndef CERBERUS_REKERNEL_CLIENT_H
#define CERBERUS_REKERNEL_CLIENT_H

#include <string>
#include <thread>
#include <atomic>
#include <functional>
#include <optional>
#include <map>

// 定义从 Re-Kernel 消息中解析出的事件结构体
struct ReKernelSignalEvent {
    int signal;
    int killer_pid;
    int killer_uid;
    int dest_pid;
    int dest_uid;
};

struct ReKernelBinderEvent {
    std::string binder_type;
    bool is_oneway;
    int from_pid;
    int from_uid;
    int target_pid;
    int target_uid;
    std::string rpc_name;
    int code;
};

class ReKernelClient {
public:
    ReKernelClient();
    ~ReKernelClient();

    // 启动客户端（在单独的线程中）
    void start();
    // 停止客户端
    void stop();

    // 设置事件回调
    void set_signal_handler(std::function<void(const ReKernelSignalEvent&)> handler);
    void set_binder_handler(std::function<void(const ReKernelBinderEvent&)> handler);

    // 检查 Re-Kernel 是否被检测到并正在运行
    bool is_active() const;

private:
    void listener_thread_func();
    std::optional<int> detect_netlink_unit();
    void parse_and_dispatch(const std::string& message);
    std::map<std::string, std::string> parse_params(const std::string& message_body);

    std::atomic<bool> is_running_{false};
    std::atomic<bool> is_active_{false};
    std::thread listener_thread_;
    int netlink_fd_ = -1;
    int netlink_unit_ = -1;

    std::function<void(const ReKernelSignalEvent&)> on_signal_received_;
    std::function<void(const ReKernelBinderEvent&)> on_binder_received_;
};

#endif // CERBERUS_REKERNEL_CLIENT_H
// daemon/cpp/process_monitor.h
#ifndef CERBERUS_PROCESS_MONITOR_H
#define CERBERUS_PROCESS_MONITOR_H

#include <functional>
#include <thread>
#include <atomic>

// 定义进程事件类型
enum class ProcessEventType {
    FORK,  // 进程创建
    EXEC,  // 进程执行新程序
    EXIT   // 进程退出
};

// 定义进程事件的回调函数类型
using ProcessEventCallback = std::function<void(ProcessEventType type, int pid, int ppid)>;

class ProcessMonitor {
public:
    ProcessMonitor();
    ~ProcessMonitor();

    // 开始监听进程事件
    void start(ProcessEventCallback callback);
    // 停止监听
    void stop();

private:
    void monitor_loop(); // 监听循环

    std::thread monitor_thread_;
    std::atomic<bool> is_running_{false};
    int netlink_socket_ = -1;
    ProcessEventCallback callback_;
};

#endif //CERBERUS_PROCESS_MONITOR_H
// daemon/cpp/process_monitor.h
#ifndef CERBERUS_PROCESS_MONITOR_H
#define CERBERUS_PROCESS_MONITOR_H

#include <functional>
#include <thread>
#include <atomic>

// 内核发出的进程事件类型
enum class ProcessEventType {
    FORK,  // 进程创建 (fork)
    EXEC,  // 执行新程序 (exec)
    EXIT   // 进程退出
};

// 进程事件回调函数签名
using ProcessEventCallback = std::function<void(ProcessEventType type, int pid, int ppid)>;

// 通过Netlink实时监控系统进程事件
class ProcessMonitor {
public:
    ProcessMonitor();
    ~ProcessMonitor();

    // 禁止拷贝和赋值
    ProcessMonitor(const ProcessMonitor&) = delete;
    ProcessMonitor& operator=(const ProcessMonitor&) = delete;

    // 开始监听进程事件，传入回调函数
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
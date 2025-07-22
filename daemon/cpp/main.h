// daemon/cpp/main.h
#ifndef CERBERUS_MAIN_H
#define CERBERUS_MAIN_H

#include <atomic>
#include <nlohmann/json.hpp>
#include <functional>
#include <variant>
#include <string>
#include <set>

// 这个 Task 结构看起来是为更高级的事件驱动模型准备的，暂时保留
struct ConfigChangeTask { nlohmann::json payload; };
struct TopAppChangeTask { std::set<int> pids; };
struct TickTask {};
struct RefreshDashboardTask {};
struct ProbeHelloTask { int fd; };
struct ClientDisconnectTask { int fd; };
struct ProbeFgEventTask { nlohmann::json payload; };
struct ProbeBgEventTask { nlohmann::json payload; };

using Task = std::variant<
    ConfigChangeTask,
    TopAppChangeTask,
    TickTask,
    RefreshDashboardTask,
    ProbeHelloTask,
    ClientDisconnectTask,
    ProbeFgEventTask,
    ProbeBgEventTask
>;

// --- 全局函数声明 ---
void broadcast_dashboard_update();
void notify_probe_of_config_change();

// 如果您暂时不使用 schedule_task，可以注释掉它以避免潜在的未定义引用错误
// void schedule_task(Task task); 

// [修复] 声明全局 probe fd
extern std::atomic<int> g_probe_fd;

#endif //CERBERUS_MAIN_H
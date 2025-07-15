#include "uds_server.h"
#include "nlohmann/json.hpp" // 引入json库
#include <android/log.h>
#include <csignal>
#include <thread>
#include <chrono>

#define LOG_TAG "cerberusd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using json = nlohmann::json;

// 与UI App中的UdsClient保持一致
const std::string SOCKET_NAME = "cerberus_socket";

// 全局变量，用于信号处理
std::unique_ptr<UdsServer> g_server = nullptr;
std::atomic<bool> g_is_running = true;

// 数据广播线程
void broadcast_thread() {
    LOGI("Broadcast thread started.");
    while (g_is_running) {
        if (g_server) {
            // --- 创建模拟的仪表盘数据 ---
            json payload;
            // 全局状态
            payload["global_stats"] = {
                {"total_cpu_usage_percent", 15.0 + (rand() % 10)},
                {"total_mem_kb", 8 * 1024 * 1024},
                {"avail_mem_kb", (4 + (rand() % 2)) * 1024 * 1024},
                {"net_down_speed_bps", (long)(rand() % 100000)},
                {"net_up_speed_bps", (long)(rand() % 50000)},
                {"active_profile_name", "常规模式"}
            };
            // 应用状态
            payload["apps_runtime_state"] = json::array({
                {
                    {"package_name", "com.fake.app1"},
                    {"app_name", "模拟应用A"},
                    {"display_status", "FOREGROUND"},
                    {"mem_usage_kb", 150 * 1024},
                    {"cpu_usage_percent", 25.5f},
                    {"is_foreground", true}
                },
                {
                    {"package_name", "com.fake.app2"},
                    {"app_name", "模拟应用B"},
                    {"display_status", "FROZEN"},
                    {"active_freeze_mode", "CGROUP"},
                    {"mem_usage_kb", 50 * 1024},
                    {"cpu_usage_percent", 0.1f},
                    {"is_foreground", false}
                }
            });

            json message = {
                {"v", 1},
                {"type", "stream.dashboard_update"},
                {"payload", payload}
            };
            
            // 广播消息
            g_server->broadcast_message(message.dump());
        }
        // 每2秒广播一次
        std::this_thread::sleep_for(std::chrono::seconds(2));
    }
    LOGI("Broadcast thread finished.");
}

// 信号处理函数
void signal_handler(int signum) {
    LOGI("Caught signal %d, shutting down...", signum);
    g_is_running = false;
    if (g_server) {
        g_server->stop(); // 优雅地停止服务器
    }
}

int main() {
    // 注册信号处理
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);

    LOGI("Cerberus Daemon v1.0 starting...");

    // 初始化并启动服务器
    g_server = std::make_unique<UdsServer>(SOCKET_NAME);
    
    // 启动广播线程
    std::thread broadcaster(broadcast_thread);

    // 主线程阻塞在这里，运行服务器的accept循环
    g_server->run();
    
    // 等待广播线程结束
    if (broadcaster.joinable()) {
        broadcaster.join();
    }
    
    LOGI("Cerberus Daemon has shut down cleanly.");
    return 0;
}
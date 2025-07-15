#include "uds_server.h"
#include "nlohmann/json.hpp"
#include <android/log.h>
#include <csignal>
#include <thread>
#include <chrono>

#define LOG_TAG "cerberusd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using json = nlohmann::json;

const std::string SOCKET_NAME = "cerberus_socket";

std::unique_ptr<UdsServer> g_server = nullptr;
std::atomic<bool> g_is_running = true;

// 信号处理函数，保持不变
void signal_handler(int signum) {
    LOGI("Caught signal %d, shutting down...", signum);
    g_is_running = false;
    if (g_server) {
        g_server->stop();
    }
}

int main() {
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);

    LOGI("Cerberus Daemon v1.1 (Thread-Safe) starting...");

    g_server = std::make_unique<UdsServer>(SOCKET_NAME);

    // 1. 将服务器的accept循环放在一个专门的子线程中
    std::thread server_thread([&]() {
        LOGI("Server thread started, entering accept loop.");
        g_server->run();
        LOGI("Server thread finished.");
    });

    LOGI("Main thread is starting broadcast loop.");
    // 2. 主线程负责数据生成和广播
    while (g_is_running) {
        // --- 创建模拟的仪表盘数据 ---
        json payload;
        payload["global_stats"] = {
            {"total_cpu_usage_percent", 15.0 + (rand() % 10)},
            {"total_mem_kb", 8 * 1024 * 1024},
            {"avail_mem_kb", (4 + (rand() % 2)) * 1024 * 1024},
            {"net_down_speed_bps", (long)(rand() % 100000)},
            {"net_up_speed_bps", (long)(rand() % 50000)},
            {"active_profile_name", "常规模式"}
        };
        payload["apps_runtime_state"] = json::array({
            {{"package_name", "com.fake.app1"}, {"app_name", "模拟应用A"}, {"display_status", "FOREGROUND"}, {"mem_usage_kb", 150*1024}, {"cpu_usage_percent", 25.5f}, {"is_foreground", true}},
            {{"package_name", "com.fake.app2"}, {"app_name", "模拟应用B"}, {"display_status", "FROZEN"}, {"active_freeze_mode", "CGROUP"}, {"mem_usage_kb", 50*1024}, {"cpu_usage_percent", 0.1f}, {"is_foreground", false}}
        });

        json message = {
            {"v", 1},
            {"type", "stream.dashboard_update"},
            {"payload", payload}
        };
        
        // 广播消息
        g_server->broadcast_message(message.dump());
        
        // 每2秒广播一次
        std::this_thread::sleep_for(std::chrono::seconds(2));
    }
    
    // 等待服务器线程结束
    if (server_thread.joinable()) {
        server_thread.join();
    }
    
    LOGI("Cerberus Daemon has shut down cleanly.");
    return 0;
}
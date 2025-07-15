// daemon/cpp/main.cpp

#include "uds_server.h"
#include "nlohmann/json.hpp"
#include <android/log.h>
#include <csignal>
#include <thread>
#include <chrono>
#include <memory>
#include <atomic>

#define LOG_TAG "cerberusd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using json = nlohmann::json;

// 与 UdsClient.kt 中的 SOCKET_NAME 保持一致
const std::string SOCKET_NAME = "cerberus_socket";

// 全局变量，用于信号处理和线程间通信
std::unique_ptr<UdsServer> g_server = nullptr;
std::atomic<bool> g_is_running = true;

void signal_handler(int signum) {
    LOGI("Caught signal %d, initiating shutdown...", signum);
    g_is_running = false;
    if (g_server) {
        // 这会中断 accept() 调用，让主线程退出循环
        g_server->stop();
    }
}

// **[新功能]** 专用于数据广播的工作线程函数
void data_broadcaster_thread() {
    LOGI("Data broadcaster thread started.");
    
    while (g_is_running) {
        try {
            // --- 创建模拟的仪表盘数据 ---
            json payload;
            payload["global_stats"] = {
                {"total_cpu_usage_percent", 15.0 + (rand() % 10)},
                {"total_mem_kb", 8 * 1024 * 1024L},
                {"avail_mem_kb", (4 + (rand() % 2)) * 1024 * 1024L},
                {"net_down_speed_bps", (long)(rand() % 100000)},
                {"net_up_speed_bps", (long)(rand() % 50000)},
                {"active_profile_name", "⚡️ 省电模式"}
            };
            payload["apps_runtime_state"] = json::array({
                {{"package_name", "com.tencent.mm"}, {"app_name", "微信"}, {"display_status", "FOREGROUND"}, {"mem_usage_kb", 150*1024L}, {"cpu_usage_percent", 25.5f}, {"is_foreground", true}},
                {{"package_name", "com.alibaba.taobao"}, {"app_name", "淘宝"}, {"display_status", "FROZEN"}, {"active_freeze_mode", "CGROUP"}, {"mem_usage_kb", 50*1024L}, {"cpu_usage_percent", 0.1f}, {"is_foreground", false}}
            });

            json message = {
                {"v", 1},
                {"type", "stream.dashboard_update"},
                {"payload", payload}
            };
            
            // 检查服务器指针是否有效
            if (g_server) {
                 g_server->broadcast_message(message.dump());
            }

        } catch (const std::exception& e) {
            LOGE("Exception in broadcaster thread: %s", e.what());
        }
        
        // 使用C++20的新方式来处理带中断的休眠
        for (int i = 0; i < 20 && g_is_running; ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
    }
    LOGI("Data broadcaster thread finished.");
}


int main() {
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);

    LOGI("Cerberus Daemon v1.1 (Robust Arch) starting...");

    // 初始化服务器
    g_server = std::make_unique<UdsServer>(SOCKET_NAME);
    
    // **[架构变更]** 将广播逻辑放入后台线程
    std::thread broadcaster(data_broadcaster_thread);

    // **[架构变更]** 主线程负责运行服务器的 accept() 循环，这将阻塞主线程，使进程保持活动状态
    LOGI("Main thread starting UDS server loop...");
    g_server->run();

    // 当 g_server->run() 返回时，意味着服务器已停止
    LOGI("UDS server loop has finished. Cleaning up...");

    // 等待广播线程干净地退出
    if (broadcaster.joinable()) {
        broadcaster.join();
    }
    
    LOGI("Cerberus Daemon has shut down cleanly.");
    return 0;
}
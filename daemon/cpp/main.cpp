// daemon/cpp/main.cpp

// 核心项目头文件
#include "main.h"
#include "uds_server.h"
#include "state_manager.h"
#include "system_monitor.h"
#include "database_manager.h"
#include "action_executor.h"

// 第三方库头文件
#include <nlohmann/json.hpp>

// C++ 标准库与系统库头文件
#include <android/log.h>
#include <csignal>
#include <thread>
#include <chrono>
#include <memory>
#include <atomic>
#include <filesystem>
#include <mutex>
#include <unistd.h>
#include <queue>
#include <sys/eventfd.h>
#include <sys/select.h> // 明确包含 select

// 日志宏定义
#define LOG_TAG "cerberusd_main_v9_stable"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// 命名空间别名
using json = nlohmann::json;
namespace fs = std::filesystem;

// --- 全局变量定义 ---
static std::unique_ptr<UdsServer> g_server;
static std::shared_ptr<StateManager> g_state_manager;
static std::atomic<bool> g_is_running = true;

// 在 main.h 中声明为 extern，在此处进行定义
std::atomic<int> g_probe_fd = -1;

// 任务队列相关全局变量 (仅限 main.cpp 使用)
static std::queue<Task> g_task_queue;
static std::mutex g_task_queue_mutex;
static int g_event_fd = -1;

void schedule_task(Task task) {
    {
        std::lock_guard<std::mutex> lock(g_task_queue_mutex);
        g_task_queue.push(std::move(task));
    }
    // Write to eventfd to wake up the select() in main loop
    if (g_event_fd >= 0) {
        uint64_t val = 1;
        if (write(g_event_fd, &val, sizeof(val)) < 0) {
            LOGE("Failed to write to eventfd: %s", strerror(errno));
        }
    }
}

// --- UDS Callbacks ---
void handle_client_message(int client_fd, const std::string& message_str) {
    try {
        json msg = json::parse(message_str);
        std::string type = msg.value("type", "");
        
        if (type == "event.probe_hello") schedule_task(ProbeHelloTask{client_fd});
        else if (type == "event.app_foreground") schedule_task(ProbeFgEventTask{msg.at("payload")});
        else if (type == "event.app_background") schedule_task(ProbeBgEventTask{msg.at("payload")});
        else if (type == "cmd.set_policy") schedule_task(ConfigChangeTask{msg.at("payload")});
        else if (type == "query.refresh_dashboard") schedule_task(RefreshDashboardTask{});
        else if (type == "query.get_all_policies") {
            // This request needs an immediate synchronous response, so handle it directly
            if (g_state_manager) {
                json payload = g_state_manager->get_full_config_for_ui();
                g_server->send_message(client_fd, json{{"type", "resp.all_policies"}, {"req_id", msg.value("req_id", "")}, {"payload", payload}}.dump());
            }
        }
    } catch (const json::exception& e) {
        LOGE("JSON Error: %s in msg: %s", e.what(), message_str.c_str());
    }
}

void handle_client_disconnect(int client_fd) {
    schedule_task(ClientDisconnectTask{client_fd});
}

// --- Global Functions ---
void broadcast_dashboard_update() {
    if (g_server && g_server->has_clients() && g_state_manager) {
        LOGD("Broadcasting dashboard update...");
        json payload = g_state_manager->get_dashboard_payload();
        g_server->broadcast_message_except(json{{"type", "stream.dashboard_update"}, {"payload", payload}}.dump(), g_probe_fd.load());
    }
}

void notify_probe_of_config_change() {
    int probe_fd = g_probe_fd.load();
    if (g_server && probe_fd != -1 && g_state_manager) {
        LOGD("Sending config update to Probe fd %d.", probe_fd);
        json payload = g_state_manager->get_probe_config_payload();
        g_server->send_message(probe_fd, json{{"type", "stream.probe_config_update"}, {"payload", payload}}.dump());
    }
}

void signal_handler(int signum) {
    LOGW("Signal %d received, shutting down...", signum);
    g_is_running = false;
    // Write to eventfd to break the main loop immediately
    if (g_event_fd >= 0) {
        uint64_t val = 1;
        write(g_event_fd, &val, sizeof(val));
    }
}

// --- Main Loop ---
int main(int argc, char *argv[]) {
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);
    signal(SIGPIPE, SIG_IGN);

    g_event_fd = eventfd(0, EFD_CLOEXEC);
    if (g_event_fd < 0) {
        LOGE("Failed to create eventfd, exiting.");
        return 1;
    }

    const std::string DATA_DIR = "/data/adb/cerberus";
    const std::string DB_PATH = DATA_DIR + "/cerberus.db";
    LOGI("Project Cerberus Daemon v9 (Stable) starting... (PID: %d)", getpid());
    
    try {
        if (!fs::exists(DATA_DIR)) fs::create_directories(DATA_DIR);
    } catch (const fs::filesystem_error& e) {
        LOGE("Failed to create data directory: %s", e.what());
        return 1;
    }

    auto db_manager = std::make_shared<DatabaseManager>(DB_PATH);
    auto action_executor = std::make_shared<ActionExecutor>();
    auto sys_monitor = std::make_shared<SystemMonitor>();
    g_state_manager = std::make_shared<StateManager>(db_manager, sys_monitor, action_executor);
    
    // Start active monitor
    sys_monitor->start_top_app_monitor([](const std::set<int>& pids){
        schedule_task(TopAppChangeTask{pids});
    });

    std::thread uds_thread([](){
        g_server = std::make_unique<UdsServer>("cerberus_socket");
        g_server->set_message_handler(handle_client_message);
        g_server->set_disconnect_handler(handle_client_disconnect);
        g_server->run();
    });

    LOGI("Main event loop started.");

    while (g_is_running) {
        fd_set read_fds;
        FD_ZERO(&read_fds);
        FD_SET(g_event_fd, &read_fds);
        
        struct timeval tv { .tv_sec = 3, .tv_usec = 0 }; // 3-second timeout for tick
        int ret = select(g_event_fd + 1, &read_fds, nullptr, nullptr, &tv);

        if (!g_is_running) break;

        if (ret < 0) {
            if(errno == EINTR) continue;
            LOGE("select() failed: %s. Exiting.", strerror(errno));
            break;
        }

        if (ret == 0) { // Timeout
            schedule_task(TickTask{});
        }

        if (FD_ISSET(g_event_fd, &read_fds)) {
            uint64_t val;
            read(g_event_fd, &val, sizeof(val)); // Clear the eventfd
        }

        // Process all tasks in the queue
        std::queue<Task> tasks_to_process;
        {
            std::lock_guard<std::mutex> lock(g_task_queue_mutex);
            tasks_to_process.swap(g_task_queue);
        }

        while(!tasks_to_process.empty()) {
            Task task = tasks_to_process.front();
            tasks_to_process.pop();
            
            std::visit([&](auto&& arg) {
                using T = std::decay_t<decltype(arg)>;
                if constexpr (std::is_same_v<T, TickTask>) {
                    if (g_state_manager->tick()) broadcast_dashboard_update();
                } else if constexpr (std::is_same_v<T, RefreshDashboardTask>) {
                    broadcast_dashboard_update();
                } else if constexpr (std::is_same_v<T, ConfigChangeTask>) {
                    if(g_state_manager->on_config_changed_from_ui(arg.payload)) {
                        notify_probe_of_config_change();
                    }
                    broadcast_dashboard_update();
                } else if constexpr (std::is_same_v<T, TopAppChangeTask>) {
                    g_state_manager->on_top_app_changed(arg.pids);
                } else if constexpr (std::is_same_v<T, ProbeHelloTask>) {
                     g_probe_fd = arg.fd;
                     notify_probe_of_config_change();
                } else if constexpr (std::is_same_v<T, ClientDisconnectTask>) {
                    if(arg.fd == g_probe_fd.load()) g_probe_fd = -1;
                } else if constexpr (std::is_same_v<T, ProbeFgEventTask>) {
                    g_state_manager->on_app_foreground(arg.payload);
                } else if constexpr (std::is_same_v<T, ProbeBgEventTask>) {
                    g_state_manager->on_app_background(arg.payload);
                }
            }, task);
        }
    }

    LOGI("Shutting down...");
    if (g_server) g_server->stop();
    if (uds_thread.joinable()) uds_thread.join();
    if (sys_monitor) sys_monitor->stop_top_app_monitor();
    if (g_event_fd >= 0) close(g_event_fd);

    LOGI("Cerberus Daemon has shut down cleanly.");
    return 0;
}
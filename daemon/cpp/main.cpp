// daemon/cpp/main.cpp
#include "uds_server.h"
#include "state_manager.h"
#include "system_monitor.h"
#include "database_manager.h"
#include "action_executor.h"
#include <nlohmann/json.hpp>
#include <android/log.h>
#include <csignal>
#include <thread>
#include <chrono>
#include <memory>
#include <atomic>
#include <filesystem>
#include <condition_variable>
#include <unistd.h>

#define LOG_TAG "cerberusd_main_v4.0"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using json = nlohmann::json;
namespace fs = std::filesystem;

// Global variables remain the same
const std::string SOCKET_NAME = "cerberus_socket";
const std::string DATA_DIR = "/data/adb/cerberus";
const std::string DB_PATH = DATA_DIR + "/cerberus.db";

std::atomic<bool> g_is_running = true;
std::unique_ptr<UdsServer> g_server;
std::shared_ptr<StateManager> g_state_manager;
std::atomic<int> g_probe_fd = -1;
std::atomic<bool> g_force_refresh_flag = false;
std::mutex g_worker_mutex;
std::condition_variable g_worker_cv;

void trigger_state_broadcast() {
    g_force_refresh_flag = true;
    g_worker_cv.notify_one();
}

void notify_probe_of_config_change() {
    int probe_fd = g_probe_fd.load();
    if (g_server && probe_fd != -1 && g_state_manager) {
        LOGI("[NOTIFY_PROBE] Sending config update to Probe fd %d.", probe_fd);
        json payload = g_state_manager->get_probe_config_payload();
        json message = {
            {"v", 4}, // Protocol version bump
            {"type", "stream.probe_config_update"},
            {"payload", payload}
        };
        // Use broadcast because there might be other probes or listeners
        g_server->broadcast_message(message.dump());
    }
}

void handle_client_disconnect(int client_fd) {
    if (client_fd == g_probe_fd.load()) {
        LOGW("Probe has disconnected (fd: %d).", client_fd);
        g_probe_fd = -1;
        if (g_state_manager) g_state_manager->on_probe_disconnect();
    } else {
        LOGI("UI client has disconnected (fd: %d).", client_fd);
    }
}

// [FIX] Reworked message handling for the new architecture
void handle_client_message(int client_fd, const std::string& message_str) {
    LOGI("[RECV MSG] From fd %d: %s", client_fd, message_str.c_str());
    try {
        json msg = json::parse(message_str);
        std::string type = msg.value("type", "");
        
        if (type == "event.probe_hello") {
            LOGI("Probe hello received from fd %d. Registering as official Probe.", client_fd);
            g_probe_fd = client_fd;
            if (g_state_manager) g_state_manager->on_probe_hello(client_fd);
            notify_probe_of_config_change(); // Immediately sync state to the new probe
            trigger_state_broadcast();
            return;
        }

        if (type == "event.app_state_changed") {
            if (g_state_manager) g_state_manager->on_app_state_changed_from_probe(msg.at("payload"));
            trigger_state_broadcast();
            return;
        }

        // --- Command Handling ---
        
        // This is now the critical path for unfreezing
        if (type == "cmd.request_immediate_unfreeze") {
            if (g_state_manager) {
                bool state_changed = g_state_manager->on_unfreeze_request_from_probe(msg.at("payload"));
                // If the state changed (i.e., an app was actually unfrozen),
                // we MUST notify all probes so they can update their caches.
                if (state_changed) {
                    notify_probe_of_config_change();
                }
                // No response needed, the config update is the confirmation.
            }
        } else if (type == "cmd.set_policy") {
            // ... (remains the same as previous version)
            const auto& payload = msg.at("payload");
            LOGI("Processing set_policy for %s (user %d)", payload.value("package_name", "N/A").c_str(), payload.value("user_id", -1));
            AppConfig new_config;
            new_config.package_name = payload.value("package_name", "");
            new_config.user_id = payload.value("user_id", 0);
            new_config.policy = static_cast<AppPolicy>(payload.value("policy", 2));
            new_config.force_playback_exempt = payload.value("force_playback_exempt", false);
            new_config.force_network_exempt = payload.value("force_network_exempt", false);
            if (g_state_manager) {
                g_state_manager->on_config_changed_from_ui(new_config);
                notify_probe_of_config_change();
                trigger_state_broadcast();
            }
        } else if (type == "query.get_all_policies") {
            // ... (remains the same)
            if (g_state_manager) {
                json response_payload = g_state_manager->get_full_config_for_ui();
                json response_msg = {
                    {"v", 4},
                    {"type", "resp.all_policies"},
                    {"req_id", msg.value("req_id", "")},
                    {"payload", response_payload}
                };
                 g_server->send_message(client_fd, response_msg.dump());
            }
        } else if (type == "query.refresh_dashboard") {
            trigger_state_broadcast();
        }
    } catch (const json::exception& e) {
        LOGE("JSON Error: %s. Message: %s", e.what(), message_str.c_str());
    }
}


// ... (signal_handler and main function structure remain the same, just ensure they call the updated functions)
void signal_handler(int signum) {
    LOGI("Caught signal %d, initiating shutdown...", signum);
    g_is_running = false;
    g_worker_cv.notify_one();
    if (g_server) g_server->stop();
}

void worker_thread_func() {
    LOGI("Worker thread started.");
    while (g_is_running) {
        {
            std::unique_lock<std::mutex> lock(g_worker_mutex);
            g_worker_cv.wait_for(lock, std::chrono::seconds(3), [&]{
                return !g_is_running.load() || g_force_refresh_flag.load();
            });
        }

        if (!g_is_running) break;
        
        bool needs_probe_update = false;
        if (g_state_manager) {
            needs_probe_update = g_state_manager->tick();
        }

        if (needs_probe_update) {
            LOGI("State manager reported significant change (app frozen), notifying probe.");
            notify_probe_of_config_change();
        }

        if (g_force_refresh_flag.load()) {
            LOGI("Forced refresh triggered for UI.");
            g_force_refresh_flag = false;
        }
        
        if (g_server && g_server->has_clients()) {
            json payload = g_state_manager->get_dashboard_payload();
            json message = {
                {"v", 4},
                {"type", "stream.dashboard_update"},
                {"payload", payload}
            };
            g_server->broadcast_message_except(message.dump(), g_probe_fd.load());
        }
    }
    LOGI("Worker thread finished.");
}

int main(int argc, char *argv[]) {
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);

    LOGI("Project Cerberus Daemon v4.0 starting... (PID: %d)", getpid());

    try {
        if (!fs::exists(DATA_DIR)) {
            fs::create_directories(DATA_DIR);
            LOGI("Created data directory: %s", DATA_DIR.c_str());
        }
    } catch (const fs::filesystem_error& e) {
        LOGE("Failed to create data directory: %s. Exiting.", e.what());
        return 1;
    }

    auto db_manager = std::make_shared<DatabaseManager>(DB_PATH);
    auto action_executor = std::make_shared<ActionExecutor>();
    auto sys_monitor = std::make_shared<SystemMonitor>();
    g_state_manager = std::make_shared<StateManager>(db_manager, sys_monitor, action_executor);
    
    std::thread worker_thread(worker_thread_func);

    g_server = std::make_unique<UdsServer>(SOCKET_NAME);
    g_server->set_message_handler(handle_client_message);
    g_server->set_disconnect_handler(handle_client_disconnect);
    g_server->run();

    LOGI("Server loop has finished. Cleaning up...");
    g_is_running = false;
    g_probe_fd = -1;
    if (worker_thread.joinable()) worker_thread.join();
    
    LOGI("Cerberus Daemon has shut down cleanly.");
    return 0;
}
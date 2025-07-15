#include "uds_server.h"
#include <android/log.h>
#include <csignal>
#include <unistd.h> // <-- 在这里添加头文件

#define LOG_TAG "cerberusd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

const std::string SOCKET_NAME = "cerberus_socket";

// 全局指针，用于信号处理
std::unique_ptr<UdsServer> g_server = nullptr;

void signal_handler(int signum) {
    LOGI("Caught signal %d, shutting down...", signum);
    if (g_server) {
        g_server->stop();
    }
    // 退出进程
    exit(0);
}

int main() {
    // 立即打印日志，这是第一条有效指令
    LOGI("Cerberus Daemon v1.1 (Single-Threaded Test) starting... PID: %d", getpid());

    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);

    // 创建服务器实例
    g_server = std::make_unique<UdsServer>(SOCKET_NAME);

    LOGI("Server object created. Starting run loop...");

    // 在主线程中直接运行服务器的 accept 循环。
    // 程序将阻塞在这里，直到被信号中断。
    g_server->run();

    LOGI("Server run loop finished. Daemon shutting down.");

    return 0;
}
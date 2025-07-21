// daemon/cpp/main.h
#ifndef CERBERUS_MAIN_H
#define CERBERUS_MAIN_H

#include <atomic>

// 声明全局可用的广播函数
void broadcast_dashboard_update();

// [修复] 将 notify_probe_of_config_change 的声明也加入头文件
void notify_probe_of_config_change();

// 声明全局广播请求标志
extern std::atomic<bool> g_needs_broadcast;

#endif //CERBERUS_MAIN_H
// daemon/cpp/main.h
#ifndef CERBERUS_MAIN_H
#define CERBERUS_MAIN_H

#include <atomic> // 引入 atomic

// 声明全局可用的广播函数
void broadcast_dashboard_update();

// [V8 修复] 声明全局广播请求标志
extern std::atomic<bool> g_needs_broadcast;

#endif //CERBERUS_MAIN_H
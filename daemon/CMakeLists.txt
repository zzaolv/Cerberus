# daemon/CMakeLists.txt
#
# Project Cerberus - Daemon Build Script
# Verified against the provided third_party directory structure.
#
cmake_minimum_required(VERSION 3.20)
project(cerberusd CXX C)

# --- 1. 定义第三方库的根目录 ---
set(THIRDPARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# --- 2. 构建 sqlite3 库 ---
# 源文件路径: third_party/sqlite3/sqlite3.c
add_library(sqlite3_lib STATIC
    ${THIRDPARTY_DIR}/sqlite3/sqlite3.c
)
# 头文件目录: third_party/sqlite3/ (包含 sqlite3.h)
target_include_directories(sqlite3_lib PUBLIC
    ${THIRDPARTY_DIR}/sqlite3
)

# --- 3. 构建 SQLiteCpp 库 ---
# 源文件路径: third_party/sqlitecpp/src/*.cpp
add_library(sqlitecpp_lib STATIC
    ${THIRDPARTY_DIR}/sqlitecpp/src/Backup.cpp
    ${THIRDPARTY_DIR}/sqlitecpp/src/Column.cpp
    ${THIRDPARTY_DIR}/sqlitecpp/src/Database.cpp
    ${THIRDPARTY_DIR}/sqlitecpp/src/Exception.cpp
    ${THIRDPARTY_DIR}/sqlitecpp/src/Savepoint.cpp
    ${THIRDPARTY_DIR}/sqlitecpp/src/Statement.cpp
    ${THIRDPARTY_DIR}/sqlitecpp/src/Transaction.cpp
)
# 头文件目录: third_party/sqlitecpp/include/
# 这样设置后，代码中可以使用 #include "SQLiteCpp/Database.h" 的形式
target_include_directories(sqlitecpp_lib PUBLIC
    ${THIRDPARTY_DIR}/sqlitecpp/include
)
# 声明 SQLiteCpp 依赖于我们刚刚定义的 sqlite3_lib
target_link_libraries(sqlitecpp_lib PUBLIC sqlite3_lib)

# --- 4. 定义最终的可执行文件 'cerberusd' ---
add_executable(cerberusd
    cpp/main.cpp
    cpp/uds_server.cpp
    cpp/system_monitor.cpp
    cpp/database_manager.cpp
    cpp/state_manager.cpp
    cpp/action_executor.cpp
   # cpp/process_monitor.cpp
    cpp/logger.cpp
    cpp/time_series_database.cpp
    cpp/rekernel_client.cpp
)

# --- 5. 为 'cerberusd' 添加头文件搜索路径 ---
# nlohmann_json 是 header-only 库，只需提供其 include 目录
# 目录路径: third_party/nlohmann_json/include/
# 这样设置后，代码中可以使用 #include "nlohmann/json.hpp" 的形式
target_include_directories(cerberusd PRIVATE
    ${THIRDPARTY_DIR}/nlohmann_json/include
)

# --- 6. 链接 'cerberusd' 可执行文件 ---
# 链接我们自己构建的库和 Android 系统提供的日志库
target_link_libraries(cerberusd
    PRIVATE
    sqlitecpp_lib   # 链接 SQLiteCpp (它会自动链接 sqlite3)
    log             # 链接 Android NDK 的 log 库 (用于 __android_log_print)
)

# --- 7. 设置 C++ 标准和编译选项 ---
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# 为 Release 构建开启优化
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O2 -DNDEBUG")
# 消除 GNU 扩展相关的警告 (例如在 process_monitor.cpp 中可能出现)
add_compile_options(-Wno-gnu-empty-struct)
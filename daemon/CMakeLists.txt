# daemon/CMakeLists.txt
cmake_minimum_required(VERSION 3.20)
project(cerberusd CXX C)

# --- 1. 定义第三方库路径 ---
# 假设 nlohmann_json, sqlite3, sqlitecpp 都在 third_party 目录下
set(THIRDPARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# --- 2. 构建 sqlite3 库 (如果使用系统库，可以省略) ---
add_library(sqlite3_lib STATIC ${THIRDPARTY_DIR}/sqlite3/sqlite3.c)
target_include_directories(sqlite3_lib PUBLIC ${THIRDPARTY_DIR}/sqlite)

# --- 3. 构建 SQLiteCpp 库 ---
add_library(sqlitecpp_lib STATIC
    ${THIRDPARTY_DIR}/sqlitecpp/src/Database.cpp
    ${THIRDPARTY_DIR}/sqlitecpp/src/Statement.cpp
    ${THIRDPARTY_DIR}/sqlitecpp/src/Column.cpp
    ${THIRDPARTY_DIR}/sqlitecpp/src/Backup.cpp
    ${THIRDPARTY_DIR}/sqlitecpp/src/Exception.cpp
    ${THIRDPARTY_DIR}/sqlitecpp/src/Savepoint.cpp
    ${THIRDPARTY_DIR}/sqlitecpp/src/Transaction.cpp
)
target_include_directories(sqlitecpp_lib PUBLIC ${THIRDPARTY_DIR}/sqlitecpp/include)
# 链接 SQLiteCpp 到 sqlite3
target_link_libraries(sqlitecpp_lib PUBLIC sqlite3_lib)

# --- 4. 定义最终的可执行文件 ---
add_executable(cerberusd
    cpp/main.cpp
    cpp/uds_server.cpp
    cpp/system_monitor.cpp
    cpp/database_manager.cpp
    cpp/state_manager.cpp
    cpp/action_executor.cpp
    cpp/process_monitor.cpp
)

# --- 5. 添加头文件搜索路径 ---
# nlohmann_json 是 header-only 库，只需包含路径
target_include_directories(cerberusd PRIVATE
    ${THIRDPARTY_DIR}/nlohmann_json/include
)

# --- 6. 链接最终的可执行文件 ---
# 链接我们自己构建的库和安卓系统提供的日志库
target_link_libraries(cerberusd
    PRIVATE
    sqlitecpp_lib
    log # Android log library
)

# --- 7. 设置 C++ 标准和编译选项 ---
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# 为 Release 版本开启优化
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O2 -DNDEBUG")
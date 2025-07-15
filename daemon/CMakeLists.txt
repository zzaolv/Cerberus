# daemon/CMakeLists.txt
cmake_minimum_required(VERSION 3.20)
project(cerberusd CXX C)

# --- 1. 定义第三方库路径 ---
# 假设您的 nlohmann_json, sqlite3, sqlitecpp 都在 third_party 目录下
set(THIRDPARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# --- 2. 构建 sqlite3 库 ---
add_library(sqlite3_lib STATIC ${THIRDPARTY_DIR}/sqlite3/sqlite3.c)
target_include_directories(sqlite3_lib PUBLIC ${THIRDPARTY_DIR}/sqlite3)

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
target_link_libraries(sqlitecpp_lib PUBLIC sqlite3_lib)

# --- 4. 定义最终的可执行文件 ---
# 【核心修改】添加新的源文件
add_executable(cerberusd
    cpp/main.cpp
    cpp/uds_server.cpp
    cpp/system_monitor.cpp
    cpp/database_manager.cpp
    cpp/state_manager.cpp
)

# --- 5. 添加头文件搜索路径 ---
target_include_directories(cerberusd PRIVATE
    ${THIRDPARTY_DIR}/nlohmann_json/include
)

# --- 6. 链接最终的可执行文件 ---
target_link_libraries(cerberusd
    PRIVATE
    sqlitecpp_lib # 链接我们自己构建的 SQLiteCpp
    log           # 链接安卓日志库
)

# --- 7. 设置 C++ 标准 ---
set_target_properties(cerberusd PROPERTIES CXX_STANDARD 17)
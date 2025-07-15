# CMake 最低版本要求
cmake_minimum_required(VERSION 3.20)

# 项目名称，并声明使用 CXX (C++) 和 C 语言
project(cerberusd CXX C)

# --- 1. 设置 C++ 标准和编译选项 ---
# 确保使用 C++17 标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# === 关键修复：添加 POSIX 线程标志 ===
# 这会为编译器和链接器添加必要的 -pthread 标志
set(CMAKE_THREAD_PREFER_PTHREAD ON)
find_package(Threads REQUIRED)

# --- 2. 定义第三方库路径 ---
set(THIRDPARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# --- 3. 构建我们自己提供的 sqlite3 库 ---
add_library(sqlite3_lib STATIC ${THIRDPARTY_DIR}/sqlite3/sqlite3.c)
target_include_directories(sqlite3_lib PUBLIC ${THIRDPARTY_DIR}/sqlite3)

# --- 4. 构建 SQLiteCpp 库 ---
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
# 将 sqlite3 链接到 SQLiteCpp
target_link_libraries(sqlitecpp_lib PUBLIC sqlite3_lib)

# --- 5. 定义最终的可执行文件 ---
add_executable(cerberusd
    cpp/main.cpp
    cpp/uds_server.cpp
)

# --- 6. 添加头文件搜索路径 ---
target_include_directories(cerberusd PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/cpp
    ${THIRDPARTY_DIR}/nlohmann_json/include
)

# --- 7. 链接最终的可执行文件 ---
target_link_libraries(cerberusd
    PRIVATE
    # 链接我们自己构建的 SQLiteCpp
    sqlitecpp_lib
    # 链接安卓日志库
    log
    # === 关键修复：链接线程库 ===
    Threads::Threads
)
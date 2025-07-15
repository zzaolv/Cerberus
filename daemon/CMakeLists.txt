cmake_minimum_required(VERSION 3.20)
project(cerberusd CXX) # 项目名改为 cerberusd 更贴切

# --- 1. 定义第三方库路径 ---
set(THIRDPARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# --- 2. 构建我们自己提供的 sqlite3 库 ---
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
add_executable(cerberusd
    cpp/main.cpp
    cpp/uds_server.cpp
    # 我们暂时不实现复杂的逻辑，先注释掉这些
    # cpp/db_manager.cpp
    # cpp/state_manager.cpp
    # cpp/policy_engine.cpp
)

# --- 5. 添加头文件搜索路径 ---
target_include_directories(cerberusd PRIVATE
    ${THIRDPARTY_DIR}/nlohmann_json/include
    ${THIRDPARTY_DIR}/sqlitecpp/include
)

# --- 6. 链接最终的可执行文件 ---
target_link_libraries(cerberusd
    PRIVATE
    sqlitecpp_lib # 即使暂时不用，也先链接好
    log          # 链接安卓日志库
)

# --- 7. 设置 C++ 标准 ---
set_target_properties(cerberusd PROPERTIES CXX_STANDARD 17)
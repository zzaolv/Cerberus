// daemon/cpp/rekernel_client.cpp
#include "rekernel_client.h"
#include <android/log.h>
#include <sys/socket.h>
#include <linux/netlink.h>
#include <unistd.h>
#include <filesystem>
#include <charconv>

#define LOG_TAG "cerberusd_rekernel"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

constexpr int REKERNEL_USER_PORT = 100;

ReKernelClient::ReKernelClient() = default;

ReKernelClient::~ReKernelClient() {
    stop();
}

void ReKernelClient::start() {
    if (is_running_) {
        return;
    }
    is_running_ = true;
    listener_thread_ = std::thread(&ReKernelClient::listener_thread_func, this);
}

void ReKernelClient::stop() {
    if (!is_running_.exchange(false)) {
        return;
    }
    // 通过关闭socket来唤醒阻塞的recvfrom
    if (netlink_fd_ != -1) {
        shutdown(netlink_fd_, SHUT_RDWR);
        close(netlink_fd_);
        netlink_fd_ = -1;
    }
    if (listener_thread_.joinable()) {
        listener_thread_.join();
    }
}

bool ReKernelClient::is_active() const {
    return is_active_;
}

void ReKernelClient::set_signal_handler(std::function<void(const ReKernelSignalEvent&)> handler) {
    on_signal_received_ = std::move(handler);
}

void ReKernelClient::set_binder_handler(std::function<void(const ReKernelBinderEvent&)> handler) {
    on_binder_received_ = std::move(handler);
}

std::optional<int> ReKernelClient::detect_netlink_unit() {
    const std::string rekernel_proc_dir = "/proc/rekernel";
    if (!fs::exists(rekernel_proc_dir) || !fs::is_directory(rekernel_proc_dir)) {
        LOGI("Re-Kernel proc directory not found. Module is not loaded.");
        return std::nullopt;
    }

    for (const auto& entry : fs::directory_iterator(rekernel_proc_dir)) {
        if (entry.is_regular_file()) {
            std::string filename = entry.path().filename().string();
            int unit = -1;
            auto [ptr, ec] = std::from_chars(filename.data(), filename.data() + filename.size(), unit);
            if (ec == std::errc()) {
                LOGI("Detected Re-Kernel Netlink Unit: %d", unit);
                return unit;
            }
        }
    }

    LOGW("Re-Kernel proc directory exists, but no valid unit file found.");
    return std::nullopt;
}

void ReKernelClient::listener_thread_func() {
    auto unit_opt = detect_netlink_unit();
    if (!unit_opt) {
        is_active_ = false;
        return;
    }
    netlink_unit_ = *unit_opt;

    netlink_fd_ = socket(AF_NETLINK, SOCK_RAW, netlink_unit_);
    if (netlink_fd_ < 0) {
        LOGE("Failed to create Netlink socket for unit %d: %s", netlink_unit_, strerror(errno));
        is_active_ = false;
        return;
    }

    struct sockaddr_nl src_addr;
    memset(&src_addr, 0, sizeof(src_addr));
    src_addr.nl_family = AF_NETLINK;
    src_addr.nl_pid = REKERNEL_USER_PORT;
    src_addr.nl_groups = 0;

    if (bind(netlink_fd_, (struct sockaddr*)&src_addr, sizeof(src_addr)) != 0) {
        LOGE("Failed to bind Netlink socket: %s", strerror(errno));
        close(netlink_fd_);
        netlink_fd_ = -1;
        is_active_ = false;
        return;
    }

    LOGI("Re-Kernel client successfully connected to Netlink Unit %d.", netlink_unit_);
    is_active_ = true;

    while (is_running_) {
        char buffer[1024];
        struct iovec iov = { buffer, sizeof(buffer) };
        struct sockaddr_nl sa;
        struct msghdr msg = { &sa, sizeof(sa), &iov, 1, nullptr, 0, 0 };

        ssize_t len = recvmsg(netlink_fd_, &msg, 0);
        if (len <= 0) {
            if (is_running_) {
                LOGW("recvmsg failed or connection closed: %s", strerror(errno));
                // 在循环中短暂休眠以防CPU空转
                std::this_thread::sleep_for(std::chrono::seconds(1));
            }
            continue;
        }

        struct nlmsghdr *nlh = (struct nlmsghdr *)buffer;
        while (NLMSG_OK(nlh, len)) {
            std::string payload((char*)NLMSG_DATA(nlh), NLMSG_PAYLOAD(nlh, 0));
            parse_and_dispatch(payload);
            nlh = NLMSG_NEXT(nlh, len);
        }
    }

    LOGI("Re-Kernel client listener thread stopped.");
    is_active_ = false;
}

std::map<std::string, std::string> ReKernelClient::parse_params(const std::string& message_body) {
    std::map<std::string, std::string> map;
    std::stringstream ss(message_body);
    std::string segment;
    while(std::getline(ss, segment, ',')) {
        size_t equals_pos = segment.find('=');
        if (equals_pos != std::string::npos) {
            map[segment.substr(0, equals_pos)] = segment.substr(equals_pos + 1);
        }
    }
    return map;
}

void ReKernelClient::parse_and_dispatch(const std::string& message) {
    if (message.empty()) return;

    // 移除尾部的 ';'
    std::string clean_message = message;
    if (clean_message.back() == ';') {
        clean_message.pop_back();
    }
    
    auto params = parse_params(clean_message);

    try {
        std::string type = params.at("type");
        if (type == "Signal" && on_signal_received_) {
            ReKernelSignalEvent event{};
            event.signal = std::stoi(params.at("signal"));
            event.killer_pid = std::stoi(params.at("killer_pid"));
            event.killer_uid = std::stoi(params.at("killer"));
            event.dest_pid = std::stoi(params.at("dst_pid"));
            event.dest_uid = std::stoi(params.at("dst"));
            on_signal_received_(event);
        } else if (type == "Binder" && on_binder_received_) {
            ReKernelBinderEvent event{};
            event.binder_type = params.at("bindertype");
            event.is_oneway = (std::stoi(params.at("oneway")) == 1);
            event.from_pid = std::stoi(params.at("from_pid"));
            event.from_uid = std::stoi(params.at("from"));
            event.target_pid = std::stoi(params.at("target_pid"));
            event.target_uid = std::stoi(params.at("target"));
            event.rpc_name = params.value("rpc_name", "");
            event.code = std::stoi(params.value("code", "-1"));
            on_binder_received_(event);
        }
    } catch (const std::exception& e) {
        LOGW("Failed to parse Re-Kernel message '%s': %s", message.c_str(), e.what());
    }
}
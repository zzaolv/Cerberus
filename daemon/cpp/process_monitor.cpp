// daemon/cpp/process_monitor.cpp
#include "process_monitor.h"
#include <android/log.h>
#include <sys/socket.h>
#include <linux/netlink.h>
#include <linux/connector.h>
#include <linux/cn_proc.h>
#include <unistd.h>
#include <cstring>
#include <vector>

#define LOG_TAG "cerberusd_procmon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

ProcessMonitor::ProcessMonitor() {}

ProcessMonitor::~ProcessMonitor() {
    stop();
}

void ProcessMonitor::start(ProcessEventCallback callback) {
    if (is_running_) {
        LOGE("Process monitor is already running.");
        return;
    }

    callback_ = std::move(callback);

    netlink_socket_ = socket(PF_NETLINK, SOCK_DGRAM | SOCK_CLOEXEC, NETLINK_CONNECTOR);
    if (netlink_socket_ < 0) {
        LOGE("Failed to create netlink socket: %s", strerror(errno));
        return;
    }

    struct sockaddr_nl sa_nl;
    memset(&sa_nl, 0, sizeof(sa_nl));
    sa_nl.nl_family = AF_NETLINK;
    sa_nl.nl_groups = CN_IDX_PROC;
    sa_nl.nl_pid = getpid();

    if (bind(netlink_socket_, (struct sockaddr*)&sa_nl, sizeof(sa_nl)) < 0) {
        LOGE("Failed to bind netlink socket: %s", strerror(errno));
        close(netlink_socket_);
        netlink_socket_ = -1;
        return;
    }

    struct __attribute__((aligned(NLMSG_ALIGNTO))) {
        struct nlmsghdr nl_hdr;
        struct __attribute__((aligned(NLMSG_ALIGNTO))) {
            struct cn_msg cn_msg;
            enum proc_cn_mcast_op op;
        } cn_req;
    } req;

    memset(&req, 0, sizeof(req));
    req.nl_hdr.nlmsg_len = sizeof(req);
    req.nl_hdr.nlmsg_pid = getpid();
    req.nl_hdr.nlmsg_type = NLMSG_DONE;

    req.cn_req.cn_msg.id.idx = CN_IDX_PROC;
    req.cn_req.cn_msg.id.val = CN_VAL_PROC;
    req.cn_req.cn_msg.len = sizeof(enum proc_cn_mcast_op);
    req.cn_req.op = PROC_CN_MCAST_LISTEN;

    if (send(netlink_socket_, &req, sizeof(req), 0) < 0) {
        LOGE("Failed to send listen request to kernel: %s", strerror(errno));
        close(netlink_socket_);
        netlink_socket_ = -1;
        return;
    }

    LOGI("Netlink process monitor started successfully.");
    is_running_ = true;
    monitor_thread_ = std::thread(&ProcessMonitor::monitor_loop, this);
}

void ProcessMonitor::stop() {
    if (!is_running_) return;

    is_running_ = false;
    if (netlink_socket_ >= 0) {
        // 使用 shutdown 来唤醒可能阻塞在 recvmsg 的线程
        shutdown(netlink_socket_, SHUT_RDWR);
        close(netlink_socket_);
        netlink_socket_ = -1;
    }
    if (monitor_thread_.joinable()) {
        monitor_thread_.join();
    }
    LOGI("Process monitor stopped.");
}

void ProcessMonitor::monitor_loop() {
    std::vector<char> buf(8192);
    while (is_running_) {
        struct iovec iov = { buf.data(), buf.size() };
        struct sockaddr_nl sa_nl;
        struct msghdr msg_hdr = { &sa_nl, sizeof(sa_nl), &iov, 1, nullptr, 0, 0 };

        ssize_t len = recvmsg(netlink_socket_, &msg_hdr, 0);
        if (len <= 0) {
            if (errno == EINTR) continue;
            // 如果 is_running_ 已经是 false，说明是正常关闭，不用报错
            if(is_running_.load()) {
                LOGE("Error receiving from netlink socket: %s. Stopping monitor.", strerror(errno));
            }
            break;
        }

        for (struct nlmsghdr* nl_hdr = (struct nlmsghdr*)buf.data(); NLMSG_OK(nl_hdr, len); nl_hdr = NLMSG_NEXT(nl_hdr, len)) {
            if (nl_hdr->nlmsg_type == NLMSG_DONE) {
                struct cn_msg* cn_msg = (struct cn_msg*)NLMSG_DATA(nl_hdr);
                struct proc_event* ev = (struct proc_event*)cn_msg->data;

                // 【核心修复】直接使用全局的枚举常量，而不是作为 proc_event 的成员
                switch (ev->what) {
                    case PROC_EVENT_FORK:
                        if (callback_) {
                            callback_(ProcessEventType::FORK, ev->event_data.fork.child_pid, ev->event_data.fork.parent_pid);
                        }
                        break;
                    case PROC_EVENT_EXEC:
                         if (callback_) {
                            // 【核心修复】exec 事件没有ppid，我们传递pid作为占位符
                            callback_(ProcessEventType::EXEC, ev->event_data.exec.process_pid, ev->event_data.exec.process_pid);
                        }
                        break;
                    case PROC_EVENT_EXIT:
                         if (callback_) {
                            // 【核心修复】exit 事件没有ppid，我们传递pid作为占位符
                            callback_(ProcessEventType::EXIT, ev->event_data.exit.process_pid, ev->event_data.exit.process_pid);
                        }
                        break;
                    default:
                        // 其他我们不关心的事件
                        break;
                }
            }
        }
    }
}
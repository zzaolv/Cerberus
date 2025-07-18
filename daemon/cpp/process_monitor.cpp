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

    // 发送监听指令给内核
    char msg_buf[NLMSG_LENGTH(sizeof(struct cn_msg) + sizeof(enum proc_cn_mcast_op))];
    struct nlmsghdr* nl_hdr = (struct nlmsghdr*)msg_buf;
    struct cn_msg* cn_msg = (struct cn_msg*)NLMSG_DATA(nl_hdr);
    // 使用指针直接在 cn_msg 后面写入数据，避免结构体嵌套
    enum proc_cn_mcast_op* op = (enum proc_cn_mcast_op*)cn_msg->data;

    memset(msg_buf, 0, sizeof(msg_buf));
    nl_hdr->nlmsg_len = sizeof(msg_buf);
    nl_hdr->nlmsg_pid = getpid();
    nl_hdr->nlmsg_type = NLMSG_DONE;

    cn_msg->id.idx = CN_IDX_PROC;
    cn_msg->id.val = CN_VAL_PROC;
    cn_msg->len = sizeof(enum proc_cn_mcast_op);
    *op = PROC_CN_MCAST_LISTEN;

    if (send(netlink_socket_, nl_hdr, nl_hdr->nlmsg_len, 0) < 0) {
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
    if (!is_running_.exchange(false)) return;

    if (netlink_socket_ >= 0) {
        // 关闭socket会导致recvmsg立即返回，从而退出循环
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
    std::vector<char> buf(8192); // 8KB buffer
    while (is_running_) {
        ssize_t len = recv(netlink_socket_, buf.data(), buf.size(), 0);
        if (len <= 0) {
            if (errno == EINTR) continue; // 被信号中断，继续
            if(is_running_.load()) { // 如果不是主动停止，则记录错误
                LOGE("Error receiving from netlink socket: %s. Stopping monitor.", strerror(errno));
            }
            break; // 退出循环
        }

        for (struct nlmsghdr* nl_hdr = (struct nlmsghdr*)buf.data(); NLMSG_OK(nl_hdr, len); nl_hdr = NLMSG_NEXT(nl_hdr, len)) {
            if (nl_hdr->nlmsg_type == NLMSG_DONE) {
                struct cn_msg* cn_msg = (struct cn_msg*)NLMSG_DATA(nl_hdr);
                if(cn_msg->id.idx == CN_IDX_PROC && cn_msg->id.val == CN_VAL_PROC){
                    struct proc_event* ev = (struct proc_event*)cn_msg->data;
                    if (callback_) {
                        switch (ev->what) {
                            case proc_event::PROC_EVENT_FORK:
                                callback_(ProcessEventType::FORK, ev->event_data.fork.child_pid, ev->event_data.fork.parent_pid);
                                break;
                            case proc_event::PROC_EVENT_EXEC:
                                callback_(ProcessEventType::EXEC, ev->event_data.exec.process_pid, ev->event_data.exec.process_pid);
                                break;
                            case proc_event::PROC_EVENT_EXIT:
                                callback_(ProcessEventType::EXIT, ev->event_data.exit.process_pid, ev->event_data.exit.process_pid);
                                break;
                            default:
                                break;
                        }
                    }
                }
            }
        }
    }
}
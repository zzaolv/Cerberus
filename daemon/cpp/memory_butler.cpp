// daemon/cpp/memory_butler.cpp
#include "memory_butler.h"
#include <android/log.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <fstream>
#include <sstream>

#define LOG_TAG "cerberusd_mem_butler"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

MemoryButler::MemoryButler() {
    check_support();
}

void MemoryButler::check_support() {
    // 尝试调用 pidfd_open, 如果返回 ENOSYS, 则内核不支持
    // 我们用一个不可能存在的pid(0)来测试，它应该失败，但不应该是ENOSYS
    int ret = syscall(__NR_pidfd_open, 0, 0);
    if (ret < 0 && errno == ENOSYS) {
        LOGW("Kernel does not support pidfd_open (errno=ENOSYS). MemoryButler is disabled.");
        supported_ = false;
    } else {
        LOGI("Kernel supports pidfd_open. MemoryButler is enabled.");
        supported_ = true;
    }
}

bool MemoryButler::is_supported() const {
    return supported_;
}

long long MemoryButler::compress_memory(int pid, CompressionLevel level) {
    if (!supported_) {
        return 0;
    }

    {
        std::lock_guard<std::mutex> lock(throttle_mutex_);
        auto it = last_compression_times_.find(pid);
        time_t now = time(nullptr);
        if (it != last_compression_times_.end() && (now - it->second) < 30) { // 30秒节流
             LOGD("Throttling memory compression for pid %d.", pid);
             return 0;
        }
        last_compression_times_[pid] = now;
    }


    int pidfd = syscall(__NR_pidfd_open, pid, 0);
    if (pidfd < 0) {
        LOGW("pidfd_open for pid %d failed: %s", pid, strerror(errno));
        return 0;
    }

    auto maps = get_compressible_maps(pid);
    if (maps.empty()) {
        close(pidfd);
        return 0;
    }

    int advice = (level == CompressionLevel::LIGHT) ? MADV_COLD : MADV_PAGEOUT;
    long long total_compressed_bytes = 0;

    for (const auto& range : maps) {
        struct iovec vec = {
            .iov_base = (void*)range.start,
            .iov_len = range.end - range.start
        };
        
        ssize_t ret = syscall(__NR_process_madvise, pidfd, &vec, 1, advice, 0);
        
        if (ret < 0) {
            // EINVAL 可能是因为地址范围已经失效，可以容忍
            if (errno != EINVAL) {
                LOGW("process_madvise on pid %d failed for range [0x%lx-0x%lx]: %s", 
                     pid, range.start, range.end, strerror(errno));
            }
        } else {
            total_compressed_bytes += ret;
        }
    }

    close(pidfd);

    if (total_compressed_bytes > 0) {
        LOGI("Compressed %lld KB for pid %d with level %s.", 
             total_compressed_bytes / 1024, pid, 
             (level == CompressionLevel::LIGHT ? "LIGHT" : "AGGRESSIVE"));
    }

    return total_compressed_bytes;
}

std::vector<MemoryButler::AddressRange> MemoryButler::get_compressible_maps(int pid) {
    std::vector<AddressRange> ranges;
    std::string maps_path = "/proc/" + std::to_string(pid) + "/maps";
    std::ifstream maps_file(maps_path);
    if (!maps_file.is_open()) {
        return ranges;
    }

    std::string line;
    while (std::getline(maps_file, line)) {
        unsigned long start, end;
        char perms[5];
        std::string pathname;

        // 解析格式: "start-end perms offset dev inode pathname"
        std::sscanf(line.c_str(), "%lx-%lx %4s", &start, &end, perms);

        // 我们只关心私有的(p)、可读写的(rw)匿名内存段
        // 这些通常是堆内存和虚拟机内部内存，是最有价值的压缩目标
        if (perms[0] == 'r' && perms[1] == 'w' && perms[3] == 'p') {
            size_t path_start = line.find('/');
            if (path_start == std::string::npos || line.substr(path_start).rfind("[anon:", 0) == 0) {
                ranges.push_back({start, end});
            }
        }
    }
    return ranges;
}
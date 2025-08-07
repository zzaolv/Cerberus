// daemon/cpp/memory_butler.cpp
#include "memory_butler.h"
#include <android/log.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <fstream>
#include <sstream>

#define LOG_TAG "cerberusd_mem_butler_v2" // 版本更新
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

MemoryButler::MemoryButler() {
    check_support();
}

void MemoryButler::check_support() {
    // 检查 process_madvise 支持情况
    int ret = syscall(__NR_pidfd_open, 0, 0);
    if (ret < 0 && errno == ENOSYS) {
        LOGW("Kernel does not support pidfd_open (errno=ENOSYS). MemoryButler is disabled.");
        supported_ = false;
    } else {
        LOGI("Kernel supports pidfd_open and process_madvise. MemoryButler is enabled.");
        supported_ = true;
    }
}

bool MemoryButler::is_supported() const {
    return supported_;
}

// [修改] 核心函数重构，使用 MADV_COLD
long long MemoryButler::advise_cold_memory(int pid) {
    if (!supported_) {
        return 0;
    }

    // 节流阀逻辑保持不变
    {
        std::lock_guard<std::mutex> lock(throttle_mutex_);
        auto it = last_trim_times_.find(pid);
        time_t now = time(nullptr);
        if (it != last_trim_times_.end() && (now - it->second) < 60) { // 节流时间延长至60秒
             LOGD("Throttling MADV_COLD for pid %d.", pid);
             return 0;
        }
        last_trim_times_[pid] = now;
    }

    int pidfd = syscall(__NR_pidfd_open, pid, 0);
    if (pidfd < 0) {
        // pidfd_open 失败是正常情况（进程可能已死），无需高频打印
        // LOGW("pidfd_open for pid %d failed: %s", pid, strerror(errno));
        return 0;
    }

    auto maps = get_compressible_maps(pid);
    if (maps.empty()) {
        close(pidfd);
        return 0;
    }

    // [核心修改] 使用 MADV_COLD
    int advice = MADV_COLD;
    long long total_advised_bytes = 0;

    for (const auto& range : maps) {
        struct iovec vec = {
            .iov_base = (void*)range.start,
            .iov_len = range.end - range.start
        };
        
        ssize_t ret = syscall(__NR_process_madvise, pidfd, &vec, 1, advice, 0);
        
        if (ret < 0) {
            if (errno != EINVAL && errno != EPERM) { // 容忍无效地址和权限问题
                LOGW("process_madvise(MADV_COLD) on pid %d failed for range [0x%lx-0x%lx]: %s", 
                     pid, range.start, range.end, strerror(errno));
            }
        } else {
            total_advised_bytes += ret;
        }
    }

    close(pidfd);

    if (total_advised_bytes > 0) {
        LOGI("Applied MADV_COLD to %lld KB for pid %d.", 
             total_advised_bytes / 1024, pid);
    }

    return total_advised_bytes;
}

// [新增] 实现系统缓存清理函数
bool MemoryButler::drop_system_caches(DropCacheLevel level) {
    std::string level_str = std::to_string(static_cast<int>(level));
    LOGI("Attempting to drop system caches with level %s...", level_str.c_str());
    if (write_to_file("/proc/sys/vm/drop_caches", level_str)) {
        LOGI("Successfully requested to drop system caches.");
        return true;
    } else {
        LOGE("Failed to write to /proc/sys/vm/drop_caches. Check permissions.");
        return false;
    }
}

// [新增] 内部实现的 write_to_file 工具函数
bool MemoryButler::write_to_file(const std::string& path, const std::string& value) {
    std::ofstream ofs(path);
    if (!ofs.is_open()) {
        LOGE("Failed to open file '%s' for writing: %s", path.c_str(), strerror(errno));
        return false;
    }
    ofs << value;
    if (ofs.fail()) {
        LOGE("Failed to write '%s' to '%s': %s", value.c_str(), path.c_str(), strerror(errno));
        return false;
    }
    return true;
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

        std::sscanf(line.c_str(), "%lx-%lx %4s", &start, &end, perms);

        // 我们只关心私有的(p)、可读写的(rw)匿名内存段
        if (perms[0] == 'r' && perms[1] == 'w' && perms[3] == 'p') {
            size_t path_start = line.find('/');
            if (path_start == std::string::npos || line.substr(path_start).rfind("[anon:", 0) == 0) {
                ranges.push_back({start, end});
            }
        }
    }
    return ranges;
}
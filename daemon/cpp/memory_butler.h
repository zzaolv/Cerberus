// daemon/cpp/memory_butler.h
#ifndef CERBERUS_MEMORY_BUTLER_H
#define CERBERUS_MEMORY_BUTLER_H

#include <string>
#include <atomic>
#include <vector>
#include <sys/uio.h>
#include <map>
#include <mutex>
#include <ctime>

// 为可能不存在于旧头文件的系统调用定义编号
#ifndef __NR_pidfd_open
#define __NR_pidfd_open 434
#endif
#ifndef __NR_process_madvise
#define __NR_process_madvise 440
#endif

// madvise 的建议值
#ifndef MADV_COLD
#define MADV_COLD 18
#endif
#ifndef MADV_PAGEOUT
#define MADV_PAGEOUT 19
#endif

class MemoryButler {
public:
    enum class CompressionLevel {
        LIGHT,      // 使用 MADV_COLD，温和
        AGGRESSIVE  // 使用 MADV_PAGEOUT，激进
    };

    MemoryButler();

    // 检查内核是否支持此功能
    bool is_supported() const;

    // 对指定PID执行内存压缩
    // 返回成功压缩的字节数
    long long compress_memory(int pid, CompressionLevel level);

private:
    struct AddressRange {
        unsigned long start;
        unsigned long end;
    };

    void check_support();
    std::vector<AddressRange> get_compressible_maps(int pid);

    std::atomic<bool> supported_{false};
    
    // 节流阀，防止对同一进程过于频繁地操作
    std::mutex throttle_mutex_;
    std::map<int, time_t> last_compression_times_;
};

#endif // CERBERUS_MEMORY_BUTLER_H
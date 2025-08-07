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
#define MADV_COLD 18 // [修改] 明确我们的主要策略
#endif

class MemoryButler {
public:
    // [新增] 定义系统缓存清理级别
    enum class DropCacheLevel {
        PAGE_CACHE_ONLY = 1, // 清理页面缓存
        DENTRIES_AND_INODES = 2, // 清理目录项和索引节点
        ALL = 3 // 清理所有
    };


    MemoryButler();

    // 检查内核是否支持此功能
    bool is_supported() const;

    // [修改] 函数重命名，功能变为应用 MADV_COLD
    // 对指定PID的私有匿名内存应用 MADV_COLD
    // 返回成功提示的字节数
    long long advise_cold_memory(int pid);

    // [新增] 清理系统级缓存
    bool drop_system_caches(DropCacheLevel level);

private:
    struct AddressRange {
        unsigned long start;
        unsigned long end;
    };

    void check_support();
    std::vector<AddressRange> get_compressible_maps(int pid);
    // [新增] 将文件写入功能移至内部，方便调用
    bool write_to_file(const std::string& path, const std::string& value);

    std::atomic<bool> supported_{false};
    
    // [修改] 节流阀重命名以反映新功能
    std::mutex throttle_mutex_;
    std::map<int, time_t> last_trim_times_;
};

#endif // CERBERUS_MEMORY_BUTLER_H
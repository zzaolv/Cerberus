#!/system/bin/sh
# 等待系统启动完成
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 1
done

# 模块目录由 Magisk 注入
MODDIR=${0%/*}

# 守护进程的可执行文件路径
DAEMON_EXEC="$MODDIR/cerberusd"
LOG_TAG="cerberusd-starter"

# 确保可执行文件有执行权限
chmod 755 "$DAEMON_EXEC"

# 记录一条启动日志到 logcat
/system/bin/logcat -t1 -s "$LOG_TAG" "Starting cerberusd from service.sh..."

# 直接在后台启动守护进程。
# 它内部的 __android_log_print 会将日志写入系统 log buffer。
# 我们不再需要 logwrapper 或管道。
"$DAEMON_EXEC" &

/system/bin/logcat -t1 -s "$LOG_TAG" "cerberusd started in background."
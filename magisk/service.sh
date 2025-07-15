#!/system/bin/sh
# 等待系统启动完成
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 1
done

MODDIR=${0%/*}
DAEMON_EXEC="$MODDIR/cerberusd"
LOG_TAG="cerberusd"

# 确保可执行文件有执行权限
chmod 755 "$DAEMON_EXEC"

# 直接将守护进程的 stdout 和 stderr 重定向到 logcat 命令
# 这比 logwrapper 在某些系统上更可靠
"$DAEMON_EXEC" 2>&1 | /system/bin/logcat -t "$LOG_TAG" &
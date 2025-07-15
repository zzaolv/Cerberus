#!/system/bin/sh
# 等待系统启动完成
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 1
done

# 模块的安装路径
MODDIR=${0%/*}

# 守护进程的可执行文件路径
DAEMON_EXEC="$MODDIR/cerberusd"

# 日志文件
LOGFILE="$MODDIR/daemon.log"

# 确保可执行文件有执行权限
chmod 755 "$DAEMON_EXEC"

# 使用 logwrapper 启动守护进程，并将日志输出到文件和logcat
# -p 10: 日志优先级 (INFO)
# -t cerberusd: 日志的TAG
logwrapper "$DAEMON_EXEC" > "$LOGFILE" 2>&1 &

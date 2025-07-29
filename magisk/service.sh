#!/system/bin/sh
# D:/project/Cerberus/Cerberus_Module/service.sh

MODDIR=${0%/*}
LOG_FILE="/data/adb/cerberus/daemon_service.log"
PROP_FILE="$MODDIR/module.prop"

# 将此脚本的所有输出都重定向到日志文件
exec 1>>$LOG_FILE 2>&1

echo "----------------------------------------"
echo "[$(date)] service.sh started."

# 等待系统启动完成
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 5
done
echo "[$(date)] Boot completed."

# 延迟一点，确保系统服务都已就绪
sleep 10

# --- 动态更新模块描述 ---
update_description() {
    echo "[$(date)] Updating module description..."
    DAEMON_PID=$(pgrep -f "$MODDIR/system/bin/cerberusd")

    if [ -n "$DAEMON_PID" ]; then
        echo "[$(date)] Daemon is running with PID: $DAEMON_PID"
        DESCRIPTION="😊 Guardian Running, PID: [$DAEMON_PID] A system-level guardian for performance and battery."
    else
        echo "[$(date)] Daemon failed to start. PID not found."
        DESCRIPTION="😅 Guardian Failed to Start. Check Magisk logs and /data/adb/cerberus/daemon_service.log for errors."
    fi
    
    # 检查写入前的文件内容
    echo "[$(date)] Before update: $(cat $PROP_FILE | grep 'description=')"
    
    sed -i "s|description=.*|$DESCRIPTION|" "$PROP_FILE"
    
    # 检查写入后的文件内容
    echo "[$(date)] After update:  $(cat $PROP_FILE | grep 'description=')"
    echo "[$(date)] Description update finished."
}

# --- 启动守护进程 ---
echo "[$(date)] Starting cerberusd daemon..."
nohup $MODDIR/system/bin/cerberusd &

# 启动后等待几秒
sleep 5

# 更新描述
update_description

echo "[$(date)] service.sh finished."
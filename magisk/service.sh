#!/system/bin/sh

MODDIR=${0%/*}
DATA_DIR="/data/adb/cerberus"
LOG_FILE="$DATA_DIR/boot.log"
PROP_FILE="$MODDIR/module.prop"

exec >> "$LOG_FILE" 2>&1

echo "----------------------------------------"
echo "[$(date)] service.sh script invoked..."

DAEMON_PATH="$MODDIR/system/bin/cerberusd"

is_daemon_running() {
  if pgrep -f "$DAEMON_PATH" > /dev/null; then
    return 0
  else
    return 1
  fi
}

update_description() {
    echo "[$(date)] update_description function called."
    
    DAEMON_PID=$(pgrep -f "$DAEMON_PATH")
    
    echo "[$(date)] Check Result: Daemon PID is '$DAEMON_PID'."
    echo "[$(date)] Before update: $(grep 'description=' $PROP_FILE)"

    if [ -n "$DAEMON_PID" ]; then
        DESCRIPTION="description=✅ 运行成功 [v1.0.1_alpha|PID: $DAEMON_PID]. "
    else
        DESCRIPTION="description=❌ 运行失败. 检查日志： $DATA_DIR"
    fi

    # 使用-i参数，确保兼容性
    sed -i "s|description=.*|$DESCRIPTION|" "$PROP_FILE"

    echo "[$(date)] After update:  $(grep 'description=' $PROP_FILE)"
    echo "[$(date)] Description update finished. UI change might need a refresh or reboot to show."
}

# 1. 等待开机完成
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 5
done
echo "[$(date)] Boot completed. Preparing to start Cerberus daemon..."

# 2. 启动守护进程
if ! is_daemon_running; then
    if [ -f "$DAEMON_PATH" ]; then
        echo "[$(date)] Starting cerberusd daemon..."
        nohup "$DAEMON_PATH" >/dev/null 2>&1 &
    else
        echo "[$(date)] ERROR: Daemon executable not found at $DAEMON_PATH!"
        sed -i "s|description=.*|description=❌ ERROR: Daemon file missing!|" "$PROP_FILE"
        exit 1
    fi
else
    echo "[$(date)] Daemon is already running. Skipping start."
fi


count=0
limit=30

echo "[$(date)] Now waiting for system UI to be ready (screen unlock)..."

while [ $count -lt $limit ]; do
    locked=$(dumpsys window policy | grep 'mInputRestricted' | cut -d= -f2)

    if [ "$locked" = "false" ]; then
        echo "[$(date)] Screen is unlocked. System is ready."
        sleep 5 
        update_description
        break
    fi

    if [ $count -eq $((limit - 1)) ]; then
        echo "[$(date)] Timeout waiting for unlock, attempting description update anyway."
        update_description
        break
    fi

    count=$((count + 1))
    sleep 2
done

echo "[$(date)] service.sh finished."
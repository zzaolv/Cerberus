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

if is_daemon_running; then
  echo "[$(date)] Daemon is already running. Skipping new instance."
  exit 0
fi

while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 5
done
echo "[$(date)] Boot completed. Preparing to start Cerberus daemon..."
sleep 10

if is_daemon_running; then
  echo "[$(date)] Daemon was started by another event while waiting. Exiting."
  exit 0
fi

if [ -f "$DAEMON_PATH" ]; then
    echo "[$(date)] Starting cerberusd daemon..."
    nohup "$DAEMON_PATH" &
else
    echo "[$(date)] ERROR: Daemon executable not found at $DAEMON_PATH!"
    sed -i "s|description=.*|description=❌ ERROR: Daemon file missing!|" "$PROP_FILE"
    exit 1
fi

sleep 5


echo "[$(date)] Updating module description..."

DAEMON_PID=$(pgrep -f "$DAEMON_PATH")

echo "[$(date)] Check Result: Daemon PID is '$DAEMON_PID'."
echo "[$(date)] Before update: $(grep 'description=' $PROP_FILE)"

if [ -n "$DAEMON_PID" ]; then
    DESCRIPTION="description=✅ 运行成功 [1001|PID: $DAEMON_PID]. "
else
    DESCRIPTION="description=❌ 运行失败. 检查日志： $DATA_DIR"
fi

sed -i "s|description=.*|$DESCRIPTION|" "$PROP_FILE"

echo "[$(date)] After update:  $(grep 'description=' $PROP_FILE)"
echo "[$(date)] Description update finished. Note: UI change might appear after a reboot."

echo "[$(date)] service.sh finished successfully."
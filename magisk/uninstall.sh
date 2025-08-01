#!/system/bin/sh

# --- 清理守护进程 ---
DAEMON_PATH="/data/adb/modules/Cerberus/system/bin/cerberusd"
DAEMON_PID=$(pgrep -f "$DAEMON_PATH")
if [ -n "$DAEMON_PID" ]; then
  kill -9 "$DAEMON_PID"
fi

# --- 清理应用 ---
(
  while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
  done
  pm uninstall "com.crfzit.crfzit"
) &

# --- 清理数据目录 ---
rm -rf "/data/adb/cerberus"

# --- 恢复系统属性 ---
resetprop -p --delete persist.sys.gz.enable
resetprop -p --delete persist.sys.millet.handshake
resetprop -p --delete persist.sys.powmillet.enable
resetprop -p --delete persist.sys.brightmillet.enable
resetprop -p --delete persist.vendor.enable.hans
#!/system/bin/sh
#
# Project Cerberus - Service Start Script
#

# 等待系统启动完成，直到解锁屏幕
wait_until_boot_complete() {
  while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
  done
  # 等待数据分区解密
  while [ ! -d "/sdcard/Android" ]; do
    sleep 1
  done
}

# 启动守护进程
start_daemon() {
  # 确保路径存在
  MODDIR=${0%/*}
  LOG_DIR="/data/adb/cerberus"
  LOG_FILE="$LOG_DIR/daemon.log"
  PID_FILE="$LOG_DIR/cerberusd.pid"
  
  mkdir -p $LOG_DIR
  
  # 启动守护进程，并将日志重定向到文件
  # 使用 nohup 和 & 在后台运行，即使脚本退出也保持运行
  nohup $MODDIR/system/bin/cerberusd > $LOG_FILE 2>&1 &
  
  # 将PID写入文件，方便状态检查
  echo $! > $PID_FILE
}

# 动态更新模块描述
update_description() {
  MODDIR=${0%/*}
  PROP_FILE="$MODDIR/module.prop"
  PID_FILE="/data/adb/cerberus/cerberusd.pid"
  
  # 等待几秒钟，给守护进程启动时间
  sleep 5
  
  if [ -f "$PID_FILE" ] && ps -p $(cat $PID_FILE) > /dev/null; then
    # 守护进程正在运行
    DAEMON_PID=$(cat $PID_FILE)
    sed -i "s/^description=.*/description=[✅ Guardian Running, PID: $DAEMON_PID] A system-level guardian for performance and battery./" "$PROP_FILE"
  else
    # 守护进程启动失败
    sed -i "s/^description=.*/description=[❌ Guardian Failed to Start] Check Magisk logs and /data/adb/cerberus/daemon.log for errors./" "$PROP_FILE"
  fi
}


# --- Main Execution ---
wait_until_boot_complete
start_daemon
update_description
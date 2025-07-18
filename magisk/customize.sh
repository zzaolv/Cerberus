#!/system/bin/sh
#
# Project Cerberus - Magisk Installer Script
#

# --- 1. 环境与权限设置 ---

# Magisk的内置变量，告诉它我们需要自定义解压
SKIPUNZIP=0

# 设置可执行文件和数据目录的权限
# UI (755), 守护进程 (755)
ui_print "- Setting permissions"
set_perm_recursive $MODPATH/system/app/Cerberus-UI 0 0 0755 0644
set_perm $MODPATH/system/bin/cerberusd 0 0 0755

# --- 2. OEM Guard: 厂商后台管理反制 ---

OUTFD=$2
ARCH_SUPPORTED=false
[ "$ARCH" = "arm" ] || [ "$ARCH" = "arm64" ] && ARCH_SUPPORTED=true

if ! $ARCH_SUPPORTED; then
  ui_print "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  ui_print "! Unsupported architecture: $ARCH"
  ui_print "! This module only supports arm32 and arm64."
  ui_print "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  exit 1
fi

ui_print "- Starting OEM Guard..."

# 创建数据目录，用于存放日志和数据库
mkdir -p /data/adb/cerberus
touch /data/adb/cerberus/oem_guard_log.txt
chown -R 1000:1000 /data/adb/cerberus
chmod -R 0771 /data/adb/cerberus

log_file="/data/adb/cerberus/oem_guard_log.txt"
echo "OEM Guard Log - $(date)" > $log_file

# 函数：检测属性是否存在
has_prop() {
  getprop "$1" | grep -q "."
}

# 反制 Hans (OPPO/OnePlus/Realme)
# 特征: /system/bin/hans 或 persist.vendor.enable.hans 属性
if [ -f "/system/bin/hans" ] || has_prop "persist.vendor.enable.hans"; then
  ui_print "  > Hans detected. Applying countermeasures..."
  echo "Hans detected. Applying countermeasures." >> $log_file
  # 强制禁用 Hans
  resetprop -n persist.vendor.enable.hans false
  # 设置一个属性，让我们的守护进程知道需要监控Hans的状态
  setprop persist.sys.cerberus.hans_detected true
fi

# 反制 Millet/GZ (Xiaomi)
# 特征: /system/bin/millet_monitor 或 persist.sys.gz.enable 属性
if [ -f "/system/bin/millet_monitor" ] || has_prop "persist.sys.gz.enable"; then
  ui_print "  > Millet/GZ detected. Applying countermeasures..."
  echo "Millet/GZ detected. Applying countermeasures." >> $log_file
  # 禁用各种Millet相关的服务
  resetprop -n persist.sys.gz.enable false
  resetprop -n persist.sys.brightmillet.enable false
  resetprop -n persist.sys.powmillet.enable false
  # 设置一个属性，让我们的守护进程知道需要监控Millet的状态
  setprop persist.sys.cerberus.millet_detected true
fi

# 反制其他可能的冲突模块
ui_print "- Checking for conflicting modules..."
if [ -d "/data/adb/modules/hans_config" ]; then
    ui_print "  > Found 'hans_config', disabling it."
    touch /data/adb/modules/hans_config/disable
fi
if [ -d "/data/adb/modules/millet_config" ]; then
    ui_print "  > Found 'millet_config', disabling it."
    touch /data/adb/modules/millet_config/disable
fi

ui_print "- OEM Guard finished."
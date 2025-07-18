#!/system/bin/sh
#
# Project Cerberus - Uninstaller Script
#

# 移除数据目录
rm -rf /data/adb/cerberus

# 尝试恢复被禁用的厂商服务（可选，保守起见可以不恢复）
# 如果在安装时设置了 'persist.sys.cerberus.hans_detected', 则恢复
if [ "$(getprop persist.sys.cerberus.hans_detected)" = "true" ]; then
  resetprop -p --delete persist.vendor.enable.hans
  resetprop -p --delete persist.sys.cerberus.hans_detected
fi

if [ "$(getprop persist.sys.cerberus.millet_detected)" = "true" ]; then
  resetprop -p --delete persist.sys.gz.enable
  resetprop -p --delete persist.sys.brightmillet.enable
  resetprop -p --delete persist.sys.powmillet.enable
  resetprop -p --delete persist.sys.cerberus.millet_detected
fi
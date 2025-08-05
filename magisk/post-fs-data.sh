#!/system/bin/sh

MODDIR=${0%/*}

mkdir -p "/data/adb/cerberus"
# 将日志初始化移到 service.sh，因为 post-fs-data 只执行一次且时间很早
# echo "[$(date)] post-fs-data.sh executing..." > "/data/adb/cerberus/boot.log"

# 使用 -p 确保属性在重启后依然生效
resetprop -p -n persist.sys.gz.enable false
resetprop -p -n persist.sys.millet.handshake false
resetprop -p -n persist.sys.powmillet.enable false
resetprop -p -n persist.sys.brightmillet.enable false
resetprop -p -n persist.vendor.enable.hans false

# echo "[$(date)] post-fs-data.sh finished." >> "/data/adb/cerberus/boot.log"
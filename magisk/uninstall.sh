#!/system/bin/sh
# D:/project/Cerberus/Cerberus_Module/uninstall.sh

# 当用户在 Manager 中卸载模块时，这个脚本会自动执行

# 还原可能被我们修改过的系统属性
# resetprop -p 会删除属性，让系统恢复到默认值
resetprop -p --delete persist.vendor.enable.hans
resetprop -p --delete persist.sys.gz.enable
resetprop -p --delete persist.sys.brightmillet.enable
resetprop -p --delete persist.sys.powmillet.enable

# 删除我们的日志文件和目录
rm -rf /data/adb/cerberus
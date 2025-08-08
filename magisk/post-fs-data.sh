#!/system/bin/sh

MODDIR=${0%/*}

# 模块数据目录
CERBERUS_DIR="/data/adb/cerberus"
# 属性备份文件
PROP_BACKUP_FILE="$CERBERUS_DIR/prop_backup.conf"

mkdir -p "$CERBERUS_DIR"

# 定义所有需要修改的属性列表
PROP_LIST="
persist.sys.spc.enabled
persist.sys.spc.cpuexception.enable
persist.sys.spc.process.tracker.enable
persist.sys.prestart.proc
persist.sys.mfz.enable
persist.sys.periodic.u.enable
persist.sys.periodic.u.startprocess.enable
persist.sys.lmkd.extend_reclaim.enable
persist.sys.lmkd.double_watermark.enable
persist.sys.lmkd.camera_adaptive_lmk.enable
persist.sys.miui.camera.boost.enable
persist.sys.memory_standard.enable
persist.sys.mms.compact_enable
persist.sys.ssmc.enable
persist.sys.miui.damon.enable
persist.sys.gz.enable
persist.sys.millet.handshake
persist.sys.powmillet.enable
persist.sys.brightmillet.enable
persist.vendor.enable.hans
"

# 检查是否是首次运行（通过备份文件是否存在来判断）
if [ ! -f "$PROP_BACKUP_FILE" ]; then
  echo "First run detected. Backing up original properties..." >&2
  # 清空旧的备份文件（以防万一）
  : > "$PROP_BACKUP_FILE"
  # 遍历属性列表，读取并备份当前值
  for prop in $PROP_LIST; do
    # 使用 getprop 获取当前值
    current_value=$(getprop "$prop")
    # 将"属性名=当前值"写入备份文件
    # 如果属性不存在，current_value会是空字符串，这也是正确的备份
    echo "$prop=$current_value" >> "$PROP_BACKUP_FILE"
  done
  echo "Backup complete." >&2
fi

# 统一应用优化属性
echo "Applying Cerberus system property optimizations..." >&2

# 禁用小米/澎湃OS系统压力控制器 (SPC)
resetprop -p -n persist.sys.spc.enabled false
resetprop -p -n persist.sys.spc.cpuexception.enable false
resetprop -p -n persist.sys.spc.process.tracker.enable false

# 禁用应用预加载
resetprop -p -n persist.sys.prestart.proc false

# 禁用内存冻结 (MFZ)
resetprop -p -n persist.sys.mfz.enable false

# 禁用定期清理
resetprop -p -n persist.sys.periodic.u.enable false
resetprop -p -n persist.sys.periodic.u.startprocess.enable false

# 禁用相机内存优化 (部分属性使用 0)
resetprop -p -n persist.sys.lmkd.extend_reclaim.enable 0
resetprop -p -n persist.sys.lmkd.double_watermark.enable 0
resetprop -p -n persist.sys.lmkd.camera_adaptive_lmk.enable false
resetprop -p -n persist.sys.miui.camera.boost.enable 0

# 禁用其他小米特定优化
resetprop -p -n persist.sys.memory_standard.enable false
resetprop -p -n persist.sys.mms.compact_enable false
resetprop -p -n persist.sys.ssmc.enable false
resetprop -p -n persist.sys.miui.damon.enable false

# 禁用您之前已有的属性
resetprop -p -n persist.sys.gz.enable false
resetprop -p -n persist.sys.millet.handshake false
resetprop -p -n persist.sys.powmillet.enable false
resetprop -p -n persist.sys.brightmillet.enable false
resetprop -p -n persist.vendor.enable.hans false

echo "Optimizations applied." >&2
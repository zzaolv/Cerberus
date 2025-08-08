#!/system/bin/sh

# --- 清理守护进程 ---
# 使用 killall 更稳健，可以杀死所有名为 cerberusd 的进程
killall cerberusd

# --- 清理应用 (后台执行) ---
(
  # 等待系统启动完成
  while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
  done
  # 卸载应用
  pm uninstall "com.crfzit.crfzit"
) &

# --- 恢复系统属性 ---
CERBERUS_DIR="/data/adb/cerberus"
PROP_BACKUP_FILE="$CERBERUS_DIR/prop_backup.conf"

# 检查备份文件是否存在
if [ -f "$PROP_BACKUP_FILE" ]; then
  echo "Restoring system properties from backup..." >&2
  # 逐行读取备份文件
  while IFS='=' read -r prop_name original_value; do
    # 如果原始值为空，说明这个属性原本不存在，我们就删除它
    if [ -z "$original_value" ]; then
      echo "  - Deleting property: $prop_name" >&2
      resetprop -p --delete "$prop_name"
    # 否则，将属性恢复到它的原始值
    else
      echo "  - Restoring property: $prop_name -> $original_value" >&2
      resetprop -p -n "$prop_name" "$original_value"
    fi
  done < "$PROP_BACKUP_FILE"
  echo "Properties restored." >&2
else
  # 如果备份文件不存在，作为备用方案，执行简单的删除操作
  echo "Backup file not found. Deleting known properties as a fallback..." >&2
  resetprop -p --delete persist.sys.spc.enabled
  resetprop -p --delete persist.sys.spc.cpuexception.enable
  resetprop -p --delete persist.sys.spc.process.tracker.enable
  resetprop -p --delete persist.sys.prestart.proc
  resetprop -p --delete persist.sys.mfz.enable
  resetprop -p --delete persist.sys.periodic.u.enable
  resetprop -p --delete persist.sys.periodic.u.startprocess.enable
  resetprop -p --delete persist.sys.lmkd.extend_reclaim.enable
  resetprop -p --delete persist.sys.lmkd.double_watermark.enable
  resetprop -p --delete persist.sys.lmkd.camera_adaptive_lmk.enable
  resetprop -p --delete persist.sys.miui.camera.boost.enable
  resetprop -p --delete persist.sys.memory_standard.enable
  resetprop -p --delete persist.sys.mms.compact_enable
  resetprop -p --delete persist.sys.ssmc.enable
  resetprop -p --delete persist.sys.miui.damon.enable
  resetprop -p --delete persist.sys.gz.enable
  resetprop -p --delete persist.sys.millet.handshake
  resetprop -p --delete persist.sys.powmillet.enable
  resetprop -p --delete persist.sys.brightmillet.enable
  resetprop -p --delete persist.vendor.enable.hans
fi

# --- 清理模块数据目录 (在所有操作完成后) ---
rm -rf "$CERBERUS_DIR"
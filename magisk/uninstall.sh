#!/system/bin/sh
# shellcheck disable=SC2148
#
# Project Cerberus Uninstallation Script
#

# 模块的数据目录
DATA_DIR="/data/adb/cerberus"
# 记录被禁用服务的文件
DISABLED_SERVICES_LOG="$DATA_DIR/disabled_services.log"

# --- Main Logic ---

echo "*********************************************************"
echo "*      正在卸载 Project Cerberus...                     *"
echo "*********************************************************"

# 1. 恢复被禁用的厂商服务
if [ -f "$DISABLED_SERVICES_LOG" ]; then
  echo "- 正在尝试恢复之前被禁用的厂商服务..."
  # 逐行读取日志文件
  while IFS= read -r PKG; do
    if [ -n "$PKG" ]; then
      echo "  > 正在重新启用: $PKG"
      pm enable "$PKG" >/dev/null 2>&1
      if [ $? -eq 0 ]; then
        echo "    -> 已成功启用。"
      else
        echo "    -! 启用失败，可能已被其他方式卸载。"
      fi
    fi
  done < "$DISABLED_SERVICES_LOG"
else
  echo "- 未找到被禁用服务的记录，跳过恢复步骤。"
fi

# 2. 清理数据目录
# 这个目录包含数据库和日志，卸载时删除以保持系统清洁
if [ -d "$DATA_DIR" ]; then
  echo "- 正在删除数据目录: $DATA_DIR"
  rm -rf "$DATA_DIR"
fi

echo ""
echo "*********************************************************"
echo "*      Project Cerberus 卸载完成！                      *"
echo "*      重启后，所有更改将还原。                         *"
echo "*********************************************************"

# 脚本执行完毕后，Magisk会自动删除/system/addon.d中的脚本和模块目录
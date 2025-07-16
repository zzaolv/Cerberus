#!/system/bin/sh
# shellcheck disable=SC2148
#
# Project Cerberus Installation Script
#

# Magisk 环境 API 版本
# 如果您的Magisk版本较旧，可能需要降低这个值
MIN_API_VERSION=20400 

# 模块的安装路径 (由Magisk注入)
# MODDIR

# 模块的数据目录，用于存放日志和配置
DATA_DIR="/data/adb/cerberus"
# 用于记录被禁用服务的文件
DISABLED_SERVICES_LOG="$DATA_DIR/disabled_services.log"

# 定义已知的冲突服务包名
# 小米: com.miui.powerkeeper
# OPPO/OnePlus: com.oplus.battery, com.coloros.oppoguardelf
# Vivo: com.vivo.abe, com.vivo.pem
# 华为: com.huawei.powergenie
CONFLICT_PACKAGES="
com.miui.powerkeeper
com.oplus.battery
com.coloros.oppoguardelf
com.vivo.abe
com.vivo.pem
com.huawei.powergenie
"

# --- Helper Functions ---

ui_print() {
  echo "$1"
}

# --- Main Logic ---

# 1. 检查 Magisk API 版本
if [ "$MAGISK_VER_CODE" -lt "$MIN_API_VERSION" ]; then
  ui_print "*********************************************************"
  ui_print "! Magisk版本过旧，请更新至 v21+ 以上版本。"
  ui_print "*********************************************************"
  abort
fi

ui_print "*********************************************************"
ui_print "*      正在安装 Project Cerberus v1.0                     *"
ui_print "*********************************************************"

# 2. 创建数据目录
ui_print "- 创建数据目录: $DATA_DIR"
mkdir -p "$DATA_DIR"
# 清理旧的日志文件，以防万一
rm -f "$DISABLED_SERVICES_LOG"
touch "$DISABLED_SERVICES_LOG"

# 3. 设置文件权限
ui_print "- 设置文件和目录权限..."
# 模块目录: 所有人可读可执行，root可写
set_perm_recursive "$MODPATH" 0 0 0755 0644
# 守护进程: root可执行
set_perm "$MODPATH/cerberusd" 0 0 0755
# service.sh: root可执行
set_perm "$MODPATH/service.sh" 0 0 0755
# uninstall.sh: root可执行
set_perm "$MODPATH/uninstall.sh" 0 0 0755

# === 新增部分：为数据目录设置权限 ===
# 守护进程以 root(0) 运行，UI App 以 app (e.g., 10xxx) 运行。
# 771 权限允许 owner(root) 和 group(root) 读写执行，其他人无权限。
# 这对于 socket 和数据库是安全的。SELinux 策略将进一步细化访问控制。
ui_print "- 设置数据目录权限..."
set_perm_recursive "$DATA_DIR" 0 0 0771 0660


# 4. OEM 后台管理反制 (OEM Guard)
ui_print "- 正在检测并禁用厂商后台管理服务..."
for PKG in $CONFLICT_PACKAGES; do
  # `pm path` 会返回包的路径，如果不存在则返回空
  if [ -n "$(pm path "$PKG")" ]; then
    ui_print "  > 检测到冲突服务: $PKG"
    # 禁用应用
    pm disable "$PKG" >/dev/null 2>&1
    if [ $? -eq 0 ]; then
      ui_print "    -> 已成功禁用。"
      # 将被禁用的包名记录到日志中，供卸载时使用
      echo "$PKG" >> "$DISABLED_SERVICES_LOG"
    else
      ui_print "    -! 禁用失败 (可能需要手动处理)。"
    fi
  fi
done

# 5. 自动安装UI应用
ui_print "- 正在安装 Cerberus UI 应用..."
# 使用 pm install 命令来安装模块目录中的APK
# -r: 替换已存在的应用
# -d: 允许降级安装 (可选，但有用)
pm install -r -d "$MODPATH/Cerberus-UI.apk" >/dev/null 2>&1
if [ $? -eq 0 ]; then
  ui_print "  -> UI 应用安装/更新成功。"
else
  ui_print "  -! UI 应用安装失败，请在重启后手动安装。"
  ui_print "     APK 路径: /data/adb/modules/cerberus/Cerberus-UI.apk"
fi


ui_print ""
ui_print "*********************************************************"
ui_print "*      Project Cerberus 安装完成！                      *"
ui_print "*      请重启手机以应用模块。                           *"
ui_print "*********************************************************"
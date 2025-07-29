#!/system/bin/sh
# D:/project/Cerberus/Cerberus_Module/customize.sh

##########################################################################################
#
# Cerberus Module Installer Script v1.2 (with APK install)
#
##########################################################################################

ui_print "*******************************"
ui_print "    Project Cerberus Daemon    "
ui_print "*******************************"

# --- 冲突模块处理 ---
ui_print "- Checking for conflicting modules..."
if [ -d "/data/adb/modules/hans_config" ]; then
  ui_print "  > Found 'Hans Config', removing..."
  rm -rf /data/adb/modules/hans_config
fi
if [ -d "/data/adb/modules/millet_config" ]; then
  ui_print "  > Found 'Millet Config', removing..."
  rm -rf /data/adb/modules/millet_config
fi

# --- 提取文件 ---
ui_print "- Extracting module files"
unzip -o "$ZIPFILE" 'system/*' -d $MODPATH >&2
unzip -o "$ZIPFILE" 'sepolicy.rule' -d $MODPATH >&2
unzip -o "$ZIPFILE" 'Cerberus.apk' -d $MODPATH >&2

# --- [核心新增] 安装捆绑的 APK ---
APK_PATH="$MODPATH/Cerberus.apk"
if [ -f "$APK_PATH" ]; then
  ui_print "- Installing Cerberus App..."
  # 使用 pm install 命令进行安装。-r 表示如果已安装则替换，-d 表示允许降级安装。
  pm install -r -d "$APK_PATH"
  if [ $? -eq 0 ]; then
    ui_print "  > App installed successfully."
  else
    ui_print "  > WARN: App installation failed. Please install manually."
  fi
else
  ui_print "- WARN: Cerberus.apk not found in module zip."
fi

# --- 设置权限 ---
ui_print "- Setting permissions"
set_perm_recursive $MODPATH 0 0 0755 0644
set_perm $MODPATH/system/bin/cerberusd 0 0 0755 u:object_r:system_file:s0
set_perm $MODPATH/service.sh 0 0 0755
set_perm $MODPATH/post-fs-data.sh 0 0 0755
set_perm $MODPATH/uninstall.sh 0 0 0755

ui_print "- Installation complete!"
ui_print "- Please reboot your device."
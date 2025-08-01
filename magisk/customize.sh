#!/system/bin/sh

# --- 环境检查 ---
$BOOTMODE || abort "- 错误: 请在 Magisk 或 KernelSU 环境中安装！"
[ "$API" -ge 30 ] || abort "- 错误: Cerberus 仅支持 Android 11 (API 30) 及以上版本！"

ui_print "- 安卓版本 $API, 符合要求。"
ui_print "- 设备架构: $ARCH (将使用默认的arm64二进制文件)。"

# --- 文件权限设置 ---
ui_print "- 设置文件权限..."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/cerberusd" 0 0 0755
set_perm "$MODPATH/tools/magiskpolicy" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755


# --- APK安装 ---
APK_PATH="$MODPATH/Cerberus.apk"
APK_PACKAGE_NAME="com.crfzit.crfzit"
if [ -f "$APK_PATH" ]; then
  ui_print "- 正在安装 Cerberus 控制面板..."
  TMP_APK_PATH="/data/local/tmp/Cerberus.apk"
  cp "$APK_PATH" "$TMP_APK_PATH"
  chmod 644 "$TMP_APK_PATH"

  # 尝试安装
  install_output=$(pm install -r "$TMP_APK_PATH" 2>&1)

  if echo "$install_output" | grep -q "Success"; then
    ui_print "  > 控制面板安装成功。"
    rm "$TMP_APK_PATH"
  else
    ui_print "  > 安装失败，原因: $install_output"
    ui_print "  > 正在尝试卸载旧版本后重装..."
    pm uninstall "$APK_PACKAGE_NAME" >/dev/null 2>&1
    sleep 1
    reinstall_output=$(pm install -r "$TMP_APK_PATH" 2>&1)
    if echo "$reinstall_output" | grep -q "Success"; then
      ui_print "  > 重装成功！如果之前已启用，请在LSPosed中检查其状态。"
      rm "$TMP_APK_PATH"
    else
      ui_print "  > [!!!] 重装失败: $reinstall_output"
      ui_print "  > [!!!] 请在重启后手动安装位于 /sdcard/Download/Cerberus.apk 的APK文件。"
      cp "$TMP_APK_PATH" "/sdcard/Download/Cerberus.apk"
    fi
  fi
  rm "$APK_PATH"
fi

ui_print " "
ui_print "--- Cerberus 安装完成 ---"
ui_print "- 请在 LSPosed Manager 中启用 [Cerberus] 模块"
ui_print "- 并确保其作用域包含了 [Android 系统]"
ui_print "- 重启设备以激活所有功能！"
ui_print " "
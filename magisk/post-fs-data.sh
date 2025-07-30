#!/system/bin/sh
# D:/project/Cerberus/Cerberus_Module/post-fs-data.sh

MODDIR=${0%/*}

mkdir -p /data/adb/cerberus
LOG_FILE="/data/adb/cerberus/module_boot.log"
echo "[$(date)] post-fs-data.sh started" > $LOG_FILE

log_print() {
  echo "[$(date)] $1" >> $LOG_FILE
}

# --- 系统组件调整 ---
# ... (这部分代码不变) ...
has_bin() { test -f "/system/bin/$1" || test -f "/system/vendor/bin/$1"; }
if has_bin "hans"; then resetprop -n persist.vendor.enable.hans false; fi
if has_bin "millet_monitor"; then resetprop -n persist.sys.gz.enable false; fi

# --- [终极修复] 使用我们模块自带的 magiskpolicy 工具 ---
POLICY_TOOL="$MODDIR/tools/magiskpolicy"

if [ -f "$POLICY_TOOL" ]; then
    log_print "Found bundled policy tool at: $POLICY_TOOL"
    
    # 授予执行权限，以防万一
    chmod 755 "$POLICY_TOOL"
    
    "$POLICY_TOOL" --live \
    "type cerberusd, domain" \
    "type cerberusd_exec, exec_type, file_type, system_file_type" \
    "type cerberus_socket, file_type" \
    "permissive cerberusd" \
    "type_transition init system_file:process cerberusd" \
    "allow init cerberusd_exec:file { read open execute }" \
    "allow cerberusd self:process { fork }" \
    "allow cerberusd self:unix_stream_socket create_socket_perms" \
    "type_transition cerberusd self:unix_stream_socket cerberus_socket" \
    "allow cerberusd shell_exec:file rx_file_perms" \
    "allow cerberusd system_file:file execute_no_trans" \
    "allow cerberusd self:fifo_file rw_file_perms" \
    "binder_use(cerberusd)" \
    "binder_call(cerberusd, system_server)" \
    "binder_call(cerberusd, servicemanager)" \
    "allow cerberusd system_api_service:service_manager find" \
    "allow cerberusd { app_domain system_server_domain }:{ dir file lnk_file } r_dir_perms" \
    "allow cerberusd { app_domain system_server_domain }:process { getattr ptrace }" \
    "allow cerberusd kernel:process getsched" \
    "allow { untrusted_app system_server } cerberusd:unix_stream_socket connectto" \
    "allow { untrusted_app system_server } cerberus_socket:sock_file rw_file_perms" \
    "allow { magisk_client su } cerberusd:process { search getattr }" \
    "allow { magisk_client su } module_data_file:dir rw_dir_perms" \
    "allow { magisk_client su } module_data_file:file { create open read write getattr setattr unlink }"

    log_print "SELinux rules injected via bundled magiskpolicy."
else
    log_print "FATAL: Bundled magiskpolicy tool not found at $POLICY_TOOL. SELinux rules not applied."
fi

log_print "post-fs-data.sh finished."
#!/system/bin/sh

MODDIR=${0%/*}
DATA_DIR="/data/adb/cerberus"
LOG_FILE="$DATA_DIR/boot.log"

#MAGISKPOLICY="$MODDIR/tools/magiskpolicy"

mkdir -p "$DATA_DIR"
echo "[$(date)] post-fs-data.sh executing (Policy v3 - Self-Contained)..." > "$LOG_FILE"

log_print() {
  echo "[$(date)] $1" >> "$LOG_FILE"
}

#if [ ! -x "$MAGISKPOLICY" ]; then
#  log_print "!!! CRITICAL ERROR: $MAGISKPOLICY not found or not executable!"
#  exit 1
#fi
#log_print "Using self-contained magiskpolicy at $MAGISKPOLICY"

#"$MAGISKPOLICY" --live "
# 1. 定义类型
#type cerberusd, domain;
#type cerberusd_exec, exec_type, file_type, system_file_type;
#type cerberus_data_file, file_type, data_file_type;

# 2. 允许init启动我们的守护进程
#type_transition init cerberusd_exec:process cerberusd;
#allow init cerberusd_exec:file { read open execute };

# 3. 守护进程自身权限
#allow cerberusd cerberusd_exec:file { read open execute entrypoint };
#allow cerberusd cerberus_data_file:dir create_dir_perms;
#allow cerberusd cerberus_data_file:file create_file_perms;

# --- [核心修复] 增强网络权限 ---
#allow cerberusd self:tcp_socket { create name_bind listen accept getattr setattr read write shutdown ioctl };
#allow untrusted_app cerberusd:tcp_socket { connect getattr read write shutdown ioctl };
#allow cerberusd self:node { TCP_NODESERVER };
# ------------------------------------

# 4. 系统交互权限
#allow cerberusd proc:dir r_dir_perms;
#allow cerberusd proc_stat:file r_file_perms;
#allow cerberusd proc_meminfo:file r_file_perms;
#allow cerberusd qtaguid_proc:file r_file_perms;
#allow cerberusd shell_exec:file rx_file_perms;
#allow cerberusd cgroup:dir create_dir_perms;
#allow cerberusd cgroup:file create_file_perms;

# 5. Binder通信权限
#binder_use(cerberusd)
#binder_call(cerberusd, system_server)
#binder_call(cerberusd, servicemanager)
#allow cerberusd system_api_service:service_manager find;

# 6. 跨域进程交互权限
#allow cerberusd app_domain:dir r_dir_perms;
#allow cerberusd app_domain:file r_file_perms;
#allow cerberusd app_domain:process { getattr ptrace getsched sigkill sigstop sigcont };
#allow cerberusd system_server:process { getattr ptrace getsched };
#allow cerberusd kernel:process { getsched };

# 7. 日志权限
#allow cerberusd logd:unix_stream_socket connectto;
#allow cerberusd log_socket:sock_file write;
#"
#log_print "SELinux rules (v3 - Self-Contained) for Cerberus injected."

log_print "Disabling conflicting OEM features..."
resetprop -n persist.sys.gz.enable false
resetprop -n persist.sys.millet.handshake false
resetprop -n persist.sys.powmillet.enable false
resetprop -n persist.sys.brightmillet.enable false
resetprop -n persist.vendor.enable.hans false
log_print "OEM features disabled."

log_print "post-fs-data.sh finished."
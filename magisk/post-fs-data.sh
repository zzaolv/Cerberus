#!/system/bin/sh
# D:/project/Cerberus/Cerberus_Module/post-fs-data.sh

MODDIR=${0%/*}

mkdir -p /data/adb/cerberus
LOG_FILE="/data/adb/cerberus/module_boot.log"
echo "[$(date)] post-fs-data.sh started (Policy v4_TCP)" > $LOG_FILE

log_print() {
  echo "[$(date)] $1" >> $LOG_FILE
}

# --- [核心修复] 切换到TCP网络权限 ---
magiskpolicy --live "
type cerberusd, domain;
type cerberusd_exec, exec_type, file_type, system_file_type;
type cerberus_data_file, file_type, data_file_type;
type_transition init cerberusd_exec:process cerberusd;
allow init cerberusd_exec:file { read open execute };
allow cerberusd cerberusd_exec:file { read open execute entrypoint };
allow cerberusd cerberus_data_file:dir create_dir_perms;
allow cerberusd cerberus_data_file:file create_file_perms;
allow cerberusd self:tcp_socket create_socket_perms;
allow cerberusd self:tcp_socket { name_bind };
allow cerberusd proc:dir r_dir_perms;
allow cerberusd proc_stat:file r_file_perms;
allow cerberusd proc_meminfo:file r_file_perms;
allow cerberusd qtaguid_proc:file r_file_perms;
allow cerberusd shell_exec:file rx_file_perms;
binder_use(cerberusd)
binder_call(cerberusd, system_server)
binder_call(cerberusd, servicemanager)
allow cerberusd system_api_service:service_manager find;
allow cerberusd app_domain:dir r_dir_perms;
allow cerberusd app_domain:file r_file_perms;
allow cerberusd app_domain:process { getattr ptrace getsched };
allow cerberusd system_server:process { getattr ptrace getsched };
allow cerberusd kernel:process getsched;
allow cerberusd logd:unix_stream_socket connectto;
allow cerberusd log_socket:sock_file write;
allow cerberusd cgroup:dir create_dir_perms;
allow cerberusd cgroup:file create_file_perms;
"

log_print "SELinux rules (v4_TCP) injected."
log_print "post-fs-data.sh finished."
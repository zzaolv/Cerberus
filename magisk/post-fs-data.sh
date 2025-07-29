#!/system/bin/sh
# D:/project/Cerberus/Cerberus_Module/post-fs-data.sh

MODDIR=${0%/*}

LOG_FILE="/data/adb/cerberus/module_boot.log"
mkdir -p /data/adb/cerberus
echo "[$(date)] post-fs-data.sh started" > $LOG_FILE

log_print() {
  echo "[$(date)] $1" >> $LOG_FILE
}

has_bin() { test -f "/system/bin/$1" || test -f "/system/vendor/bin/$1"; }

if has_bin "hans"; then
    log_print "Hans detected. Disabling..."
    resetprop -n persist.vendor.enable.hans false
fi

if has_bin "millet_monitor"; then
    log_print "Millet detected. Disabling..."
    resetprop -n persist.sys.gz.enable false
    resetprop -n persist.sys.brightmillet.enable false
    resetprop -n persist.sys.powmillet.enable false
fi

if [ -d /data/adb/ksu ]; then
  log_print "KernelSU detected. Applying App Profile."
  /data/adb/ksu/bin/ksu_profile \
  --domain cerberusd --permissive \
  --type cerberus_socket --file \
  --allow cerberusd self unix_stream_socket create_socket_perms \
  --type_transition cerberusd self unix_stream_socket cerberus_socket \
  --allow system_server cerberusd unix_stream_socket connectto \
  --allow system_server cerberus_socket sock_file write \
  --allow untrusted_app cerberusd unix_stream_socket connectto \
  --allow untrusted_app cerberus_socket sock_file write \
  --allow cerberusd '{ app_domain system_server_domain }' '{ dir file lnk_file }' '{ search getattr read open }' \
  --allow cerberusd '{ app_domain system_server_domain }' process '{ getattr ptrace }' \
  --allow cerberusd shell_exec file '{ read execute execute_no_trans open }' \
  --allow cerberusd system_file file execute_no_trans \
  --allow su cerberusd process '{ search getattr }'

  log_print "KernelSU App Profile 'cerberusd' configured."
else
  log_print "Magisk detected. SEPolicy rules will be applied via sepolicy.rule."
fi
#!/system/bin/sh

MODDIR=${0%/*}
DATA_DIR="/data/adb/cerberus"
LOG_FILE="$DATA_DIR/boot.log"

mkdir -p "$DATA_DIR"
echo "[$(date)] post-fs-data.sh executing (Policy v3 - Self-Contained)..." > "$LOG_FILE"

log_print() {
  echo "[$(date)] $1" >> "$LOG_FILE"
}

log_print "Disabling conflicting OEM features..."
resetprop -n persist.sys.gz.enable false
resetprop -n persist.sys.millet.handshake false
resetprop -n persist.sys.powmillet.enable false
resetprop -n persist.sys.brightmillet.enable false
resetprop -n persist.vendor.enable.hans false
log_print "OEM features disabled."

log_print "post-fs-data.sh finished."
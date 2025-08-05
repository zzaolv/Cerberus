#!/system/bin/sh

MODDIR=${0%/*}
DATA_DIR="/data/adb/cerberus"
LOG_FILE="$DATA_DIR/boot.log"
PROP_FILE="$MODDIR/module.prop"

# >> 初始化日志，每次服务启动都重新记录
echo "[$(date)] service.sh starting..." > "$LOG_FILE"
# >> 将标准输出和错误输出都重定向到日志文件
exec >> "$LOG_FILE" 2>&1

echo "----------------------------------------"
echo "[$(date)] Cerberus service script invoked..."
echo "[$(date)] Module path: $MODDIR"

DAEMON_PATH="$MODDIR/system/bin/cerberusd"

# --- 函数定义 ---

is_daemon_running() {
  # 使用 pgrep -f 精确匹配完整路径，避免误判
  if pgrep -f "^$DAEMON_PATH$" > /dev/null; then
    return 0
  else
    return 1
  fi
}

update_description() {
    echo "[$(date)] Updating module description..."
    # 给予守护进程启动和初始化的时间
    sleep 2
    
    DAEMON_PID=$(pgrep -f "^$DAEMON_PATH$")
    
    echo "[$(date)] Check Result: Daemon PID is '$DAEMON_PID'."

    # 从 module.prop 中动态读取版本号，避免硬编码
    VERSION=$(grep '^version=' "$PROP_FILE" | cut -d= -f2)
    
    if [ -n "$DAEMON_PID" ]; then
        DESCRIPTION="description=✅ 运行中 [$VERSION | PID: $DAEMON_PID]"
    else
        DESCRIPTION="description=❌ 启动失败, 检查日志: $LOG_FILE"
    fi

    # 使用更兼容的方式更新文件，防止 sed -i 的问题
    # 1. 移除旧的 description 行
    grep -v '^description=' "$PROP_FILE" > "$PROP_FILE.tmp"
    # 2. 追加新的 description 行
    echo "$DESCRIPTION" >> "$PROP_FILE.tmp"
    # 3. 替换原文件
    mv "$PROP_FILE.tmp" "$PROP_FILE"
    
    echo "[$(date)] Description updated to: $DESCRIPTION"
}

# --- 主逻辑 ---

# 1. 等待开机完成
echo "[$(date)] Waiting for boot to complete..."
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 5
done
echo "[$(date)] Boot completed."

# 2. 等待系统就绪（屏幕解锁）
# 这是一个非常好的指标，表明系统UI和服务已准备就绪
count=0
limit=60 # 等待最多 120 秒

echo "[$(date)] Waiting for system UI to be ready (screen unlock)..."
while [ $count -lt $limit ]; do
    # 'mInputRestricted=false' 是一个很好的判断屏幕已解锁且可交互的标志
    locked=$(dumpsys window policy | grep -m 1 'mInputRestricted' | cut -d= -f2)

    if [ "$locked" = "false" ]; then
        echo "[$(date)] Screen is unlocked. System is fully ready."
        # >> 解锁后再等待一小会，确保所有服务都稳定了
        sleep 5
        break
    fi
    
    count=$((count + 1))
    sleep 2
done

if [ $count -ge $limit ]; then
    echo "[$(date)] Timeout waiting for unlock. Proceeding anyway, but daemon start might be unstable."
fi

# 3. 启动守护进程
echo "[$(date)] Preparing to start daemon..."

if is_daemon_running; then
    echo "[$(date)] Daemon is already running. No action needed."
else
    if [ -x "$DAEMON_PATH" ]; then
        echo "[$(date)] Starting cerberusd daemon..."
        # 使用绝对路径，并放入后台
        nohup "$DAEMON_PATH" &
    else
        echo "[$(date)] ERROR: Daemon executable not found or not executable at $DAEMON_PATH!"
        # 如果文件不存在，直接更新描述并退出
        DESCRIPTION="description=❌ 错误: 守护进程文件缺失!"
        grep -v '^description=' "$PROP_FILE" > "$PROP_FILE.tmp"
        echo "$DESCRIPTION" >> "$PROP_FILE.tmp"
        mv "$PROP_FILE.tmp" "$PROP_FILE"
        exit 1
    fi
fi

# 4. 更新描述文件
update_description

echo "[$(date)] service.sh finished."
#!/usr/bin/env bash
# 忘记密码时重置管理员密码：删除 server.json（会重新生成初始密码），保留其余数据。
set -euo pipefail
SERVICE="pcl-dsh-linux"
DATA_DIR="/opt/pcl-dsh-linux/data"
if [ "$(id -u)" -ne 0 ]; then echo "请使用 root 运行。"; exit 1; fi
systemctl stop "$SERVICE"
STAMP=$(date +%Y%m%d-%H%M%S)
if [ -f "$DATA_DIR/config/server.json" ]; then
  mv "$DATA_DIR/config/server.json" "$DATA_DIR/config/server.json.bak-$STAMP"
fi
rm -f "$DATA_DIR/initial-password"
systemctl start "$SERVICE"
sleep 5
echo "已重置。新初始密码文件：$DATA_DIR/initial-password"
[ -f "$DATA_DIR/initial-password" ] && cat "$DATA_DIR/initial-password"

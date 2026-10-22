#!/usr/bin/env bash
# PCL-DSH-Linux 卸载脚本。默认保留数据目录；加 --purge 才会删除。
set -euo pipefail
SERVICE="pcl-dsh-linux"
INSTALL_DIR="/opt/pcl-dsh-linux"
PURGE="no"
if [ "$1" = "--purge" ]; then PURGE="yes"; fi
if [ "$(id -u)" -ne 0 ]; then echo "请使用 root 运行。"; exit 1; fi

systemctl stop "$SERVICE" 2>/dev/null || true
systemctl disable "$SERVICE" 2>/dev/null || true
rm -f "/etc/systemd/system/$SERVICE.service"
rm -f /etc/nginx/conf.d/pcl-dsh-linux.conf
systemctl daemon-reload
if command -v nginx >/dev/null 2>&1; then nginx -t && systemctl reload nginx || true; fi
rm -rf "$INSTALL_DIR/current" "$INSTALL_DIR/releases"
if [ "$PURGE" = "yes" ]; then
  rm -rf "$INSTALL_DIR"
  if id -u pcl-dsh >/dev/null 2>&1; then userdel pcl-dsh || true; fi
  echo "已完全删除程序与数据。"
else
  echo "程序已卸载；数据保留在 $INSTALL_DIR/data。如需删除请运行 ./uninstall.sh --purge"
fi

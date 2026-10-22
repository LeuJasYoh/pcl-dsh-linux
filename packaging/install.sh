#!/usr/bin/env bash
# PCL-DSH-Linux 安装/升级脚本（在发布包解压目录内以 root 运行）
set -euo pipefail

SERVICE="pcl-dsh-linux"
INSTALL_DIR="/opt/pcl-dsh-linux"
DATA_DIR="$INSTALL_DIR/data"
SOURCE_DIR=$(cd "$(dirname "$0")" && pwd)
VERSION=$(cat "$SOURCE_DIR/VERSION")
RELEASE_DIR="$INSTALL_DIR/releases/$VERSION"
ALLOW_IP=""

if [ "$(id -u)" -ne 0 ]; then
  echo "请使用 root 运行：sudo ./install.sh"
  exit 1
fi
if ! command -v java >/dev/null 2>&1; then
  echo "未找到 java，请先安装 JDK 21+（例如 apt install openjdk-21-jre）。"
  exit 1
fi
JAVA_MAJOR=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
if [ "$JAVA_MAJOR" -lt 21 ]; then
  echo "需要 Java 21 或更高版本，当前为 $JAVA_MAJOR。"
  exit 1
fi

echo "== 创建系统用户与目录 =="
if ! id -u pcl-dsh >/dev/null 2>&1; then
  useradd --system --home "$INSTALL_DIR" --shell /usr/sbin/nologin pcl-dsh
fi
mkdir -p "$INSTALL_DIR/releases" "$DATA_DIR"
chmod 700 "$DATA_DIR"

echo "== 安装版本 $VERSION =="
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"
cp -R "$SOURCE_DIR/release/." "$RELEASE_DIR/"
cp "$SOURCE_DIR/VERSION" "$RELEASE_DIR/VERSION"
ln -sfn "$RELEASE_DIR" "$INSTALL_DIR/current"
chmod -R a+rX "$RELEASE_DIR"
chown -R pcl-dsh:pcl-dsh "$DATA_DIR"

# Windows 打包无法在 tar 中保存可执行位：安装时补齐。
chmod +x "$RELEASE_DIR/tools/node/bin/node" 2>/dev/null || true
chmod +x "$SOURCE_DIR"/*.sh 2>/dev/null || true

# Windows 上解压的 Node 包缺少 npm/npx/corepack 符号链接（Windows 无法创建 Linux 符号链接），
# 在 Linux 上补齐标准布局（运行期不需要，但便于运维使用 bundled node）。
NODE_BIN="$RELEASE_DIR/tools/node/bin"
if [ -d "$NODE_BIN" ]; then
  [ -e "$NODE_BIN/npm" ] || ln -s ../lib/node_modules/npm/bin/npm-cli.js "$NODE_BIN/npm"
  [ -e "$NODE_BIN/npx" ] || ln -s ../lib/node_modules/npm/bin/npx-cli.js "$NODE_BIN/npx"
  [ -e "$NODE_BIN/corepack" ] || ln -s ../lib/node_modules/corepack/dist/corepack.js "$NODE_BIN/corepack"
fi

echo "== 安装 systemd 服务 =="
if [ -f "$SOURCE_DIR/pcl-dsh-linux.service" ]; then
  cp "$SOURCE_DIR/pcl-dsh-linux.service" "/etc/systemd/system/$SERVICE.service"
fi
systemctl daemon-reload
systemctl enable "$SERVICE" >/dev/null
systemctl restart "$SERVICE"

echo "== 配置 Nginx =="
if command -v nginx >/dev/null 2>&1; then
  cp "$SOURCE_DIR/nginx-pcl-dsh-linux.conf" "/etc/nginx/conf.d/pcl-dsh-linux.conf"
  if [ -t 0 ]; then
    read -r -p "来源 IP 白名单（可选，输入你的公网 IP，留空跳过）: " ALLOW_IP || true
  fi
  if [ -n "$ALLOW_IP" ]; then
    sed -i "s|# allow 203.0.113.10;|allow $ALLOW_IP; allow 127.0.0.1;|" "/etc/nginx/conf.d/pcl-dsh-linux.conf"
    sed -i "s|# deny all;|deny all;|" "/etc/nginx/conf.d/pcl-dsh-linux.conf"
    echo "已启用来源 IP 白名单：$ALLOW_IP"
  fi
  nginx -t
  # nginx 可能已安装但未运行（例如手工停止过）：先确保运行，再 reload 生效。
  if systemctl is-active --quiet nginx; then
    systemctl reload nginx
  else
    systemctl enable --now nginx
    systemctl reload nginx || true
  fi
else
  echo "未安装 Nginx。请先执行：apt update && apt install -y nginx"
  echo "安装 Nginx 后重新运行本脚本即可完成站点配置（不会重复安装版本）。"
fi

echo "== 等待服务就绪 =="
READY="no"
for i in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:8080/api/health" >/dev/null 2>&1; then
    READY="yes"
    break
  fi
  sleep 1
done
if [ "$READY" != "yes" ]; then
  echo "服务未在 40 秒内就绪，请查看：journalctl -u $SERVICE -n 100"
  exit 1
fi

echo ""
echo "安装完成。"
echo "访问地址：http://服务器公网IP/"
if [ -f "$DATA_DIR/initial-password" ]; then
  echo "初始密码文件：$DATA_DIR/initial-password（登录后必须修改，修改后会自动删除）"
  echo "查看密码：cat $DATA_DIR/initial-password"
fi
echo "常用命令：systemctl status $SERVICE | journalctl -u $SERVICE -f"

# M0 Linux PoC 执行手册（PCL-DSH-Linux）

- **目的**：在正式写接入层之前，用真实 Linux 服务器回答 6 个关键问题：① 最新版 DSH 在 Ubuntu 24.04 上能否安装；② 自带 Node 24.21.0 + pnpm 10.34.0 是否可用；③ 启动/就绪/停止/端口释放行为；④ 启动 token 的形式与 Web 访问鉴权；⑤ 工作台是否有 WebSocket / 绝对地址 / CSP 障碍；⑥ 单个实例的真实内存占用。
- **安全边界**：全部操作只在 /opt/pcl-dsh-linux-poc 临时目录；不安装系统软件（Node/pnpm 只解压在本目录）；不修改 Nginx/systemd；不影响现有服务；结束可整目录删除。
- **执行方式**：你逐批执行，把每批输出贴回；我根据结果给出下一批与结论。
- **约定**：命令中的 <...> 需要替换；所有文件都在 /opt/pcl-dsh-linux-poc 内。

## 批次 0（已完成）

服务器已确认：Ubuntu 24.04.4 LTS x86_64、2 vCPU、1.6 GiB 内存 + 2 GiB swap、28 GiB 可用磁盘、JDK 21.0.13、系统 Node v24.15.0（nvm，/root 下）、Nginx 未安装、80 端口空闲、npm/nodejs/GitHub 可访问。

## 批次 1：临时目录 + 自带 Node/pnpm

```bash
set -e
POC=/opt/pcl-dsh-linux-poc
mkdir -p "$POC/tools" "$POC/runtimes" "$POC/dsh-home" "$POC/workspace" "$POC/agents-home"

cd "$POC/tools"
curl -fsSLO https://nodejs.org/dist/v24.21.0/node-v24.21.0-linux-x64.tar.xz
curl -fsSLO https://nodejs.org/dist/v24.21.0/SHASUMS256.txt
grep ' node-v24.21.0-linux-x64.tar.xz$' SHASUMS256.txt | sha256sum -c -
tar -xJf node-v24.21.0-linux-x64.tar.xz
mv node-v24.21.0-linux-x64 node
rm -f node-v24.21.0-linux-x64.tar.xz SHASUMS256.txt

mkdir -p "$POC/tools/pnpm"
"$POC/tools/node/bin/node" "$POC/tools/node/lib/node_modules/npm/bin/npm-cli.js" install --prefix "$POC/tools/pnpm" pnpm@10.34.0 --ignore-scripts --no-audit --no-fund --registry=https://registry.npmjs.org

"$POC/tools/node/bin/node" -v
"$POC/tools/node/bin/node" "$POC/tools/pnpm/node_modules/pnpm/bin/pnpm.cjs" -v

"$POC/tools/node/bin/node" "$POC/tools/node/lib/node_modules/npm/bin/npm-cli.js" view @deepseek-ai/dsh version --registry=https://registry.npmjs.org
"$POC/tools/node/bin/node" "$POC/tools/node/lib/node_modules/npm/bin/npm-cli.js" view @deepseek-ai/dsh versions --json --registry=https://registry.npmjs.org | tail -c 600
```

**把输出贴回**：Node/pnpm 版本、npm view 返回的最新版本（记作 LATEST）。

## 批次 2：安装 LATEST 版 DSH + 能力探测

把命令里的 <LATEST> 替换为批次 1 得到的版本号（例如 0.2.0-rc.2）。

```bash
set -e
POC=/opt/pcl-dsh-linux-poc
LATEST=<LATEST>
RUNTIME="$POC/runtimes/$LATEST"
mkdir -p "$RUNTIME"
printf '%s\n' '{"name":"pcl-dsh-poc-runtime","private":true,"dependencies":{"@deepseek-ai/dsh":"'"$LATEST"'"}}' > "$RUNTIME/package.json"
printf '%s\n' 'autoInstallPeers: true' 'nodeLinker: hoisted' 'resolutionMode: time-based' 'strictPeerDependencies: false' 'dangerouslyAllowAllBuilds: true' > "$RUNTIME/pnpm-workspace.yaml"

cd "$RUNTIME"
NODE="$POC/tools/node/bin/node"
PNPM="$POC/tools/pnpm/node_modules/pnpm/bin/pnpm.cjs"
time "$NODE" "$PNPM" install --prod --no-frozen-lockfile --registry=https://registry.npmjs.org --store-dir="$POC/pnpm-store" --config.ignore-pnpmfile=true --reporter=append-only

"$NODE" -e "const p=require('./node_modules/@deepseek-ai/dsh/package.json');console.log('version',p.version);console.log('bin',JSON.stringify(p.bin))"
DSH_HOME="$POC/dsh-home" DSH_AGENTS_HOME="$POC/agents-home" "$NODE" "$RUNTIME/node_modules/@deepseek-ai/dsh/lib/bin.js" --help 2>&1 | tee "$POC/help-top.txt"
DSH_HOME="$POC/dsh-home" DSH_AGENTS_HOME="$POC/agents-home" "$NODE" "$RUNTIME/node_modules/@deepseek-ai/dsh/lib/bin.js" --profile web --help 2>&1 | tee "$POC/help-web.txt"
```

说明：若入口不是 lib/bin.js，用上一步打印的 bin 路径替换。需要记录：安装耗时、node_modules 体积、top-level help 是否含 --profile，web help 是否含 --port/--host/--no-open。

## 批次 3：启动 / 就绪 / 停止 / 端口 / 内存

```bash
POC=/opt/pcl-dsh-linux-poc
LATEST=<LATEST>
RUNTIME="$POC/runtimes/$LATEST"
NODE="$POC/tools/node/bin/node"
mkdir -p "$POC/dsh-home" "$POC/workspace"
cd "$POC/workspace"
DSH_HOME="$POC/dsh-home" DSH_AGENTS_HOME="$POC/agents-home" nohup "$NODE" "$RUNTIME/node_modules/@deepseek-ai/dsh/lib/bin.js" --profile web --port 3080 --host 127.0.0.1 --no-open > "$POC/dsh-web.log" 2>&1 &
echo $! > "$POC/dsh-web.pid"
sleep 8
echo '=== 启动日志（前 80 行） ==='
head -80 "$POC/dsh-web.log"
echo '=== 端口 ==='
ss -tulpn | grep 3080 || true
echo '=== HTTP/1.1 首页 ==='
curl -s -o /dev/null -w 'code=%{http_code}\n' --http1.1 http://127.0.0.1:3080/
echo '=== 内存 ==='
ps -o pid,ppid,rss,cmd -C node | head -10
free -h
echo '=== token 线索（日志中的 dsh web 行） ==='
grep -a -i 'dsh web\|token\|http://127.0.0.1' "$POC/dsh-web.log" | tail -20
```

**把输出贴回（不要把完整 token 贴出来）**：启动耗时、首页状态码、日志中 token 的**形式**（查询参数/路径/Cookie，可用 ?token=*** 脱敏）、Node 进程 RSS、空闲内存。然后我们决定用哪种方式访问工作台；确认后我会给你批次 4（headers/Cookies/绝对地址/WebSocket 探测）。

随后停止并检查端口释放：

```bash
POC=/opt/pcl-dsh-linux-poc
PID=$(cat "$POC/dsh-web.pid")
kill "$PID" 2>/dev/null || true
sleep 3
kill -9 "$PID" 2>/dev/null || true
sleep 1
ss -tulpn | grep 3080 || echo 'port 3080 released'
```

## 批次 4：工作台访问面探测（等批次 3 后确认）

需要采集（不含 token 原文）：
- 首页响应头：curl -sI --http1.1 http://127.0.0.1:3080/（关注 200/401、Set-Cookie、CSP、X-Frame-Options、Location）。
- HTML 与 JS 中是否含绝对地址：检索 http://127.0.0.1:端口 与形如 /ws 的路径。
- 携带启动 URL（含 token）访问时：是否 302 / 是否下发 Cookie / 后续无 token 请求是否 200。
- WebSocket：检查 HTML/JS 中是否出现 new WebSocket 或 /ws；可用浏览器开发者工具（F12 → Network → WS）确认。
- 401/403 行为：不带 token 的首页、带 token 的首页、访问 /api 类路径。

## 批次 5（可选）：系统 Node 对比

用系统 Node 24.15.0 在同样目录重装一份（或直接用它执行 bin），确认与自带 Node 24.21.0 行为是否一致；仅记录，不改变默认结论（发布包默认自带 24.21.0）。

## 结束与清理（经你确认后）

```bash
# 确认没有进程在跑后执行
rm -rf /opt/pcl-dsh-linux-poc
```

## PoC 结论记录要求

结束时把以下内容记入 M0 报告（我整理）：DSH 版本、Node/pnpm 版本、安装耗时/体积、能力探测结果、启动/就绪/停止是否正常、token 形式与 Web 鉴权路径、WebSocket/绝对地址/CSP 情况、峰值内存、是否触发任何错误。

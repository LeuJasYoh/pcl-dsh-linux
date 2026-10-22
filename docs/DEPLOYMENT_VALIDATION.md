# 部署链路验证记录（WSL Ubuntu 24.04，2026-10-22）

> 目的：在阿里云服务器正式验收前，用 WSL 复现发布包安装链路（install.sh + systemd + Nginx + 自带 Node/pnpm + 静态前端 + DSH 反代），提前发现安装/权限/配置问题。

## 1. 环境

- WSL Ubuntu 24.04 x86_64，systemd（PID 1），root。
- 发布包：pcl-dsh-linux-0.3.0-linux-x64.tar.gz（sha256 见 target/dist）。
- Nginx 1.28.3（apt 安装）；JDK 21；Node/pnpm 使用发布包自带版本。

## 2. 安装步骤与结果

在发布包解压目录内执行：

    bash install.sh

结果：
- 创建系统用户 pcl-dsh，目录 /opt/pcl-dsh-linux/{releases,current,data}。
- systemd 单元安装并 enable/start 成功：active (running)，主进程 java，内存约 347 MB（-Xmx384m）。
- Nginx 站点配置安装成功，监听 0.0.0.0:80；/api/health 经 Nginx 返回 200。
- 初始密码文件生成于 /opt/pcl-dsh-linux/data/initial-password。
- 自带 tools/node（24.21.0）与 tools/pnpm（10.34.0）在 Linux 下可执行；Windows 打包缺失的可执行位与 npm/npx/corepack 符号链接由 install.sh 补齐。

## 3. 端到端浏览器验证（Playwright + Chromium）

- 访问 http://127.0.0.1/ → 登录页 → 初始密码登录 → 强制改密 → 首页。
- 启动已有实例（运行时 0.2.1-rc.2 已安装）→ LAUNCH_INSTANCE Job SUCCEEDED。
- 打开 /workbench/{id}/ → DeepSeek Harness 工作台完整渲染；静态资源与插件模块 200；WebSocket ws://<host>/workbench/{id}/api/remote.mux 建立成功。
- 页面无 JS 异常；仅登录前 1 条 /api/state 401 控制台噪音（前端未登录时仍在轮询，待优化）。

## 4. 发现并修复的问题

1. Windows 打包的 tar 包无法保留 Linux 可执行位与符号链接：install.sh 安装时 chmod node 并补齐 npm/npx/corepack 链接（已修）。
2. Nginx 静态资源扩展名正则 location 会抢先匹配 /workbench/.../assets/*.js：/api 与 /workbench 改为 ^~ 前缀（已修）。
3. WebSocket 同源校验：Host 头无端口时 Spring 返回 port=0（而非 -1），按 Origin 默认端口归一化（已修）。
4. install.sh 内部等待 /api/health 通过，systemd 启动与 Nginx 配置顺序无问题。

## 5. 未覆盖（留阿里云服务器 M4）

- 云安全组/公网 80 端口实测、来源 IP 白名单。
- 服务器内存（1.6 GiB）与 Node 实例峰值内存实测。
- 真实用户数据目录、升级/回滚/备份恢复演练。
- SSH 加固、fail2ban、安全头等加固项。
- 真实整合包导入与账户绑定注入（需要真实 DSH 运行时与凭据/模拟服务）。

## 6. 来源 IP 与反代头（安全）

- 后端 ClientIp 仅在 remoteAddr 为回环时信任 X-Forwarded-For（只影响限流/审计归属）。
- Nginx 模板已改为 **覆盖式** `X-Forwarded-For $remote_addr`（而非追加），避免客户端伪造 XFF 左侧值；生产部署使用本模板即可。

## 7. 已完成的额外验证（2026-10-22）

- **重复安装幂等**：同一版本再次执行 install.sh，服务重启、data/config/launcher-v2.json SHA-256 前后一致，数据不丢。
- **SSE 经 Nginx**：登录后启动实例，连接 /api/jobs/{id}/events 立即收到 state/log 事件（未被 Nginx 缓冲），任务最终 SUCCEEDED。
- **来源 IP 头**：Nginx 模板改为覆盖式 X-Forwarded-For（$remote_addr），避免客户端伪造；后端 ClientIp 仅在回环代理时信任该头。
- **自动化测试现状**：core 104 + server 77 + 前端 Playwright E2E 11 全部通过；npm run build 通过。

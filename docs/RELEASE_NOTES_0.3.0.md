# PCL-DSH-Linux 0.3.0

PCL-DSH-Linux 是 HDSL 桌面版的 Linux WebUI 移植版本：在 Linux 服务器上以 Spring Boot 服务运行，浏览器访问，管理 DeepSeek Harness（DSH）实例、运行时、账户、插件与整合包。

## 功能

- 单管理员密码登录；首次启动生成初始密码并强制修改；会话 + CSRF + 登录限流 + 审计。
- 实例管理：创建/编辑/删除/复制/改版本（复制实现）、启动/停止、单实例运行限制、工作台地址。
- 运行时：npm 版本查询、按精确版本安装、能力探测、删除（引用保护）。
- 账户：提供商预设、API Key 加密存储（Linux AES-256-GCM）、绑定实例并在启动时注入与校验。
- 插件：列表、安装/更新/移除；社区插件目录检索与安装目标核验。
- 整合包：.dspack/Overture 预览、导入（含运行时与依赖恢复、失败回滚 trash）、导出（分享包不含凭据/会话）。
- 任务与日志：Job 队列（忙时 409）、SSE 实时进度/日志、取消；日志尾部查看。
- 设置：代理、npm registry、内置/自定义背景（WebP 优化）。
- DSH 工作台反向代理：/workbench/{id}/ 经 Spring Boot 转发到实例回环端口，支持 token 引导、Cookie 重写、SSE 与 WebSocket；浏览器只访问 Nginx 80。
- 界面全量复刻 HDSL 桌面版（8 页面 + 登录），桌面浏览器最小约 960px。

## 安装（Ubuntu 24.04 x86_64）

前置：JDK 21（或更高）、Nginx、root/sudo；服务器可访问 npm registry、nodejs.org、GitHub（可配代理）。

1. 下载并校验发布包：sha256sum -c pcl-dsh-linux-0.3.0-linux-x64.tar.gz.sha256
2. 解压并安装：

       tar -xzf pcl-dsh-linux-0.3.0-linux-x64.tar.gz
       cd pcl-dsh-linux-0.3.0-linux-x64
       sudo bash install.sh

3. 若未安装 Nginx，先执行 sudo apt update && sudo apt install -y nginx，再重新运行 install.sh。
4. 浏览器访问 http://<服务器IP>/ ，初始密码见 /opt/pcl-dsh-linux/data/initial-password（首次登录后修改）。
5. 首次使用先在“下载”页安装一个 Harness 运行时，然后创建实例并启动。

数据、日志、回收站均在 /opt/pcl-dsh-linux/data；升级/回滚见 docs/DEPLOYMENT_VALIDATION.md。

## 运行要求与限制

- 单用户、单服务器；建议同时最多运行 1 个 DSH 实例（1.6 GiB 内存）。
- 当前 HTTP + IP 访问；公网部署建议云安全组限制来源 IP（应用提供密码登录与 Nginx 限流）。
- DSH 版本以 npm 最新版为验收基线；旧版本可能可用但未实测。
- 移除 Windows 桌面安装包下载功能；不提供多用户/多租户。
- Web 前端需现代 Chromium/Firefox/Edge；不支持手机窄屏适配。

## 许可

GPL-3.0-or-later，含 HMCL 第 7 条附加条款。发布物包含 LICENSE、LICENSES/、THIRD_PARTY_NOTICES.md 与对应源码包；界面保留上游可见版权声明。

## 已知问题

- 安装/运行依赖服务器网络与 Nginx 配置；反向代理与 WebSocket 已在 WSL 与真实 DSH 浏览器验证，正式服务器需按部署清单复测。
- 真实插件安装/原生插件、真实整合包来源站点的可用性仍需在目标环境验证。

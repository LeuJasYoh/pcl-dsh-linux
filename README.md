# PCL-DSH-Linux

PCL-DSH-Linux 是 [HDSL](https://github.com/jiefing/HDSL) 桌面版的 Linux WebUI 移植版本：在 Linux 服务器上以 Spring Boot 服务运行，浏览器访问，负责管理 DeepSeek Harness（DSH）实例、运行时、账户、插件与整合包。

- 目标平台：Ubuntu 24.04 x86_64（headless），Nginx 作为公网入口。
- 前端：Vue 3 + TypeScript，界面全量复刻 HDSL 桌面版。
- 后端：Spring Boot 3.5.x（Java 21），复用 HDSL 0.2.0 的业务层（实例/运行时/账户/整合包/目录）。
- 许可：GPL-3.0-or-later（含 HMCL 第 7 条附加条款）。详见 LICENSE、LICENSES/、THIRD_PARTY_NOTICES.md。
- 代码仓库：<https://github.com/LeuJasYoh/pcl-dsh-linux>
- 部署发行版：[Releases](https://github.com/LeuJasYoh/pcl-dsh-linux/releases)（自包含 Linux x64 tar.gz + 源码包）

## 当前状态

M1–M4 的开发、测试、打包与 Linux 端到端验收已完成，发布包可部署：

- 后端（pcl-dsh-server）：密码登录/强制改密/会话+CSRF/审计、实例 CRUD/启停/复制/改版本、运行时安装探测删除、账户 CRUD 与启动注入、插件与目录、整合包预览/导入/导出、Job+SSE、DSH 工作台反代（HTTP/SSE/WebSocket）；**core 105 + server 78 测试通过**。
- 前端（pcl-dsh-web）：登录、首页、实例、运行时、账户、插件、目录、整合包、任务日志、设置/关于等页面；**Playwright E2E 11 用例通过**。
- Linux 真实链路验收（WSL Ubuntu 24.04）：安装脚本/systemd/Nginx/静态前端/工作台反代/WS/SSE、重复安装、账户注入启动、整合包导出→下载→预览→导入全部通过；详见 docs/DEPLOYMENT_VALIDATION.md、docs/M0_RESULTS.md。
- 发布链：一键生成自包含 Linux x64 部署包（jar + static + Node 24.22.1 + pnpm 10.34.0 + systemd/Nginx/卸载/重置密码脚本 + 源码包；支持 `-NodeTarball` 内置本地 Node 压缩包），见 Releases。
- 已知限制：远程访问时「DeepSeek 账号网页登录」需配合 SSH 隧道（上游 DSH 的 OAuth 回调仅接受回环地址 127.0.0.1）；实例页新增「直连登录」按钮，可一键复制隧道命令与回环直连地址，详见 docs/REMOTE_ACCOUNT_LOGIN.md。
## 实施记录与文档

- 方案与取舍：docs/PORTING_ANALYSIS.md、docs/IMPLEMENTATION_PLAN.md
- 工作台反代设计：docs/DSH_WORKBENCH_PROXY.md
- Linux PoC 与部署验收记录：docs/M0_RESULTS.md、docs/DEPLOYMENT_VALIDATION.md
- 远程账号登录限制与直连登录：docs/REMOTE_ACCOUNT_LOGIN.md
- 开发与测试：docs/DEVELOPMENT.md；发布说明：docs/RELEASE_NOTES_0.3.2.md（历史：0.3.1、0.3.0）

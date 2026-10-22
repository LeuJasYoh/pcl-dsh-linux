# PCL-DSH-Linux

PCL-DSH-Linux 是 [HDSL](https://github.com/jiefing/HDSL) 桌面版的 Linux WebUI 移植版本：在 Linux 服务器上以 Spring Boot 服务运行，浏览器访问，负责管理 DeepSeek Harness（DSH）实例、运行时、账户、插件与整合包。

- 目标平台：Ubuntu 24.04 x86_64（headless），Nginx 作为公网入口。
- 前端：Vue 3 + TypeScript，界面全量复刻 HDSL 桌面版。
- 后端：Spring Boot 3.5.x（Java 21），复用 HDSL 0.2.0 的业务层（实例/运行时/账户/整合包/目录）。
- 许可：GPL-3.0-or-later（含 HMCL 第 7 条附加条款）。详见 LICENSE、LICENSES/、THIRD_PARTY_NOTICES.md。

## 当前状态

项目主体功能已实现并在 WSL Linux 上完成端到端验证：

- 后端（pcl-dsh-server）：密码登录/强制改密/会话+CSRF/审计、实例 CRUD/启动停止/复制/改版本、运行时安装探测删除、账户 CRUD 与启动注入、插件与插件目录、整合包上传预览/导入/导出、背景资源、Job+SSE、DSH 工作台反向代理（HTTP/SSE/WebSocket）；**core 104 + server 77 测试全部通过**。
- 前端（pcl-dsh-web）：登录、首页、实例、运行时/插件目录、账户、插件、整合包、任务日志、设置/关于等页面；Playwright E2E **11 用例通过**。
- 发布链（packaging/scripts）：可生成自包含 Linux 发布包（jar + static + Node 24.21.0 + pnpm 10.34.0 + systemd/Nginx/安装脚本 + 源码包）；在 WSL Ubuntu 上验证了 install.sh/systemd/Nginx/静态资源/工作台反代/WS/SSE/重复安装，详见 docs/DEPLOYMENT_VALIDATION.md 与 docs/M0_RESULTS.md。
- 待完成：在阿里云服务器执行正式部署验收（需要你按 docs/M0_POC_RUNBOOK.md 或发布包内 install.sh 操作），以及真实整合包导入、账户注入等服务器侧验收。

## 当前进度（2026-10-22）

- 基线：多模块工程、core 业务代码迁移、LinuxSecretProtector、Spring Boot 启动类。
- core：104 项测试迁移并通过；RuntimeService 支持发布包只读工具根 + 可写 shim 目录；ProcessService 增加 runningIds()。
- server：密码登录 + 首次登录强制改密 + 会话/CSRF/登录限流/审计；JobManager 单任务 409、取消/超时、SSE 事件流；/api/health、/api/auth/*、/api/state、/api/instances、/api/runtimes、/api/settings；可执行 jar 冒烟通过（health/401/登录/强制改密）。
- web：Vue 3 + TS + Vite + Pinia 工程；AppShell/40px 页头/200px 侧栏/登录页/首页启动区/实例页/设置页/占位页；npm run build 通过。
- 增量 2：server 实例生命周期/启动 Job、运行时安装探测删除、日志接口（core 104 + server 24 测试通过）；web 实例弹窗/启停/任务 SSE/运行时/日志/设置交互；packaging systemd 与 Nginx 模板；真实后端 API 冒烟通过（登录/改密/建实例/选择/web-url 409/删除/版本查询/日志）。
- 增量 3：server 账户/插件/插件目录/背景资源/artifact（core 104 + server 36 测试通过）；web 账户 CRUD/插件管理/插件目录/背景上传/关于许可；真实后端联调冒烟通过（账户 CRUD 与引用 409、插件 Job、目录检索、背景上传与资源读取、artifact 404、无密钥泄漏）。
- 发布链：scripts/package-linux.ps1 可生成 HDSL Web 发布包（jar + static + Node 24.21.0 + pnpm 10.34.0 + systemd/Nginx/安装脚本 + 源码包）；已在 WSL 验证自带 Node/pnpm 可在 Linux 运行。
- 增量 4：DSH 工作台反向代理（HTTP/SSE/WebSocket、token 引导、Cookie/Location 重写、SSRF 白名单）与 Playwright E2E 11 用例；真实 DSH 浏览器验证通过（工作台完整渲染 + WS 连接）。
- 部署链路：发布包安装脚本/systemd/Nginx 在 WSL 验证通过（安装、健康检查、登录、启动实例、工作台反代、WS），记录见 docs/DEPLOYMENT_VALIDATION.md。
- 下一步：真实服务器 M4 验收（需你执行命令）、未登录轮询 401 噪音修复、artifact/账户降级等后端打磨、正式发布包与源码包。
- 下一步：M0 服务器 PoC（等待执行结果）、账户/插件/目录后端、整合包与复制、前端账户/插件/整合包页面、DSH 反代。

## 工程结构

<pre>
pcl-dsh-core/     复用业务层（实例/运行时/账户/整合包/插件目录；纯 Java）
pcl-dsh-server/   Spring Boot Web 服务（REST/SSE/鉴权/反代，开发中）
pcl-dsh-web/      Vue 3 + TypeScript 前端（开发中）
packaging/        systemd、Nginx、安装脚本
scripts/          构建脚本
docs/             方案、部署与使用说明
</pre>

## 构建

需要 JDK 21 与 Maven 3.9.x。Windows 下可使用仓库自带脚本（自动设置 JAVA_HOME/PATH）：

<pre>
powershell -File scripts/mvn.ps1 -B -ntp -DskipTests package
</pre>

或直接使用已配置好的环境执行：

<pre>
mvn -B -ntp -DskipTests package
</pre>

## 参考项目与来源

- HDSL 0.2.0：D:/Projects/DSHL/HDSL（本地参考源，未修改）。
- HMCL 界面移植来源与许可见 [docs/HMCL_PORT.md](docs/HMCL_PORT.md)、LICENSES/HMCL-ADDITIONAL-TERMS.md。

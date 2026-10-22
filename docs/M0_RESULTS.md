# M0 Linux PoC 结果（WSL Ubuntu 24.04 x86_64，2026-10-22）

> 说明：正式 M0 计划在阿里云服务器执行；由于服务器命令需要你执行，先在本机 WSL Ubuntu（同版本用户态、x86_64）完成等价 PoC。结果用于确定反代/运行时/内存参数；服务器上的最终验收留 M4。

## 1. 环境

- WSL Ubuntu、x86_64；JDK 21.0.13；网络可访问 npm registry。
- 使用**发布包**（pcl-dsh-linux-0.3.0-linux-x64）解压到 /tmp/pcl-m0：自带 Node 24.21.0、pnpm 10.34.0、可执行 jar、static。
- 数据目录 /tmp/pcl-m0/data；服务端口 18090。

## 2. 结果摘要

| 项目 | 结果 |
| --- | --- |
| 发布包在 Linux 启动 | 通过（Java 21 启动，约 3 秒） |
| 自带 Node/pnpm 运行 | 通过（node v24.21.0、pnpm 10.34.0 在 WSL 内执行） |
| npm 可用 DSH 版本 | 36 个；**最新 0.2.1-rc.2**（此前文档记录 0.2.0-rc.2） |
| 安装最新运行时 | 成功；pnpm 安装约 4 分钟，运行时目录 508 MB |
| 原生模块 | 已安装 linux-x64 ELF 原生模块（sherpa-onnx / sharp / koffi 等），Linux 依赖正确 |
| 实例启动与就绪 | 成功；web-url 返回 http://127.0.0.1:3080/?token=<一次性 token> |
| 停止实例 | 成功，进程与端口释放 |
| 内存 | Spring Boot（-Xmx384m）RSS 约 366 MB；Node 子进程未单独采样 |

## 3. DSH 工作台访问行为（反代关键结论）

1. **token 形式**：查询参数 token，URL 形如 http://127.0.0.1:3080/?token=<一次性 token>。
2. 带 token 请求：返回 **303 Location: ./**，同时下发 cookie dsh-auth-<随机 8 字符>（HttpOnly、Secure=false、Path=/、host-only）。
3. 仅带 cookie 访问：**200**，token 只用于引导换取 cookie，后续由 cookie 鉴权。
4. 不带 token 且无 cookie：**401**，响应体提示 dsh web authentication required。
5. HTML 特征：包含 base href="./"，所有静态资源均为**相对路径**（./assets/...、plugins/??...、manifest.webmanifest）；**未发现 127.0.0.1 绝对地址**，不需要 HTML/JS 重写。
6. 无 CSP、无 X-Frame-Options。
7. JS 资源分析（约 10 个资源、总计数 MB）：
   - 出现 WebSocket 构造（远程流）；
   - 出现 EventSource/SSE（EVENTS_ROUTE、text/event-stream）；
   - 有相对 /api/ 引用（随 base 解析到 /workbench/{id}/api/...）；
   - 无 ws:// 或 wss:// 硬编码、无绝对回环地址。
8. 结论：**Spring Boot 前缀反代可行**——去掉 /workbench/{id}/ 前缀后转给 127.0.0.1:port 即可；需支持 WebSocket 与 SSE；token 只用于首次 302 引导。

## 4. 反代实现结论

- 浏览器首次 GET /workbench/{id}/（无 cookie/token）→ 后端 302 到 /workbench/{id}/?token=<内存中的启动 token>。
- DSH 返回 303 ./ → 浏览器解析为 /workbench/{id}/，并携带 dsh-auth cookie。
- 后端转发：去掉前缀拼到 127.0.0.1:port；原样转发 Cookie；Set-Cookie 去掉 Domain/Secure、Path 可改为 /workbench/{id}；相对 Location 原样；不缓冲（SSE/流式）；支持 WebSocket 升级；目标白名单仅回环+实例登记端口；要求登录与实例就绪。
- JVM 参数：-Xmx384m 在 Linux 正常；1.6 GiB 内存下保持单实例运行。

## 5. 风险与后续

- WSL 与阿里云服务器在网络/内核/防火墙上有差异；M4 仍需服务器真实验收，但关键未知项（运行时安装、token/Cookie/资源路径/WS/SSE）已消除。
- WebSocket 转发实现后需用真实 DSH 页面验证。
- Node 子进程内存需在正式服务器测量。

## 6. 反代真实浏览器验证（补充，2026-10-22）

在 WSL 后端 + Windows Vite 代理 + Windows Playwright(Chromium) 环境下，用真实 DSH 0.2.1-rc.2 完成端到端验证：

- 登录 HDSL Web → 首页 → 启动实例（LAUNCH_INSTANCE 任务成功）→ 浏览器打开 /workbench/{id}/；
- 工作台标题为 "DeepSeek Harness"，页面完整渲染（侧栏、预览版说明、输入区等），**所有静态资源与插件模块加载成功**（plugins/?? 多模块 URL 经反代 200）；
- 建立 WebSocket：ws://127.0.0.1:5173/workbench/{id}/api/remote.mux（经 Vite → Spring Boot → DSH 转发成功）；
- 页面无 JS 运行时异常；控制台仅 1 条资源 401（登录时序中的无害请求，待后续小修）；
- 截图：.build-tools/proxy-browser.png（本地临时，不入库）。

结论：**HTTP/静态资源/token 引导/Cookie/WebSocket 反代均已在真实 DSH 上跑通**，满足“浏览器远程访问工作台”的核心目标。Nginx 生产链路（静态直出 + /api + /workbench + SSE/WS）将在 M4 服务器验收中复测。

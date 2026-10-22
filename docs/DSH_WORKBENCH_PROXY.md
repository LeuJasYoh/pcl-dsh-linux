# DSH 工作台反向代理设计（草稿，待 M0 结果定稿）

> 状态：**M0 已在 WSL 完成（见 docs/M0_RESULTS.md）**，token/Cookie/相对路径/WS/SSE 行为已确认，进入实现。
> 目标：浏览器只访问 Nginx 80，工作台路径 /workbench/{instanceId}/ 由 Spring Boot 反代到 127.0.0.1:<实例端口>，且必须登录。

## 1. 已知事实与待验证项

已知（来自 HDSL 0.2.0 源码）：
- 实例只绑定 127.0.0.1（RuntimeService.assembleLaunch 追加 --host 127.0.0.1）。
- 启动地址由子进程 stdout 打印、以 “dsh web:” 为标记，ProgressService.bootstrapAddress 会提取并只保存在内存（ProcessService.webAddresses）。
- 新版未带启动 token 的首页可能返回 401；启动早期可能短暂 404，不能仅凭状态码判断就绪。
- 启动 URL 可能是查询参数、路径或 Cookie 形式（M0 必须确认）。

M0 必须回答：
1. token 在 URL 中的位置（query/path/header/Cookie）与有效期。
2. 工作台是否使用 WebSocket、其路径与子协议。
3. 页面/JS 是否包含 http://127.0.0.1:<port> 绝对地址或绝对路径（/assets 等）。
4. Set-Cookie 的 Domain/Path/Secure/SameSite 属性；CSP 与 X-Frame-Options。
5. 401/403 引导流程与重定向行为。

## 2. 首选架构

    Browser
      |  /workbench/{id}/...   (HDSL 会话 + CSRF/Origin)
    Nginx  (80, 反代到 127.0.0.1:8080)
      |
    Spring Boot /workbench/{id}/**  (WorkbenchProxyController)
      |  目标白名单：127.0.0.1 + 实例登记端口
    DSH 工作台 127.0.0.1:<port>

关键点：
- 鉴权：/workbench/** 属于受保护路径；复用现有 Spring Security 会话，不额外做 DSH 用户体系。
- 目标解析：由实例 id 查 InstanceStore 得端口；运行中且状态为“运行中”才允许；拒绝任意 host/ip（防 SSRF）。
- bootstrap：ProcessService 新增 bootstrapUrl(id) 返回内存中的启动 URI；代理在首次访问时把浏览器 302 到 /workbench/{id}/ + 启动 URI 的 path/query（保留 token），或者直接由后端请求并注入 token（取决于 M0 结论）。
- Cookie：Set-Cookie 去除 Domain/Secure，Path 重写为 /workbench/{id}；保留 HttpOnly/SameSite，避免 DSH 的 127.0.0.1 Cookie 被浏览器拒绝。
- Location：把指向 127.0.0.1:<port> 的重定向改写为 /workbench/{id}/...。
- 静态/绝对路径：若 HTML/JS 使用绝对路径且不兼容子路径，M0 后决定是否做有限重写（仅 text/html，限制大小、只替换回环地址），或改用独立子域/端口映射（需要重新决策）。
- WebSocket：独立路径转发；实现方式优先级：Spring WebSocket 服务端 + JDK HttpClient WebSocket 客户端透传；若复杂度过高，回退到 Nginx 直连（需重新评估 token 与鉴权）。
- 超时：普通请求 60s；SSE/WS 长连接不设短超时；实例停止时主动断开并返回 502。
- 日志：/workbench/** 访问日志关闭或过滤查询串；应用日志不记录 token/Cookie 原文。

## 3. 实现拆分（建议）

1. core：ProcessService 暴露 bootstrapUrl(id) / runningIds()（后者已存在）。
2. server：
   - WorkbenchProxyController：路由、鉴权已由 Security 处理；实例校验；HTTP 方法转发；请求头白名单与 body 流式转发。
   - ProxyTargetResolver：实例 id → 回环 URI（白名单）。
   - ResponseRewriter：Location/Set-Cookie（必要时 HTML 重写）。
   - WebSocket 转发器（按 M0 结论）。
   - 失败映射：实例未运行 409、未就绪 409、上游断开 502、超时 504。
3. 测试：
   - 本地 mock DSH（Node/Python 小服务）：token 查询参数 + Set-Cookie + 绝对地址 + WebSocket echo；覆盖 302、Cookie 重写、WS 透传、实例停止 502。
   - 真实服务器：M0 批次 4 通过后，在 /workbench 下打开工作台并完成一次页面操作。
4. 前端：/api/instances/{id}/web-url 改为返回 /workbench/{id}/ 相对路径（后端配置 proxy enabled 时）；“打开 Web” 新标签打开该路径；直连模式（本机开发）仍可返回回环绝对地址。

## 4. 回退方案

若 M0 证明工作台强依赖绝对地址或无法安全注入 token：
- 方案 A：Nginx 为每个实例生成 location（Spring Boot 动态写配置 + reload），一次性 token URL 由后端签发；代价：Nginx 配置动态化、鉴权分散。
- 方案 B：把实例端口直接映射到公网（不推荐；需重新评估安全与鉴权）。
- 方案 C：仅在单机 localhost 场景开放直连（退回 WebUI 仅本机使用），远端部署不承诺“打开工作台”。

任一回退都需在实施计划中重新评估里程碑与风险（R1）。

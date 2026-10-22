# pcl-dsh-web

PCL-DSH-Linux 的 Web 前端（Vue 3 + TypeScript + Vite + Vue Router + Pinia），
用于全量复刻 HDSL 桌面版界面。方案见 `../docs/IMPLEMENTATION_PLAN.md` §5。

## 开发

```bash
npm install
npm run dev        # http://127.0.0.1:5173，/api 与 /workbench 代理到 127.0.0.1:8080
npm run build      # vue-tsc --noEmit && vite build，产物在 dist/
npm run preview    # 预览构建产物
```

需要 Node.js >= 20.19（本机 Node 24 已验证）。

## 结构

```
src/
  api/          client.ts（fetch 封装、CSRF、401 处理、FormData）、types.ts、normalize.ts、index.ts
  components/   AppShell/PageHeader/SideNav/FooterBar/AppIcon、首页启动区与任务卡、
                BaseModal/ConfirmDialog/InstanceDialog、TaskProgressStrip
  router/       8 个业务路由 + /login，登录守卫
  stores/       auth / appState / instances / settings / notify / jobs（任务 + SSE）
  styles/       tokens.css（HMCL 蓝调色板与度量）、base/layout/components.css
  utils/        display/backgrounds/errors/validation/clipboard/jobs
  views/        Login/Home/Instances/Runtimes/Accounts/Plugins/Packs/Logs/Settings
  assets/       backgrounds/whale-01..05.png、icons/hdsl.png（桌面版原始资源）
```

## 视觉与交互约定

- 页头 40px（主色 #5C6BC0）、侧栏 200px、首页启动按钮 200x55、按钮切换箭头 200ms 旋转。
- 正文 13px；字体栈 Microsoft YaHei UI / PingFang SC / Noto Sans CJK SC / Source Han Sans SC。
- 8px 滚动条；页面最小宽度 960px，小于 960px 时横向滚动，不做响应式改造（Q14）。
- 窗口按钮保留位置但 disabled，悬停提示「浏览器不支持窗口操作」。
- 背景图 cover + 右对齐（内置背景），非首页叠加半透明底色，与桌面版一致。
- 弹窗自研轻量组件：焦点陷阱、ESC 关闭、aria-modal，不引入 UI 框架。

## 任务与 SSE（方案 §4.3 / §5.3）

- 提交长任务（启动实例 / 安装运行时 / 探测能力）后，后端返回 202 Job View；
  `stores/jobs.ts` 记录 jobId 并订阅 `GET /api/jobs/{id}/events`。
- 事件：`state` / `log` / `result` / `error`；按 seq 去重，保留最近 4000 行。
- 使用浏览器原生 `EventSource`：断线自动重连时会携带 `Last-Event-ID`，服务端从该 seq 续传；
  连接反复失败时提示并提供「重新连接」。
- jobId 写入 localStorage，页面刷新后通过 `GET /api/jobs/{id}` 恢复快照并重新订阅；
  刷新到已结束任务时会重放日志后关闭连接。
- 任务进行中：首页任务卡显示阶段/已用时/日志尾部，页头与底部状态同步；空闲轮询 1.5s，任务期间降为 3s。
- 取消通过 `POST /api/jobs/{id}/cancel`；日志页支持清空显示、复制（Clipboard API 不可用时弹出手动复制回退）。

## 后端契约与优雅降级

- 认证：`GET /api/auth/me`、`POST /api/auth/login`、`POST /api/auth/logout`、`PUT /api/auth/password`。
- 非 GET 请求从 `XSRF-TOKEN` Cookie 读取并放入 `X-XSRF-TOKEN` 头；所有请求带 `credentials: include`。
- 读取：`GET /api/state`、`/api/instances`、`/api/runtimes`、`/api/runtimes/available`、
  `/api/settings`、`/api/logs/tail?lines=800`、`/api/jobs/{id}`。
- 写入：实例 create/update/delete/selection/account/launch/stop、`GET /api/instances/{id}/web-url`、
  运行时 install/probe/delete、`PUT /api/settings`、`POST /api/jobs/{id}/cancel`（详见方案 §4.2）。
- 后端未实现（404/405/501）或不可达时，页面显示明确的「接口尚未实现 / 加载失败」提示与重试按钮，
  绝不伪造实例、账户、日志或任务数据；轮询失败退避到 5 秒并自动恢复。
- 登录失败区分 401（密码错误）、423（限流锁定）、403 `PASSWORD_CHANGE_REQUIRED`（强制修改初始密码）。

## 本增量（M2 第二增量）完成度

- 实例：创建/编辑（名称、端口、账户；版本与 profile 只读并提示「下一版本提供」）、删除（确认后移入回收目录，
  展示 trashedPath）、选择当前实例、启动/停止、打开 Web（仅后端确认就绪时可用，新标签页）、状态徽标。
- 运行时：已安装列表与兼容性摘要、可用版本列表（刷新/手输/安装）、检测、移除（引用中 409 有中文提示）。
- 任务：全局 job store、首页任务卡（阶段/已用时/日志尾部）、任务页 SSE 日志、取消、复制回退、刷新恢复。
- 日志页：任务实时日志（仅在底部时自动滚底）、清空显示、复制、取消、重新连接、后端 tail 日志与失败提示。
- 设置：代理/Registry 可编辑并保存（与桌面版一致的中文校验文案）、5 张内置背景选择并保存；
  未保存编辑不会被 1.5s 轮询覆盖；自定义背景上传在接口未实现时明确提示「开发中」。
- 账户：读取 `/api/state` 的 accounts 展示名称/提供商/模型/密钥有无与状态；新增/编辑给出「下一增量提供」提示。

## 与桌面版的已知偏差

1. 桌面版状态文本位于标题栏；Web 同时提供底部 24px 状态栏（任务/后端连接/版本/许可），更适合浏览器。
2. 窗口拖动/缩放/最大化/关闭按钮置灰；「打开文件夹」动作不提供，实例路径改为只读展示。
3. 自定义背景上传依赖 `POST /api/settings/background`（后端尚未实现），当前明确提示开发中；内置背景可选可保存。
4. 「打开 Web」使用后端返回的地址：本增量后端返回 `http://127.0.0.1:<port>/` 回环地址（反代增量后改为
   `/workbench/{id}/`）；服务器部署在远端时该地址无法直连，需等反代增量。
5. 复制实例、账户新增/编辑/删除/绑定、插件页、整合包页仍在后续增量；按钮禁用或点击后给出明确说明。
6. 内置背景仍为桌面版原始 PNG（合计约 7.3 MB），WebP/缩略图优化（方案 §5.7）待后续。

## 待办

- Playwright 验收用例（方案 §5.4 的 15 条）与桌面版截图对照。
- 反代增量接入后，将「打开 Web」切换为 `/workbench/{id}/` 并处理远端部署。
- 实例复制、账户管理、插件页、整合包页交互（M3）。
- 内置背景转 WebP/缩略图（首屏资源目标 < 2 MB）。

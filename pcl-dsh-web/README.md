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
  api/          client.ts（fetch 封装、CSRF、401 处理）、types.ts、normalize.ts
  components/   AppShell/PageHeader/SideNav/FooterBar/AppIcon/... 及首页启动区
  router/       8 个业务路由 + /login，登录守卫
  stores/       auth / appState / instances / settings / notify
  styles/       tokens.css（HMCL 蓝调色板与度量）、base/layout/components.css
  utils/        display/backgrounds/errors
  views/        Login/Home/Instances/Runtimes/Accounts/Plugins/Packs/Logs/Settings
  assets/       backgrounds/whale-01..05.png、icons/hdsl.png（桌面版原始资源）
```

## 视觉与交互约定

- 页头 40px（主色 #5C6BC0）、侧栏 200px、首页启动按钮 200x55、按钮切换箭头 200ms 旋转。
- 正文 13px；字体栈 Microsoft YaHei UI / PingFang SC / Noto Sans CJK SC / Source Han Sans SC。
- 8px 滚动条；页面最小宽度 960px，小于 960px 时横向滚动，不做响应式改造（Q14）。
- 窗口按钮保留位置但 disabled，悬停提示「浏览器不支持窗口操作」。
- 背景图 cover + 右对齐（内置背景），非首页叠加半透明底色，与桌面版一致。

## 后端契约与优雅降级

- 认证：`GET /api/auth/me`、`POST /api/auth/login`、`POST /api/auth/logout`、`PUT /api/auth/password`。
- 非 GET 请求从 `XSRF-TOKEN` Cookie 读取并放入 `X-XSRF-TOKEN` 头；所有请求带 `credentials: include`。
- 读取：`GET /api/state`、`/api/instances`、`/api/settings`；写接口（启动/停止/选择等）按 §4.2 调用。
- 后端未实现（404/501/502/503）或不可达时，页面显示明确的「接口尚未实现 / 加载失败」提示与重试按钮，
  绝不伪造实例、日志或任务数据；轮询失败退避到 5 秒并自动恢复。
- 登录失败区分 401（密码错误）、423（限流锁定）、403 `PASSWORD_CHANGE_REQUIRED`（强制修改初始密码）。

## 与桌面版的已知偏差（本增量）

1. 桌面版状态文本位于标题栏；Web 同时提供底部 24px 状态栏（任务/后端连接/版本/许可），更适合浏览器。
2. 窗口拖动/缩放/最大化/关闭按钮置灰；「打开文件夹」「打开浏览器」类动作改为接口/提示。
3. 本地自定义背景文件无法被浏览器直接读取，暂回退默认内置背景；背景修改与 WebP 压缩待后续增量。
4. 任务卡、日志页、插件/账户/整合包/运行时页面目前为结构占位（任务 SSE、Job API 待后端增量）。
5. 首页「创建实例」在实例为空时跳转实例页并提示创建接口尚未实现（M2 F-5 后续）。

## 待办

- 接入 JobManager/SSE 任务卡与日志页（进度、四项计数、取消、复制回退）。
- 实例创建/编辑/复制/删除弹窗（自研轻量 Modal）。
- 设置页保存与背景选择、上传；账户/插件/整合包/运行时页面交互。
- Playwright 验收用例（方案 §5.4 的 15 条）与桌面版截图对照。
- 内置背景转 WebP/缩略图（方案 §5.7，首屏资源目标 < 2 MB；当前 PNG 合计约 7.3 MB）。

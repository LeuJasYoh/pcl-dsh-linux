# PCL-DSH-Linux 0.3.0 移植实施方案与任务拆解

- **文档版本**：v1.0（草案，待你确认后进入实施）
- **项目命名**：产品名 **PCL-DSH-Linux**（目录同名）；为降低迁移差异，复用代码的 Java 包名保留 com.hdsl.*（不影响 GPL 对修改版本的名称区分要求）。
- **日期**：2026-10-22
- **依据**：①《HDSL → Linux WebUI 移植可行性分析报告》（D:/Projects/DSHL/PCL-DSH-Linux/docs/PORTING_ANALYSIS.md）；② 2026-10-22 方案问答 Q1–Q18 的最终决策；③ HDSL 0.2.0 参考源码（D:/Projects/DSHL/HDSL）。
- **当前状态**：**仅方案文档，不含实现代码**。你确认本文档后，才按第 9 节的里程碑开始编码。
- **目标环境**：阿里云 ECS，Ubuntu 24.04.4 LTS x86_64，2 vCPU / 1.6 GiB 内存 / 40 GB 磁盘 / 2 GiB swap；公网 IPv4 39.107.255.118；HTTP 80 入口。
- **交付物**：PCL-DSH-Linux 0.3.0 自包含发布包（tar.gz）、对应源码包、部署文档与使用说明。

## 0. 文档约定

- 「现有代码」指 HDSL 0.2.0 的 src/main/java 等，行号基于该快照；「新增」指 Web 版新写的代码/组件；「待定」项在 §11 列出。
- 估算单位为「人日」，按 1 名开发 + AI 辅助计算，仅用于排期排序，不构成承诺。
- 许可口径：**GPL-3.0-or-later + HMCL 第 7 条附加条款**；发布物必须附对应源码并保留界面可见版权。
- 本文档中「必须」「禁止」是验收条件，不是建议。

## 1. 目标与范围

### 1.1 目标形态

把 Windows 桌面版 HDSL 0.2.0 改造成**运行在 Linux 服务器上的 WebUI 软件**：

- 后端：Spring Boot（Java 21）承接现有全部业务能力，只监听 127.0.0.1。
- 前端：Vue 3 + TypeScript 重写，界面与交互**全量复刻**桌面版。
- 入口：宿主机 Nginx 监听公网 80，反向代理到 Spring Boot。
- 实例：PCL-DSH-Linux 以 systemd 服务常驻，按需启动 Node DSH 子进程（每实例独立端口）。
- 账户：登录密码控制访问；API Key 用服务器本地主密钥加密存储。
- 单用户、单服务器、最多同时运行 1 个 DSH 实例。

### 1.2 范围内（做什么）

1. **全量功能对齐桌面版**，唯一例外是移除「Windows 桌面安装包下载」。
2. 8 个页面 + 登录页：首页、实例、下载（运行时）、账户、插件、整合包、任务与日志、设置。
3. 后端接口全部实现（含暂缓 UI 的插件管理、社区插件目录、整合包），保证接口可独立使用。
4. 账户绑定与启动注入（复用 AccountOverlay/LaunchBinding 逻辑）。
5. 运行时版本查询、安装、能力探测；实例启动/停止/就绪检测；任务进度与日志。
6. 浏览器限制下的 5 处等价实现（见 §5.5）。
7. 自包含发布包、安装脚本、systemd、Nginx 配置、升级/回滚、备份说明、GPL 合规材料。
8. 测试迁移与新增（单元、集成、Playwright E2E、Linux 验收）。

### 1.3 范围外（明确不做）

- 多用户/多租户、账号体系、数据隔离（Q8：单人单服务器）。
- Windows/macOS 桌面形态；JavaFX 保留（Q11/Q13 明确重写 UI）。
- Docker 分发（Q3 选定自包含目录 + systemd；Docker 可作为以后的可选项）。
- Windows 桌面安装包下载功能（Q9 明确移除）。
- 旧 Windows 数据迁移（Q2 全新开始）。
- 移动端/窄屏适配（Q14 只保证桌面浏览器）。
- 公网 HTTPS/TLS（当前 HTTP + IP；以后有域名/证书再由 Nginx 加）。
- 应用内在线升级（升级=替换发布目录 + 重启，见 §7.8）。

### 1.4 已确认决策（Q1–Q18）

| # | 决策点 | 最终结论 |
| --- | --- | --- |
| Q1 | 部署与访问 | 公网服务器；Nginx 唯一公网入口；Spring Boot 仅 127.0.0.1；DSH 工作台由 Spring Boot 内部反代 |
| Q2 | 数据迁移 | 全新开始，不迁移 Windows 数据 |
| Q3 | 部署方式 | 自包含目录 + systemd，不用 Docker；Nginx 装宿主机 |
| Q4 | 运行时 | 系统 JDK 21；发布包自带 Node 24.21.0 + pnpm 10.34.0；可删 tools/node 回退系统 Node |
| Q5 | 鉴权 | 应用密码登录（全路径鉴权）；默认叠加来源 IP 白名单；密码细节发布前定 |
| Q6 | 使用对象 | 自己 + 少数人，各自部署一套（单用户/台） |
| Q7 | 账户密钥 | 自动生成主密钥文件（0600）+ AES-256-GCM；支持 PCL_DSH_SECRET_KEY 覆盖 |
| Q8 | 多用户 | 不做 |
| Q9+Q10 | 功能范围 | 全量对齐（除 Windows 桌面下载）；后端接口先全做，前端分批 |
| Q11 | 界面 | 全量复刻桌面版，不做视觉改版 |
| Q12 | 许可 | GPL-3.0-or-later + HMCL §7；名称 PCL-DSH-Linux 0.3.0；附源码与可见版权 |
| Q13 | DSH 版本 | npm 最新版为验收基线；旧版本“未实测、尽力支持” |
| Q14 | 适配 | 只保证桌面浏览器，最小约 960px，更窄横向滚动 |
| Q15 | 开发环境 | 本机 JDK 21（你安装中）；Maven 用工作区下载；Linux 验证用 WSL |
| Q16 | 并发任务 | 忙时直接拒绝并提示（与桌面版一致） |
| Q17 | 浏览器限制例外 | 按 5 条默认处理（见 §5.5） |
| Q18 | 同时运行实例 | 最多 1 个；Spring Boot 内存限制 + swap 兜底 |

## 2. 总体架构

### 2.1 架构图

```text
                   公网 IPv4 39.107.255.118:80（云安全组放行）
                                    |
                          [ Nginx (root, 宿主机) ]
                                    |
                 127.0.0.1:8080（仅本机）
                                    |
                  [ Spring Boot / PCL-DSH-Linux 0.3.0 ]
                     |            |              |
        REST/SSE/静态资源     JobManager      认证/会话/限流
                     |            |
                     +------> pcl-dsh-core（复用现有业务层）
                                    |
                     +--------------+----------------+
                     |                              |
        [ Node DSH 实例 127.0.0.1:3080+ ]     [ 数据目录 /opt/pcl-dsh-linux/data ]
        （最多 1 个运行；由 Java 启动）        （配置/实例/运行时/缓存/日志）
```

### 2.2 组件职责

| 组件 | 职责 | 实现 |
| --- | --- | --- |
| Nginx | 公网入口、HTTP 转发、SSE/WebSocket 支持、上传大小限制、限流、访问日志、可选 IP 白名单 | 宿主机 apt 安装，配置在 /etc/nginx/conf.d/pcl-dsh-linux.conf |
| Spring Boot | REST API、会话鉴权、静态资源、Job/SSE、DSH 反代、上传下载、健康检查 | 新增 pcl-dsh-server 模块 |
| pcl-dsh-core | 现有业务层（实例/运行时/账户/整合包/插件/目录/下载），去掉 JavaFX/桌面耦合 | 由现有包迁入，改动见 §3.2 |
| Node DSH 实例 | 每个实例一个 Node 子进程，提供 DSH 工作台 | 现有 RuntimeService/ProcessService 逻辑 |
| systemd | 常驻、崩溃重启、日志、开机自启 | pcl-dsh-linux.service |
| 数据目录 | 所有持久化状态 | /opt/pcl-dsh-linux/data（见 §2.6） |

### 2.3 端口与进程规划

| 端口 | 监听地址 | 用途 | 公网暴露 |
| --- | --- | --- | --- |
| 80 | 0.0.0.0 | Nginx 入口 | 是（安全组） |
| 8080 | 127.0.0.1 | Spring Boot | 否 |
| 3080–65535 | 127.0.0.1 | DSH 实例端口（自动分配，默认 3080 起） | 否（经 Spring Boot 反代） |
| 22 | 0.0.0.0 | SSH 运维 | 是（建议收窄来源 IP） |

- 进程树：systemd → java（pcl-dsh-linux.jar）→ node（DSH 实例）。Java 进程退出时由 systemd 控制组终止全部子进程；应用自身负责优雅停止（现有 ProcessService.stop 逻辑）。
- 服务器上不出现 JavaFX、X11、桌面环境依赖。

### 2.4 请求路径与路由策略

| 路径 | 处理者 | 说明 |
| --- | --- | --- |
| / | Spring Boot（Vue SPA） | 未登录 302 到 /login；静态资源可直接由 Nginx 或 Spring Boot 提供 |
| /login | Spring Boot | 登录页，唯一匿名可访问的页面 |
| /api/** | Spring Boot | REST API，全部需要会话（除 /api/auth/login、/api/health） |
| /api/events、/api/jobs/{id}/events | Spring Boot | SSE，需要会话，禁用 Nginx 缓冲 |
| /workbench/{instanceId}/** | Spring Boot 内部反代 | 代理到 127.0.0.1:<实例端口>；需要 PCL-DSH-Linux 会话 |
| /assets/** | Nginx 或 Spring Boot | 前端静态资源（可加缓存头） |

- 同源策略：所有内容都从同一个 Nginx 源提供，避免跨域与 Cookie 问题。
- SPA 深链接：/instances 等路径回退到 index.html（Spring Boot 控制器或 Nginx try_files）。
- 反代路径前缀 /workbench/ 只允许 GET/POST 等常规方法与 WebSocket 升级，目标白名单仅 127.0.0.1 的实例端口。

### 2.5 认证边界

- 匿名可访问：/login、/api/auth/login、/api/health、登录页静态资源。
- 需要会话：其余全部，包括 /api/**、/api/events、/workbench/**。
- 服务器 SSH 权限独立于 Web 登录；Web 登录只控制浏览器入口。
- 默认叠加来源 IP 白名单（Nginx allow/deny 或云安全组），配置模板见 §7.3；安装时尽量自动采集你的登录来源 IP。
- 密码在 HTTP 下为明文传输：这是你确认过的已知限制，白名单是主要补偿措施；后续如启用 HTTPS，只需在 Nginx 增加证书。

### 2.6 数据目录与磁盘布局

```text
/opt/pcl-dsh-linux/
  current -> releases/0.3.0            # 当前版本（软链接，升级/回滚只切链接）
  releases/0.3.0/
    pcl-dsh-linux.jar                           # Spring Boot 可执行 jar
    static/                            # Vue 构建产物（index.html + assets/）
    tools/node/                        # 自带 Node 24.21.0（linux-x64）
    tools/pnpm/                        # 自带 pnpm 10.34.0
    LICENSE、LICENSES/、THIRD_PARTY_NOTICES.md、HMCL 附加条款
    docs/                              # 部署说明、使用说明、许可来源
  data/                                # 唯一持久化数据目录（不随版本替换）
    config/launcher-v2.json            # 实例清单与设置（沿用现有 schemaVersion=1）
    config/accounts.json               # 账户元数据 + AES-GCM 密文
    config/secret.key                  # 主密钥（0600，首次启动生成）
    config/server.json                 # 服务器设置（绑定地址/端口/会话参数等）
    config/compatibility.json          # 可选：CLI 能力人工声明
    instances/<id>/dsh-home            # DSH_HOME
    instances/<id>/workspace           # 工作目录
    instances/<id>/agents-home         # DSH_AGENTS_HOME
    runtimes/<精确版本>/               # DSH 运行时
    tools/shims/                       # 运行期生成的 pnpm shim 与插件桥（pcl-dsh 用户可写）
    assets/backgrounds/                # 自定义背景资源
    cache/{pnpm-store,registry-metadata,probes,npm,pack-staging,uploads,exports}
    logs/                              # 应用日志（按日）与任务日志
    trash/                             # 删除/失败导入
    backups/                           # 可选：手动备份落点（见 §6.3）
```

- 权限：/opt/pcl-dsh-linux/releases 与 current 由 root 拥有、pcl-dsh 用户只读；data 由 pcl-dsh 拥有（0700）；config 内 secret.key 与 accounts.json 0600。
- 磁盘占用估算：JDK 不在包内；Node+pnpm 约 120 MB；每个 DSH 运行时（含依赖）约 300–700 MB；pnpm store 缓存可能到数百 MB；28 GB 可用空间足够，但需定期清理 trash/cache（§7.9）。

### 2.7 运行时依赖与查找顺序

| 依赖 | 来源 | 版本 | 查找顺序/说明 |
| --- | --- | --- | --- |
| Java | 系统安装 | 21+（服务器实测 21.0.13） | systemd 直接调用 /usr/bin/java；启动时校验 >=21 |
| Node | 发布包自带 | 24.21.0 linux-x64 | 查找顺序：/opt/pcl-dsh-linux/current/tools/node（只读，随版本）→ data/tools/node（可选覆盖）→ 系统 PATH；需在 RuntimeService 增加只读工具根配置（M1 B-11）。服务器 nvm Node 不在服务 PATH，故默认必须自带 |
| pnpm | 发布包自带 | 10.34.0 | 查找顺序同上：current/tools/pnpm → data/tools/pnpm → 系统 PATH；调用方式为 node pnpm.cjs；shim 写 data/tools/shims |
| Nginx | 系统 apt | 1.24+（Ubuntu 24.04 仓库版本） | 部署时安装，需你同意 |
| systemd | 系统自带 | 255 | 直接使用 |

- 启动自检：Java 版本、Node 版本（>=24）、pnpm 是否存在、数据目录可写、端口可用；任一失败在启动日志给出明确中文错误并退出非 0（便于 systemd 重试与排障）。

## 3. 代码组织与构建

### 3.1 仓库/模块结构

在 D:/Projects/DSHL/PCL-DSH-Linux 下建立新工程（参考源码 HDSL 保持不变）：

```text
PCL-DSH-Linux/
  pom.xml                    # 聚合父 POM
  pcl-dsh-core/                 # 纯 Java 业务层（无 Spring/JavaFX/JNA）
  pcl-dsh-server/               # Spring Boot 应用（REST/SSE/鉴权/反代/静态资源）
  pcl-dsh-web/                  # Vue 3 + TS + Vite 前端工程
  packaging/                 # install.sh、pcl-dsh-linux.service、pcl-dsh-linux.conf（nginx）、升级脚本
  scripts/                   # 构建/打包/测试脚本（Windows PowerShell + bash）
  docs/                      # 本方案、部署文档、使用说明、许可说明
  src-archive/ 或 git 历史    # 发布时生成的对应源码包
```

### 3.2 模块与现有代码映射

| 现有包/文件 | 去向 | 处理 | 说明 |
| --- | --- | --- | --- |
| core/Instance、InstanceStore、TaskProgress | pcl-dsh-core | 原样 | 数据格式不变 |
| core/ProcessService | pcl-dsh-core | 小改 | 增加进程组/停止增强、单运行实例约束由上层控制 |
| core/Controller | pcl-dsh-core + pcl-dsh-server | 拆分 | 业务保留在 service；去掉 java.awt.Desktop、UI 轮询、JavaFX 依赖；REST 层新建 |
| runtime/RuntimeService | pcl-dsh-core | 小改 | 保留安装/探测/启动命令/环境注入；PATH/工具发现按 §2.7；超时与取消对接 Job |
| runtime/Capabilities、SemVer、PluginService | pcl-dsh-core | 原样 | 纯逻辑 |
| account/AccountService | pcl-dsh-core | 小改 | 默认 protector 换 LinuxSecretProtector；构造器留接缝 |
| account/AccountOverlay、LaunchBinding、SecretProtector | pcl-dsh-core | 原样 | 纯逻辑；密文格式由新 protector 决定 |
| account/WindowsSecretProtector | 删除/归档 | 移除 | Windows 专用；替换为 LinuxSecretProtector |
| download/PluginCatalogService | pcl-dsh-core | 原样 | 纯 HTTP/JSON |
| download/DesktopDownloadService | 归档 | 不迁移 | Windows 桌面下载功能移除（保留源码在参考仓库） |
| pack/PackService、SafeArchive、TarSafety、PackInspection | pcl-dsh-core | 原样 | 纯 Java；新增上传/下载适配 |
| ui/UiState、UiActions | pcl-dsh-server/api（原包名可保留） | 迁移 | 作为 API DTO 与服务边界；后续按资源拆分 |
| ui/ShellView、ui/hmcl/**、BackgroundCatalog、HdslApplication、Main | 不迁移 | 重写 | Vue 前端 + Spring Boot 入口；HMCL 视觉规则作为复刻参考 |
| 旧 Swing src/com/pcl-dsh/Launcher.java | 不迁移 | 参考 | 不参与构建 |
| src/main/resources 的 CSS/图片 | pcl-dsh-web/assets | 转换 | 视觉值手工转 CSS；背景图转 WebP/缩略图 |
| scripts/*.ps1（Windows 打包） | scripts/ | 重写 | 改为跨平台构建 + Linux 发布包脚本 |

### 3.3 依赖调整

| 类型 | 处理 |
| --- | --- |
| org.openjfx:javafx-controls、javafx-swing | **移除**（不再有 JavaFX） |
| net.java.dev.jna:jna-platform | **移除**（DPAPI 不再使用） |
| com.fasterxml.jackson.core:jackson-databind | 保留（升级到 Spring Boot 管理的兼容版本） |
| org.yaml:snakeyaml、org.apache.commons:commons-compress | 保留 |
| org.junit.jupiter:junit-jupiter | 保留（由 spring-boot-starter-test 统一管理） |
| 新增 spring-boot-starter-web | REST、静态资源、内嵌 Tomcat |
| 新增 spring-boot-starter-security | 登录/会话/CSRF/密码哈希 |
| 新增 spring-boot-starter-validation | 请求校验 |
| 新增 spring-boot-starter-test、@playwright/test（前端） | 测试 |
| 不引入数据库 | 继续使用 JSON 文件 + 文件锁，降低部署复杂度 |

- Spring Boot 版本：实施首日锁定为当时的 3.x 稳定版（Java 21 兼容），并在 README 记录确切版本；Maven 3.9.x 由构建脚本下载到工作区。
- HTTP 客户端：优先使用 JDK HttpClient（现有代码已在用）实现 DSH 反代与下载；WebSocket 代理在 M0 验证后确定实现方式（见 §4.6 与风险 R2）。

### 3.4 前端工程结构

```text
pcl-dsh-web/
  index.html
  vite.config.ts
  tsconfig.json
  src/
    main.ts
    App.vue
    router/            # 8 路由 + /login + 未登录守卫
    stores/            # Pinia：state/instances/runtimes/accounts/plugins/packs/logs/settings/jobs/auth
    api/               # 类型化 API 客户端（fetch 封装、错误映射、SSE 封装）
    components/        # AppShell、SideNav、PageHeader、TaskPanel、LogViewer、Modal 等
    views/             # Home/Instances/Runtimes/Accounts/Plugins/Packs/Logs/Settings/Login
    styles/            # 复刻桌面版的 CSS（变量、布局、组件样式）
    assets/            # 背景图（WebP）、图标、字体栈
  tests/               # Vitest 组件测试
  e2e/                 # Playwright 验收用例（对齐 UiAcceptanceTest 15 条）
```

- 不引入大型 UI 框架，避免视觉偏离「全量复刻」；表单与对话框自研轻量组件。
- 构建产物输出到 pcl-dsh-web/dist，打包时复制到 pcl-dsh-server 静态资源或发布包 static/。
- 开发模式：Vite dev server 代理 /api、/workbench 到本机 8080，避免跨域。

### 3.5 构建流水线

| 阶段 | 命令/脚本 | 环境 |
| --- | --- | --- |
| 后端编译与测试 | scripts/mvn.ps1（下载 Maven 到 .build-tools 后执行 mvn -B -ntp verify） | Windows JDK 21 |
| 前端依赖与构建 | pnpm install（或 npm ci）、pnpm build | 本机 Node 24.x |
| 前端 E2E | pnpm exec playwright test（首次需安装浏览器） | Windows |
| Linux 单元/集成验证 | WSL Ubuntu 中执行 mvn test / 运行时集成 | WSL（稍后安装） |
| Linux 发布包 | scripts/package-linux.ps1：下载 Node linux-x64（校验 SHA-256）、组装 tar.gz、生成源码包与校验和 | Windows（或 WSL） |
| 部署 | 上传 tar.gz → 服务器执行 install.sh（我写命令、你执行） | 服务器 |

- 发布包命名：pcl-dsh-linux-0.3.0-linux-x64.tar.gz（含版本、平台、SHA-256 文件）。
- 源码包：pcl-dsh-linux-0.3.0-source.tar.gz，包含本次全部源码/脚本/文档/许可，满足 GPL 对应源码要求。

### 3.6 发布包结构

```text
pcl-dsh-linux-0.3.0-linux-x64/
  install.sh                 # 安装/升级（幂等，检测 root、Java、Nginx）
  uninstall.sh
  pcl-dsh-linux.service               # systemd 单元模板
  nginx-pcl-dsh-linux.conf            # Nginx 站点配置模板（含 IP 白名单占位）
  VERSION
  LICENSE、LICENSES/、THIRD_PARTY_NOTICES.md
  docs/使用说明.md、docs/部署说明.md、docs/许可与源码说明.md
  release/
    pcl-dsh-linux.jar
    static/
    tools/node/              # Node 24.21.0
    tools/pnpm/              # pnpm 10.34.0
  SHA256SUMS
```

### 3.7 版本与升级

- 版本号：0.3.0（名称 PCL-DSH-Linux，用于区分 HMCL 原版与 HDSL 桌面版的修改版本）。
- 数据 schema 不变：launcher-v2.json 仍为 schemaVersion=1；accounts.json 增加 protector/kdf 标识，向后兼容读取（无该字段按新格式处理）。
- 升级：解压新版本到 releases/<新版本> → 切换 current 软链接 → systemctl restart pcl-dsh；数据目录不动。
- 回滚：切回旧版本软链接并重启；若数据 schema 已升级，回滚前按 §7.8 备份说明处理。

## 4. 后端设计（pcl-dsh-core + pcl-dsh-server）

### 4.1 分层与包结构

```text
pcl-dsh-core/（纯 Java，不依赖 Spring/JavaFX）
  com.hdsl.api          UiState/UiActions 迁移为 DTO 与动作常量（可保留原接口形态）
  com.hdsl.core         Instance、InstanceStore、ProcessService、TaskProgress、Controller 拆出的服务
  com.hdsl.runtime      RuntimeService、Capabilities、PluginService、SemVer、PluginEntry
  com.hdsl.account      AccountService、LinuxSecretProtector、AccountOverlay、LaunchBinding、SecretProtector
  com.hdsl.pack         PackService、SafeArchive、TarSafety、PackInspection
  com.hdsl.download     PluginCatalogService（DesktopDownloadService 不迁移）

pcl-dsh-server/（Spring Boot）
  com.hdsl.server.api         REST 控制器（按资源分组）
  com.hdsl.server.job         JobManager、Job、SSE 事件、取消、超时
  com.hdsl.server.security    登录、会话、CSRF、限流、IP 白名单、审计日志
  com.hdsl.server.proxy       DSH 工作台反向代理（HTTP + WebSocket）
  com.hdsl.server.upload      分片/流式上传、临时文件、产物下载
  com.hdsl.server.state       DTO 组装与状态枚举映射
  com.hdsl.server.config      Spring 配置：数据根、运行时路径、超时、内存参数
```

分层规则：
- Controller 只做鉴权后的参数校验、调用 service/job、返回 DTO；不直接操作文件或进程。
- pcl-dsh-core 的类保持现有语义（安装、启动、清单、账户），不引入 Spring 注解，便于单测与复用。
- 所有长任务统一由 JobManager 执行，service 层通过回调上报日志/阶段（沿用现有 Consumer<String> log 模式）。

### 4.2 REST API 设计

**认证**

| 方法 | 路径 | 请求 | 响应 | 说明 |
| --- | --- | --- | --- | --- |
| POST | /api/auth/login | {password} | 204 + 会话 Cookie + CSRF Cookie | 唯一匿名的写接口；失败 401，连续失败锁定 |
| POST | /api/auth/logout | — | 204 | 销毁会话 |
| GET | /api/auth/me | — | {authenticated, csrfToken, version, initialPasswordPending} | 前端启动时探测登录态 |
| PUT | /api/auth/password | {oldPassword, newPassword} | 204 | 修改密码；首次登录强制修改（若初始密码由系统生成） |
| GET | /api/health | — | {status, version, uptime} | 唯一匿名的读接口，监控用，不含敏感信息 |

**读取（全部需登录）**

| 方法 | 路径 | 响应 | 说明 |
| --- | --- | --- | --- |
| GET | /api/state | 聚合快照 | currentInstanceId、settings、instances、runtimes、accounts、availableVersions、status、catalog 摘要 |
| GET | /api/instances | Instance[] | 含状态、profile、port、profiles[]、accountId、path（仅登录可见） |
| GET | /api/instances/{id} | Instance | 单实例 |
| GET | /api/instances/{id}/web-url | {url} | 仅运行中且已就绪；返回反代地址（或短时 token URL，见 §4.6） |
| GET | /api/instances/{id}/plugins | Plugin[] | 只读；代替“刷新插件” |
| GET | /api/runtimes | Runtime[] | 已安装版本 + 兼容性摘要 |
| GET | /api/runtimes/available | {versions[]} | npm 版本列表（60s 超时） |
| GET | /api/accounts | Account[] | 公开元数据，无密钥 |
| GET | /api/settings | Settings | proxy、registry、background（资源 id）等 |
| GET | /api/logs/tail?lines=800 | {lines[]} | 环形缓冲日志尾部 |
| GET | /api/plugin-catalog?q=&page=&refresh= | CatalogPage | 社区插件目录（接口先实现） |
| GET | /api/plugin-catalog/{owner}/{repo}?refresh= | PluginDetails | 安装目标解析（接口先实现） |
| GET | /api/jobs | Job[]（最近 N 个） | 任务历史 |
| GET | /api/jobs/{id} | Job | 单任务状态 |
| GET | /api/jobs/{id}/events | text/event-stream | SSE：state/log/result/error |
| GET | /api/artifacts/{id} | 文件流 | 下载导出产物（.dspack、实例目录打包等） |

**写入（全部需登录 + CSRF）**

| 方法 | 路径 | 请求 | 返回 | 类型 |
| --- | --- | --- | --- | --- |
| POST | /api/instances | {name,version,profile,port?,accountId?} | 201 Instance | 同步 |
| PUT | /api/instances/{id} | {name,version,profile,port?,accountId?} | 200 Instance | 同步（换版本会复制，见现有逻辑） |
| DELETE | /api/instances/{id} | — | 200 {trashedPath} | 同步 |
| POST | /api/instances/{id}/copy | {name} | 202 Job | 长任务（导出+导入） |
| PUT | /api/instances/{id}/selection | — | 200 | 同步 |
| PUT | /api/instances/{id}/account | {accountId} | 200 | 同步（要求实例已停止） |
| POST | /api/instances/{id}/launch | — | 202 Job | 长任务（安装+启动+就绪） |
| POST | /api/instances/{id}/stop | — | 200 | 同步（进程树停止） |
| POST | /api/instances/{id}/plugins | {spec} 或 {repo,spec} | 202 Job | 长任务（pnpm/CLI） |
| PATCH | /api/instances/{id}/plugins/{name} | — | 202 Job | 长任务 |
| DELETE | /api/instances/{id}/plugins/{name} | — | 202 Job | 长任务 |
| POST | /api/runtimes | {version} | 202 Job | 长任务（pnpm 安装，20 分钟上限） |
| POST | /api/runtimes/{version}/probe | — | 202 Job | 长任务（35/60s 探测） |
| DELETE | /api/runtimes/{version} | — | 200/409 | 同步（有实例引用时 409） |
| POST | /api/accounts | {name,provider,baseUrl,model,apiKey} | 201 Account | 同步 |
| PUT | /api/accounts/{id} | 同上（apiKey 可空=保留） | 200 Account | 同步 |
| DELETE | /api/accounts/{id} | — | 200/409 | 同步（被实例引用时 409） |
| PUT | /api/settings | {proxy,registry,background} | 200 | 同步 |
| POST | /api/packs/preview | multipart 文件 | 200 Preview + previewId | 同步/中任务（检查） |
| POST | /api/packs/import | {previewId,name?,version?} | 202 Job | 长任务 |
| POST | /api/packs/export | {instanceId,mode:profile/home} | 202 Job（结果 artifactId） | 长任务 |
| POST | /api/jobs/{id}/cancel | — | 202 | 取消当前任务 |
| GET | /api/instances/{id}/export-dir | 文件流 | 202 Job → artifact | 可选：实例目录打包下载（替代“打开文件夹”） |

- 所有同步写操作在全局任务忙时返回 409 CONFLICT_BUSY（Q16）；长任务返回 202 + jobId。
- 删除语义保持桌面版：实例/运行时移入 trash，不物理删除。
- 桌面版“打开文件夹/打开浏览器”类动作移除；由前端使用 /web-url、/artifacts 替代。
- 参数校验沿用现有规则：实例名 1–160、版本号格式、profile 规则、端口 1024–65535（InstanceStore.java:121-124）。

### 4.3 JobManager 与 SSE

**任务状态机**：RUNNING →（SUCCEEDED | FAILED | CANCELLED）。不设排队队列（Q16：忙时直接拒绝）；同一时间仅 1 个变更任务。

| 任务类型 | 说明 | 超时 |
| --- | --- | --- |
| INSTALL_RUNTIME | pnpm 安装 DSH 运行时 | 20 分钟（RuntimeService.java:209） |
| LAUNCH_INSTANCE | 安装（如需）+ 能力探测 + 启动 + 90s 就绪 | 20 分钟 + 90 秒 |
| IMPORT_PACK / COPY_INSTANCE | 解包 + 安装运行时 + 恢复依赖 + 登记 | 30 分钟（含 20 分钟依赖恢复） |
| EXPORT_PACK | 遍历+打包+自检 | 10 分钟 |
| PLUGIN_MUTATE | Harness CLI + pnpm | 20 分钟 |
| PROBE_RUNTIME | --help 探测 | 90 秒 |
| PREVIEW_PACK | 读取索引+全量校验 | 10 分钟 |
| CATALOG / VERSION 查询 | 发现类，不占全局锁，可并行 | 60 秒 |

**SSE 协议**（per-job 流）：

```text
event: state
data: {"jobId":"j-...","seq":12,"ts":1729...,"state":"RUNNING","phase":"下载与安装依赖","message":"已解析 277 项"}

event: log
data: {"jobId":"j-...","seq":13,"ts":...,"level":"info","line":"[安装进度] ..."}

event: result
data: {"jobId":"j-...","state":"SUCCEEDED","result":{"instanceId":"i-...","version":"0.2.0-rc.2"}}

event: error
data: {"jobId":"j-...","state":"FAILED","code":"UNSUPPORTED_RUNTIME","message":"...","details":{}}
```

- 断线重连用 Last-Event-ID 续传（seq 递增，服务端保留当前任务最近 2000 行日志；其余从日志文件回读）。
- 心跳：每 15 秒发送注释行（冒号 ping），防 Nginx/网络空闲断开。
- 取消：置标志 + interrupt 工作线程（沿用 Controller.java:94,173 的协作式检查）；取消后进程/临时目录清理必须完成，任务终态为 CANCELLED。
- 任务并发与发现类：版本查询、目录查询等发现类任务不占用全局锁（沿用 Controller.java:87-88,137-169 的发现线程模型），但同一发现类同时只允许一个在途请求（合并重复请求）。
- 关闭服务：停止接收新任务 → 取消运行中任务 → 停止所有实例进程 → 关闭文件锁（沿用 close 语义，Controller.java:318-328）。
- 单运行实例约束：launch 前检查除目标外的运行中实例；存在则 409 CONFLICT_RUNNING_INSTANCE（Q18）。

### 4.4 认证与会话

- 技术：Spring Security 6，会话式认证（不使用 JWT，单服务器更简单），BCrypt 密码哈希。
- 登录：POST /api/auth/login JSON；成功后服务端会话 + HttpOnly/SameSite=Lax 的 JSESSIONID；CSRF 采用 CookieCsrfTokenRepository（前端读 XSRF-TOKEN，请求头 X-XSRF-TOKEN）。
- 密码存储：data/config/server.json 的 passwordHash（0600）；不存明文。
- 初始密码：首次启动若未设置密码，生成随机强密码（安全随机 20+ 字符），写入 data/initial-password（0600）并打印到 journal；首次登录强制修改（initialPasswordPending=true）。安装脚本可选传入初始密码（发布前定，见 TBD-1）。
- 会话时长：默认 12 小时滑动过期（发布前可调）；登出销毁会话。
- 防爆破：应用内按 IP 计数，连续 5 次失败锁定 60 秒；Nginx 层再加 limit_req 作为第一道。
- 来源 IP：Nginx 模板默认提供 allow <你的IP>; deny all; 由安装脚本询问/自动采集 SSH 客户端 IP；若临时换网络，改 Nginx 并 reload（§7.3）。云安全组作为外层。
- 代理信任：Spring 只信任来自 127.0.0.1 的 X-Forwarded-* 头；否则忽略，防伪造。
- 审计：登录成功/失败、改密、实例启停、账户增删、设置变更写入 data/logs/audit-YYYY-MM-DD.log（不含密钥/密码）。
- 用户体系：仅一个管理员账号（单用户），不提供注册；忘记密码通过服务器端重置命令（§7.7）。

### 4.5 账户密钥（LinuxSecretProtector）

- 接口沿用 SecretProtector（protect/unprotect），WindowsSecretProtector 替换为 LinuxSecretProtector。
- 主密钥：32 字节随机数；来源优先级：环境变量 PCL_DSH_SECRET_KEY（Base64 或 64 位 Hex）> data/config/secret.key（首次保存账户前自动生成，0600，原子写）。
- 加密：AES-256-GCM，随机 12 字节 IV，密文格式「v1 + Base64(iv || ciphertext || tag)」；accounts.json 中记录 keyId（如 key-1）以便将来轮换。
- 错误处理：密钥缺失/损坏时，账户列表仍可显示，但标记“需重新输入 API Key”，不自动删除旧数据；日志不得出现密钥或明文。
- 轮换（可选，发布前定）：重新加密所有账户并更新 keyId；未实现前文档说明“更换 secret.key 会导致已存 Key 不可用”。
- 备份要求：备份 data 时必须包含 secret.key，否则账户密钥无法恢复（写入使用说明）。

### 4.6 DSH 工作台反向代理

目标：浏览器只访问 Nginx 80，工作台路径 /workbench/{instanceId}/ 由 Spring Boot 反代到 127.0.0.1:<实例端口>，且必须登录。

**M0 必须先验证的上游行为**（决定实现细节，不靠猜）：
1. 启动地址的 token 形式：查询参数、路径、Cookie 或响应头（现有代码只保存在内存，ProcessService.java:100-108）。
2. 工作台是否使用 WebSocket、路径前缀、心跳。
3. 页面/JS 是否包含绝对地址（127.0.0.1:port），是否需要 HTML/JS 重写。
4. 响应头：CSP、X-Frame-Options、Set-Cookie 的 Domain/Path/Secure。
5. 新版 401/403 引导流程（docs/runtime-compatibility.md:35）。

**首选实现（M0 通过后采用）**：
- 路由 /workbench/{id}/** → 校验会话、实例存在且运行/就绪 → 目标仅允许 127.0.0.1 与实例登记端口（SSRF 白名单）。
- token 只在服务端内存；浏览器首次访问 /workbench/{id}/ 时由后端 302 到携带 token 的路径（或由后端注入所需头/Cookie），随后依赖 DSH 自己下发的 Cookie；后端重写 Set-Cookie 的 Domain/Path，剥离 Secure 相关限制（HTTP 场景）。
- Location 重定向改写为 /workbench/{id}/...；必要时仅对 text/html 做有限重写（大小上限、只替换本机地址）。
- WebSocket：升级请求原样转发（M0 确定实现：Tomcat WebSocket 服务端 + JDK HttpClient WebSocket 客户端转发）。
- 超时：普通请求 60 秒；SSE/WS 长连接不设短超时；实例停止/结束时主动断开并返回 502。
- 日志：/workbench/** 访问日志不得记录查询串（token 防泄漏）；应用日志同样脱敏。

**失败兜底（需你重新拍板，作为风险预案）**：若 M0 证明 DSH 工作台无法安全反代（例如强依赖绝对地址/无法注入 token），则改为「Nginx 动态 location + 一次性 token URL」或「把实例端口直接映射到公网」，后者需重新评估安全。该决策点见风险 R2。

### 4.7 文件上传与下载

| 功能 | 设计 |
| --- | --- |
| 整合包上传 | POST /api/packs/preview，multipart 流式落盘到 data/cache/uploads/<uuid>.zip；同时计算 SHA-256；上限 2 GiB；校验失败/取消清理 |
| 预览 | PackService.inspect 返回预览 + previewId（短期 30 分钟），导入时用 previewId 避免重复上传 |
| 导入 | 后台 Job：解包到暂存 → 安装运行时 → 恢复依赖 → 登记实例；失败移入 trash（复用现有 Controller.importPack 语义） |
| 导出 | Job 生成 .dspack 到 data/cache/exports/<uuid>.dspack；完成后 GET /api/artifacts/{id} 下载；产物 TTL 24 小时自动清理 |
| 背景图片 | POST /api/settings/background（≤20 MB，校验图片格式与尺寸），存 data/assets/backgrounds/<uuid>.<ext>，settings 存资源 id/URL；提供缩略图 |
| Nginx | client_max_body_size 2048m；proxy_request_buffering off；上传/下载超时放宽 |
| 安全 | 不使用服务器任意路径参数；上传文件类型/大小/数量限制；临时目录权限 0700；下载设置 Content-Disposition + no-store |

### 4.8 运行时与进程管理

- 安装：复用 RuntimeService.install（npm 元数据 + pnpm 安装 + 版本/入口校验 + 原子移动）；进度通过 Job 日志输出；失败保留诊断目录。
- 能力探测：复用 inspect（--help 35s、profile help 60s、compatibility.json 覆盖）。
- 启动：复用 launchCommand/assembleLaunch（--profile/--port/--host 127.0.0.1/--no-open）+ configureEnvironment（DSH_HOME/DSH_AGENTS_HOME/registry/proxy/PATH）。
- 账户注入：复用 AccountService.prepare + LaunchBinding（--patch + 环境变量 + validation 轮询 20s）。
- 就绪：复用 awaitReady（HTTP/1.1、NO_PROXY、401/403 + 内存 token 判定、90s 上限）。
- 停止：复用 ProcessService.stop（先子孙后根、3s + 4s、端口释放确认）；服务退出时 systemd 控制组兜底终止全部子进程。
- 单运行实例：pcl-dsh-server 在 launch 前检查；运行中实例的 stop 独立可用（不受忙任务限制）。
- 内存参数：Spring Boot -Xmx384m -Xms128m -XX:MaxMetaspaceSize=160m；DSH 实例内存主要由 Node 决定，M0 实测后可在实例环境注入 NODE_OPTIONS=--max-old-space-size=<值>（待定，见 TBD-3）。
- 端口：沿用 1024–65535 校验、3080 起自动分配、连接探测判占用（ProcessService.java:94-99）；实例端口只监听回环。

### 4.9 状态、DTO 与数据兼容

- 首版 API DTO 以 UiState 为基础拆分：
  - StateResponse：currentInstanceId、settings、instances[]、runtimes[]、accounts[]、availableVersions[]、status{code,message}。
  - 独立资源：InstanceResponse、RuntimeResponse、AccountResponse、PluginResponse、JobResponse、CatalogPage、PreviewResponse。
- 状态枚举：NOT_STARTED / STARTING / RUNNING / EXITED / PORT_BUSY / RUNNING_LIMITED；附中文 message，前端按文本原样展示（保持复刻文案）。
- 计划/任务日志：沿用现有中文文案与“进行中：”前缀；后端统一经 SSE/日志接口输出。
- 数据文件兼容：
  - launcher-v2.json 保持 schemaVersion=1；settings.background 新增资源 id 格式，旧绝对路径值按“内置默认背景”处理（本版全新数据，无历史兼容压力）。
  - accounts.json 保持结构，新增 protector/keyId 字段；旧 DPAPI 密文（如未来导入）识别后标记需重新输入，不尝试解密。
  - instances 目录结构不变（dsh-home/workspace/agents-home）。
- 禁止：DTO/日志/SSE 中出现 API Key、密文、token、完整环境变量或密码哈希。

### 4.10 错误模型与脱敏

统一错误响应：

```json
{"code":"CONFLICT_BUSY","message":"当前有任务正在执行，请等待完成或取消。","details":{},"traceId":"..."}
```

| code | HTTP | 场景 |
| --- | --- | --- |
| UNAUTHORIZED | 401 | 未登录/会话过期 |
| FORBIDDEN | 403 | 无权限（单用户下基本不出现，保留） |
| CSRF_INVALID | 403 | CSRF 校验失败 |
| BAD_REQUEST / VALIDATION | 400 | 参数非法（沿用现有中文校验文案） |
| NOT_FOUND | 404 | 资源不存在 |
| CONFLICT_BUSY | 409 | 已有任务在跑（Q16） |
| CONFLICT_RUNNING_INSTANCE | 409 | 已有其他实例在运行（Q18） |
| CONFLICT_IN_USE | 409 | 账户/运行时被引用 |
| UNSUPPORTED_RUNTIME | 422 | 能力探测失败/版本不支持 |
| INSTANCE_NOT_READY | 409 | 工作台未就绪 |
| JOB_CANCELLED / TIMEOUT | 409/504 | 任务终态 |
| INTERNAL | 500 | 未预期错误（详情仅日志） |

- 日志脱敏沿用 ProcessService.redact（ProcessService.java:109-113），并扩展到 HTTP 请求头/查询串/代理日志。
- 每个请求生成 traceId（短 UUID），错误响应与日志可对应；不记录密码、Key、token、Cookie 原文。

## 5. 前端设计（pcl-dsh-web）

### 5.1 页面与路由

| 路由 | 页面 | 内容 | 首版 UI 里程碑 |
| --- | --- | --- | --- |
| /login | 登录 | 密码输入、错误提示、锁定提示 | M2 |
| / | 首页 | 背景、右下启动区（200x55 双行按钮 + 实例切换器 + 箭头动画）、任务卡、页脚状态 | M2 |
| /instances | 实例 | 实例卡片列表、创建/编辑/复制/删除、启动/停止、打开工作台 | M2 |
| /runtimes | 下载（运行时） | 版本列表、安装/探测/删除、安装进度 | M2 |
| /accounts | 账户 | 账户列表、添加/编辑/删除、绑定当前实例 | M2 |
| /plugins | 插件 | 实例下拉、插件列表、添加/更新/移除 | M3 |
| /packs | 整合包 | 上传检查、导入新实例、导出当前 profile/整个实例 | M3 |
| /logs | 任务与日志 | 实时日志、任务状态/取消、复制文本 | M2 |
| /settings | 设置 | 代理/Registry、外观背景、关于与许可 | M2 |

### 5.2 布局与视觉复刻规范

- **外壳**：顶部 40px 标题栏（主色 #5C6BC0），左侧返回首页图标 + 标题（PCL-DSH-Linux 0.3.0 / 页面名），中部任务进度与状态，右侧窗口按钮位置保留但置灰不可用。
- **侧栏**：固定 200px，三组标题与分隔线：「账户」「实例」「通用」；7 个导航项 + 当前实例项；选中态与悬停态按桌面版；侧栏内部可滚动。
- **首页**：背景图 cover + 右对齐（内置背景），主启动按钮固定在右下（200x55、双行文本、切换箭头 200ms 旋转）；任务进行时显示任务卡（阶段、已用时、四项包计数、查看任务）。
- **其他页面**：卡片式布局、统一标题/工具栏/表单/空状态/徽标样式与桌面版一致。
- **字体**：正文 13px，字体栈 "Microsoft YaHei UI", "PingFang SC", "Noto Sans CJK SC", "Source Han Sans SC", sans-serif；日志等宽字体 "Consolas", "Menlo", monospace。
- **CSS 变量**：从 hmcl-root.css / hmcl-blue.css / launcher.css 中提取颜色、间距、圆角、阴影值（主色 #5C6BC0、关闭按钮悬停 #E81123 等），在 pcl-dsh-web/src/styles/tokens.css 集中定义；JavaFX 的 -fx-* 语法必须改写为标准 CSS。
- **滚动条**：8px 细滚动条；日志区独立滚动。
- **窗口尺寸**：页面最小宽度 960px；小于 960px 时横向滚动（不做响应式适配，Q14）。
- **背景**：5 张内置背景 + 自定义上传；缩略图沿用桌面版尺寸；内置背景按 right center 定位，自定义背景居中。
- **版权**：设置/关于页保留「版权所有 © 2013-2026 huangyuhui 及贡献者」、PCL-DSH-Linux 版权、GPL 与 HMCL 附加条款入口；登录页页脚也显示许可入口。

### 5.3 状态管理（Pinia）

| Store | 对应 UiState/接口 | 主要状态 |
| --- | --- | --- |
| auth | /api/auth/me、login/logout | 登录态、csrfToken、需修改密码标记 |
| state | /api/state | currentInstanceId、status、聚合快照、最后刷新时间 |
| instances | /api/instances、实例写接口 | 列表、当前实例、操作中标记 |
| runtimes | /api/runtimes、available | 已安装版本、可用版本、兼容性摘要 |
| accounts | /api/accounts | 账户列表、绑定关系 |
| plugins | /api/instances/{id}/plugins | 插件列表（M3 UI） |
| packs | /api/packs/* | 预览、导入导出进度（M3 UI） |
| logs | /api/logs/tail + SSE | 日志行、任务事件、自动滚动开关 |
| settings | /api/settings | proxy/registry/background |

刷新策略：
- 页面首帧：GET /api/state 一次。
- 空闲轮询：每 1.5 秒 GET /api/state（与桌面版一致，作为 SSE 之外的一致性兜底）；页面隐藏（document.hidden）时暂停，恢复时立即刷新。
- 任务期间：以 SSE 事件为准更新任务状态与日志；轮询继续但降级为 3 秒。
- 未保存表单：轮询/后台刷新不得覆盖正在编辑的设置与实例表单（对应桌面版 ShellView.java:107-108,721-724 的行为）。
- 忙碌态：收到 409 CONFLICT_BUSY 时显示「当前有任务正在执行」，按钮禁用；任务结束（SSE result/error 或轮询发现空闲）后恢复。

### 5.4 交互与验收行为对齐（UiAcceptanceTest 平移）

以下 15 条桌面验收行为在 Playwright 中逐条实现（编号为规划用）：

| # | 行为 | 对应用例 |
| --- | --- | --- |
| 1 | 8 个路由可导航，侧栏 7 个导航项始终存在，960x650 下控件不越界 | e2e/navigation.spec.ts |
| 2 | 未保存的设置/背景选择在后台刷新后不丢失 | e2e/settings-unsaved.spec.ts |
| 3 | 创建实例：名称必填、版本可手输、空端口自动分配、端口校验 | e2e/instance-create.spec.ts |
| 4 | 编辑实例：profile 只读、支持 Unicode 名称、参数正确传递 | e2e/instance-edit.spec.ts |
| 5 | 首次启动显示下载任务卡（阶段/计时/四项计数，不造百分比） | e2e/launch-first.spec.ts |
| 6 | 任务期间可导航/复制日志；取消后任务结束并记录取消 | e2e/task-cancel.spec.ts |
| 7 | 导出先选范围（默认当前 profile），取消不触发导出 | e2e/pack-export.spec.ts |
| 8 | 运行中/启动中/服务未就绪都显示「停止 + 打开工作台」，不显示「启动」 | e2e/launch-states.spec.ts |
| 9 | 空工作空间主按钮显示「创建实例」 | e2e/empty-home.spec.ts |
| 10 | 标题栏 40px、侧栏 200px、启动按钮 200x55（尺寸断言） | e2e/layout.spec.ts |
| 11 | 版权与 GPL/HMCL 两个许可 Tab 可离线查看 | e2e/about-license.spec.ts |
| 12 | 版本查询：创建框打开只查一次、不阻塞保存、失败保留输入、迟到响应不覆盖 UI | e2e/version-query.spec.ts |
| 13 | 背景：5 张内置 + 自定义、保存参数、内置右对齐、缩略图全部解码、重置回默认 | e2e/backgrounds.spec.ts |
| 14 | 账户：必填、provider 预设、密码提交/取消后清空且不进状态、编辑留空保留 | e2e/accounts.spec.ts |
| 15 | 对话框/列表控件边界行为（禁用不触发、空列表、长文本） | e2e/components.spec.ts |

### 5.5 浏览器限制的 5 条例外实现

1. **窗口按钮**：保留标题栏视觉与按钮位置，按钮 disabled + tooltip「浏览器不支持窗口操作」；不实现拖动/缩放/最大化。
2. **文件选择器**：统一封装 FilePicker 组件——选择文件后立即上传（分片/流式），返回 previewId/assetId；导出改为后端生成 → 前端下载链接。
3. **剪贴板**：优先尝试 navigator.clipboard（localhost/HTTPS 才可用），失败时弹出只读文本域并自动全选，提示手动复制。
4. **打开实例文件夹**：按钮改为「显示实例路径」（只读文本 + 复制），并预留 M3 的「打包下载实例目录」入口。
5. **customCommand**：前端不提供入口；后端拒绝执行非空 customCommand 并记录审计日志（新数据永远不会设置该字段）。

### 5.6 可访问性与测试标识

- 把现有节点 id（page-/nav-/instance-/account-/background-/window-/task-/catalog-）映射为 data-testid，保持语义一致，便于 Playwright 定位。
- 主按钮保留可读文本；图标按钮加 aria-label；对话框焦点陷阱与 ESC 关闭；键盘可完成主要流程。
- 颜色对比度不低于桌面版；错误信息同时以文本+颜色表达。

### 5.7 资源与性能

- 背景图转 WebP（保持 1672x941 视觉），生成设置页缩略图；首屏资源目标 < 2 MB（原约 9 MB）。
- 静态资源加缓存（Nginx 或 Spring Boot Cache-Control）；index.html 不缓存，带 hash 的 assets 长缓存。
- 前端路由懒加载；日志长列表虚拟滚动（或限制 DOM 行数）。
- 首屏 API 合并为 /api/state 一次请求；避免 N+1。

## 6. 数据、备份与许可

### 6.1 配置文件

| 文件 | 结构 | 说明 |
| --- | --- | --- |
| data/config/launcher-v2.json | 沿用现有 schemaVersion=1 | 实例清单、selected、settings{proxy,registry,background}；不再使用旧版 instances.properties |
| data/config/accounts.json | 沿用现有 + protector/keyId | accounts[]{id,name,provider,baseUrl,model,encryptedKey}；id 仍为小写 UUID |
| data/config/server.json | 新增 | passwordHash、sessionTimeoutMinutes、initialPasswordPending、bindPort、trustedProxy、version |
| data/config/secret.key | 新增 | 32 字节随机主密钥（0600）；可用 PCL_DSH_SECRET_KEY 覆盖 |
| data/initial-password | 新增（临时） | 首次启动生成的初始密码（0600），首次登录改密后删除 |
| data/config/compatibility.json | 沿用 | 可选的 CLI 能力人工声明 |

### 6.2 权限与所有者

- 系统用户：pcl-dsh（系统用户，nologin，home=/opt/pcl-dsh-linux）；服务以 pcl-dsh 运行；root 仅用于安装/升级/Nginx。
- 目录权限：/opt/pcl-dsh-linux 755 root:root；release 只读；data 0700 pcl-dsh:pcl-dsh；config 0700；secret.key / accounts.json 0600；缓存/临时目录 0700。
- 工具目录：发布包内的 tools 为只读；运行期生成的 shim/插件桥写入 data/tools/shims（需要把现有 RuntimeService 的 shim 写出位置改为可配置，见 M1 任务 B-11）。

### 6.3 备份与恢复

- 备份对象：data/ 全目录（含 secret.key！）＋ 发布包版本号 ＋ Nginx/systemd 配置副本。
- 备份命令（示例，具体脚本 M4 提供）：tar -czf /opt/pcl-dsh-linux-backup-$(date +%F).tar.gz -C /opt/pcl-dsh-linux data，并单独备份 /etc/nginx/conf.d/pcl-dsh-linux.conf 与 /etc/systemd/system/pcl-dsh-linux.service。
- 恢复：装同版本发布包 → 停服务 → 还原 data → 启动服务 → 登录验证。
- 注意：secret.key 丢失则账户 API Key 无法解密，只能重新输入；备份文件本身含密钥，需妥善保管。
- 建议频率：重大升级前必备份；日常按需（单用户）。

### 6.4 许可合规

- 发布包（tar.gz）包含：LICENSE（GPL-3.0 全文）、LICENSES/HMCL-ADDITIONAL-TERMS.md、THIRD_PARTY_NOTICES.md、界面可见版权、源码包或源码下载说明。
- 新增第三方依赖许可清单需补充到 THIRD_PARTY_NOTICES：Spring Boot/Spring Framework（Apache-2.0）、Node 24（MIT）、pnpm（MIT）、Vue/Router/Pinia/Vite（MIT）、Playwright（Apache-2.0）等；构建时核对实际版本。
- 应用内「关于」与登录页显示：PCL-DSH-Linux 0.3.0、Copyright (c) 2026 jiefing、GPL-3.0-or-later、HMCL 附加条款、上游版权声明；不删除、不隐藏。
- 发布给他人时附对应源码（本次修改后的全部源码），满足 GPL；源码包由构建脚本生成并附 SHA-256。

## 7. 部署与运维（Linux 单机）

### 7.1 目标环境

Ubuntu 24.04.4 LTS x86_64；2 vCPU；1.6 GiB 内存 + 2 GiB swap；40 GB 磁盘（28 GB 可用）；系统 JDK 21；公网 39.107.255.118；安全组放行 80（建议收窄来源 IP）；Nginx 由部署时安装。

### 7.2 安装前检查

1. root 或 sudo 权限、SSH 可登录。
2. java -version ≥ 21。
3. 80 端口未被占用；当前无其他站点（已确认）。
4. 磁盘可用 ≥ 10 GB（已满足 28 GB）。
5. 外网可访问 registry.npmjs.org、nodejs.org、api.github.com（已确认 200）。
6. 你同意安装 Nginx（apt install nginx）。

### 7.3 安装步骤（M4 交付脚本后按此执行）

```bash
# 1) 上传发布包（在你的 Windows 本机执行；示例）
scp pcl-dsh-linux-0.3.0-linux-x64.tar.gz root@39.107.255.118:/root/

# 2) 服务器上校验并解压
cd /root
sha256sum -c pcl-dsh-linux-0.3.0-linux-x64.tar.gz.sha256
tar -xzf pcl-dsh-linux-0.3.0-linux-x64.tar.gz
cd pcl-dsh-linux-0.3.0-linux-x64

# 3) 安装（脚本会创建 pcl-dsh 用户、目录、systemd、初始密码；需要 Nginx 时会提示）
sudo ./install.sh

# 4) 若 Nginx 尚未安装（由你确认后执行）
sudo apt update && sudo apt install -y nginx
sudo systemctl enable --now nginx

# 5) 健康检查
curl -s http://127.0.0.1:8080/api/health
curl -sI http://127.0.0.1/ | head

# 6) 浏览器访问
# http://39.107.255.118/
# 初始密码见 /opt/pcl-dsh-linux/data/initial-password（首次登录后修改并删除）
```

### 7.4 systemd 单元（模板）

```ini
[Unit]
Description=PCL-DSH-Linux 0.3.0
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=pcl-dsh
Group=pcl-dsh
WorkingDirectory=/opt/pcl-dsh-linux/current
ExecStart=/usr/bin/java -Xms128m -Xmx384m -XX:MaxMetaspaceSize=160m -XX:+ExitOnOutOfMemoryError -jar /opt/pcl-dsh-linux/current/pcl-dsh-linux.jar --server.address=127.0.0.1 --server.port=8080 --pcl.data=/opt/pcl-dsh-linux/data
Restart=on-failure
RestartSec=5
TimeoutStopSec=40
LimitNOFILE=65535
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/pcl-dsh-linux/data
Environment=PCL_DSH_DATA=/opt/pcl-dsh-linux/data

[Install]
WantedBy=multi-user.target
```

说明：systemd 控制组会随服务停止终止全部子进程（Node 实例），确保无孤儿进程；应用内仍做优雅停止。

### 7.5 Nginx 配置（模板）

```nginx
limit_req_zone $binary_remote_addr zone=pcl_dsh_login:10m rate=10r/m;

server {
    listen 80 default_server;
    server_name _;

    # 安全组之外的应用层白名单（安装脚本按你的来源 IP 填充；可注释掉）
    # allow 203.0.113.10;
    # deny all;

    client_max_body_size 2048m;
    server_tokens off;

    # 登录/API：禁用 SSE 缓冲、放宽超时
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 1800s;
        proxy_send_timeout 1800s;
        proxy_buffering off;
    }

    location = /api/auth/login {
        limit_req zone=pcl_dsh_login burst=5 nodelay;
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # DSH 工作台反代（WebSocket + 不缓冲；不记录访问日志以防 token 泄漏）
    location /workbench/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
        access_log off;
    }

    # 前端页面与静态资源
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

另需在 http 块定义：map $http_upgrade $connection_upgrade { default upgrade; "" close; }（放在 /etc/nginx/conf.d/pcl-dsh-linux-map.conf）。

### 7.6 运行监控与排障

| 命令 | 用途 |
| --- | --- |
| systemctl status pcl-dsh | 服务状态/最近日志 |
| journalctl -u pcl-dsh -f | 实时日志 |
| curl -s http://127.0.0.1:8080/api/health | 应用健康 |
| ss -tulpn | 端口占用（8080/3080+） |
| free -h、ps -eo pid,rss,cmd --sort=-rss | 内存占用（Java vs Node） |
| df -h /opt、du -sh /opt/pcl-dsh-linux/data/* | 磁盘（runtimes/cache/trash 最大） |
| nginx -t && systemctl reload nginx | 校验并重载 Nginx |
| tail -n 200 /opt/pcl-dsh-linux/data/logs/launcher-日期.log | 启动器日志 |

常见问题：
- 502：Spring Boot 未启动/崩溃 → journalctl；8080 未监听。
- 401 循环：Cookie/SameSite 或 Nginx 未转发 X-Forwarded；检查 /api/auth/me。
- SSE 不更新：Nginx 缓冲未关或代理超时。
- 413：上传超过 client_max_body_size 或 Spring multipart 上限。
- OOM：内存 1.6 GiB 下同时跑多个实例；停实例/加 swap/升级内存。
- 端口占用：实例端口被外部程序占用；换端口或停占用进程（PCL-DSH-Linux 不会杀非自己进程）。

### 7.7 安全加固清单

1. 云安全组：80 与 22 只放行你的来源 IP（如可固定）；关闭当前未使用的 5000、18789。
2. SSH：使用密钥登录、禁止 root 密码登录（可选，需保留一个可用账号）、安装 fail2ban（可选）。
3. Nginx：应用层 allow/deny 白名单；登录接口 limit_req；server_tokens off；不记录 /workbench 查询串。
4. 应用：强密码 + BCrypt；连续失败锁定；会话 HttpOnly/SameSite=Lax；CSRF；审计日志。
5. 账户密钥：secret.key 0600；备份加密保管；日志/API 永不返回密钥。
6. 文件：上传大小/类型限制；临时目录 0700；下载 no-store。
7. 升级：只从可信渠道获取发布包并校验 SHA-256；升级前备份 data。
8. 忘密码重置：服务器上停止服务 → 删除 data/config/server.json 中 passwordHash 或执行安装包内 reset-password 脚本（M4 提供）→ 重新生成初始密码 → 启动。

### 7.8 升级与回滚

```bash
# 升级（示例）
sudo systemctl stop pcl-dsh
sudo tar -xzf pcl-dsh-web-0.4.0-linux-x64.tar.gz -C /opt/pcl-dsh-linux/releases
sudo ln -sfn /opt/pcl-dsh-linux/releases/0.4.0 /opt/pcl-dsh-linux/current
sudo systemctl start pcl-dsh
curl -s http://127.0.0.1:8080/api/health

# 回滚
sudo systemctl stop pcl-dsh
sudo ln -sfn /opt/pcl-dsh-linux/releases/0.3.0 /opt/pcl-dsh-linux/current
sudo systemctl start pcl-dsh
```

升级前备份 data（§6.3）；若新版本修改了数据 schema，按发布说明执行迁移，回滚前恢复备份。

### 7.9 日志与磁盘维护

- 应用日志：/opt/pcl-dsh-linux/data/logs/（launcher-日期.log、audit-日期.log）；建议 logrotate 保留 30 天。
- Nginx 日志：/var/log/nginx/（pcl-dsh 站点可单独 access.log；/workbench 关闭访问日志）。
- 清理：trash 定期人工确认；cache/pnpm-store 可重建；runtimes 只保留使用中的版本；disk 使用率 >80% 告警。
- 备份与清理操作在执行前先确认路径，避免误删 data。

### 7.10 首次部署验收清单

1. 浏览器打开 http://39.107.255.118/ → 登录页；错误密码提示；正确密码进入。
2. 创建实例（默认 profile web、自动端口）成功。
3. 「下载并启动」：运行时安装完成、实例启动、工作台就绪。
4. 点击「打开工作台」：新标签页进入 DSH Web，登录态正常；控制台无跨域错误。
5. 停止实例：进程退出、端口释放、界面状态正确。
6. 重启服务器（或 systemctl restart pcl-dsh）：登录态按预期、实例清单保留、未启动的实例不自动运行。
7. 账户保存后重启服务：密钥仍可解密；日志无密钥。
8. 上传一个 .dspack 预览（可选）：校验通过/失败提示正确；取消不残留。
9. 备份 data → 恢复 → 重新登录并启动实例（验证备份可用）。
10. 内存检查：启动一个实例后 free -h 仍有余量；无 OOM 记录。

## 8. 测试与验收

### 8.1 测试层次

| 层次 | 范围 | 工具/方式 | 运行环境 |
| --- | --- | --- | --- |
| 单元测试 | SemVer/Capabilities/InstanceStore/ProcessService/TaskProgress/PackService/SafeArchive/TarSafety/PluginCatalogService/AccountOverlay 等 | JUnit 5 | Windows + WSL（Linux） |
| 集成测试 | RuntimeService 安装/探测（本机 Node 夹具）、Controller 流程（FakeRuntime）、进程启停 | JUnit 5 + loopback | WSL Linux 为主 |
| 后端 API 测试 | 登录/会话/CSRF、实例/运行时/账户/插件/整合包、Job/SSE、上传下载、反代 | Spring Boot Test + MockMvc/WebTestClient + 真实实例 | WSL / 服务器 |
| 前端单元 | store、API 客户端、工具函数、组件渲染 | Vitest + @vue/test-utils | Windows |
| E2E | §5.4 的 15 条行为 + 登录 + 部署冒烟 | Playwright（Chromium） | Windows（连接本机后端） |
| Linux 部署验收 | §7.10 清单 | 手工 + 脚本 | 阿里云服务器 |

### 8.2 现有测试迁移评估

| 类别 | 用例数 | 处理 |
| --- | --- | --- |
| A 类（纯逻辑/loopback，可直接迁移） | 约 100 | 移入 pcl-dsh-core/pcl-dsh-server，改包名与 DTO 断言；Linux CI 全量运行 |
| B 类（需夹具/真实运行时/网络，有开关） | 约 37 | 保留开关（-Dpcl.integration 等），在 WSL/服务器按需运行 |
| C 类（JavaFX UI 验收） | 1（956 行） | 不迁移；行为平移为 Playwright（§5.4） |

迁移时必须处理：
- 包名与依赖：原测试引用 com.hdsl.ui/javafx 的断言改写为 REST DTO/HTML 断言。
- 平台跳过：AccountServiceTest 的 DPAPI assumeTrue 改为“LinuxSecretProtector 测试”；AccountReferenceTest 的 @EnabledOnOs(WINDOWS) 删除或改为通用。
- Node 夹具：RuntimeServiceTest/PluginServiceTest 中依赖 Node 的用例，在 WSL 准备好 Node 24.21.0 后运行。
- 真实网络测试：默认跳过，发布前在服务器或 WSL 显式执行一次并记录。

### 8.3 新增后端测试清单

1. 认证：未登录访问任意 API → 401；登录成功/失败；CSRF 缺失 → 403；暴力破解锁定；登出。
2. 实例：创建/编辑/删除/复制/选择/绑定账户；端口冲突；名称/profile 校验；运行中禁止修改。
3. 运行时：可用版本查询（loopback npm 夹具）、安装失败/成功、能力探测、删除引用保护。
4. 账户：保存/编辑留空 Key/删除引用保护；LinuxSecretProtector 加解密；密钥缺失/损坏的错误提示。
5. Job/SSE：202 + jobId；事件顺序；断线续传；取消；超时；忙时 409；单运行实例限制。
6. 上传/下载：2 GiB 限流（小样本模拟）、流式计算 SHA-256、清理；导出 artifact 下载。
7. 反代：token 注入、Cookie 重写、Location 重写、401 透传、WebSocket 转发（M0 结论后定实现）、SSRF 防护（只允许 127.0.0.1 登记端口）。
8. 安全：日志脱敏（Key/token/password）、审计日志、请求体大小限制、路径遍历防护。
9. 兼容：launcher-v2.json 读写；accounts.json 新字段；旧数据读取不崩溃。

### 8.4 M0 Linux PoC 验证清单（必须全部有结论）

1. 发布包自带 Node 24.21.0 + pnpm 10.34.0 在 Ubuntu 24.04 上可执行；系统 Node 24.15.0 回退也可用（对比记录）。
2. npm 最新版 DSH：install（pnpm）耗时/磁盘/依赖数量；能力探测结果（--port/--host/--no-open）。
3. 启动：准备环境变量后成功启动；awaitReady 通过；HTTP 200（或新版 401 + token 机制）；日志无敏感信息。
4. 工作台：token 形式、Cookie/WS/绝对地址/CSP 行为；最小反代原型验证（含 WebSocket 是否可用）。
5. 停止：进程树退出、端口释放、重复启动正常。
6. 内存：Java（-Xmx384m）+ 1 个 DSH 实例的空闲/运行峰值 RSS；free -h 余量；是否需要 NODE_OPTIONS。
7. 账户绑定：真实（或模拟）账户 overlay 生成与校验 ok（M1 前可用假 Key 与本机模拟服务，不调用付费 API）。
8. 结论：反代可行/不可行；Node 版本选择；内存参数；是否需调整方案。

### 8.5 里程碑 DoD（完成定义）

| 里程碑 | DoD |
| --- | --- |
| M0 | PoC 报告（含 §8.4 全部结论）；反代方案确认；环境/版本/内存参数定稿；服务器临时目录清理 |
| M1 | 全部 REST/Job/SSE/认证/账户/反代接口可用；A 类测试在 Linux 通过；无 JavaFX/JNA 依赖；健康检查与启动自检；Swagger/接口文档（可选） |
| M2 | 登录 + 首页 + 实例 + 运行时 + 账户 + 日志 + 设置页可用；核心 Playwright 用例通过；视觉对照通过（截图对比）；未保存保护/忙碌态正确 |
| M3 | 插件/整合包/社区目录页面可用；全量 15 条 E2E 通过；上传/导出/导入端到端；实例目录打包下载 |
| M4 | 发布包可在干净 Ubuntu 24.04 安装；§7.10 验收全通过；升级/回滚/备份恢复演练通过；许可与源码包齐全；文档齐全 |

## 9. 里程碑与任务拆解

### 9.1 总览

| 里程碑 | 目标 | 估算（人日） | 主要产出 |
| --- | --- | ---: | --- |
| M0 | Linux PoC 与关键未知项验证 | 8–10 | PoC 报告、反代原型、参数定稿 |
| M1 | 后端服务化完整接口 | 15 | pcl-dsh-core/pcl-dsh-server、全部 API/Job/SSE/认证/反代 |
| M2 | 核心 UI | 20–25 | 登录/首页/实例/运行时/账户/日志/设置 |
| M3 | 剩余 UI 与全量对齐 | 15–18 | 插件/整合包/社区目录/细节打磨/E2E 全量 |
| M4 | 打包、部署、验收、发布 | 10–15 | 发布包、安装/升级脚本、文档、服务器验收 |
| **合计** |  | **68–83** | 约 14–17 周（1 人 + AI 辅助，含风险缓冲） |

说明：估算按顺序执行；M2 与 M1 的后端契约确定后可部分并行；实际以每阶段复盘为准。

### 9.2 M0 任务（PoC）

| ID | 任务 | 估算 | 依赖 | 产出 |
| --- | --- | ---: | --- | --- |
| M0-1 | 服务器临时目录与 PoC 环境准备（/opt/pcl-dsh-linux-poc，你执行命令） | 0.5 | 你提供执行配合 | 环境就绪 |
| M0-2 | 复用现有 Controller/InstanceStore/ProcessService/RuntimeService 写最小启动探针（临时类/脚本） | 2 | M0-1 | 可执行探针 |
| M0-3 | 安装 npm 最新版 DSH：耗时、磁盘、依赖数、能力探测 | 1.5 | M0-2 | 数据记录 |
| M0-4 | 启动/就绪/停止/端口释放验证（含重复启动、进程树） | 1 | M0-3 | 结果记录 |
| M0-5 | 工作台 token/WS/绝对地址/CSP 调查 + 最小反代原型 | 2.5 | M0-4 | 反代原型与结论 |
| M0-6 | 内存/性能测量（Java + 1 实例），确定 NODE_OPTIONS 与 JVM 参数 | 1 | M0-4 | 参数建议 |
| M0-7 | 账户注入链路验证（假 Key + 本机模拟服务） | 1 | M0-4 | 结论 |
| M0-8 | PoC 报告与方案更新（含反代决策） | 1 | 全部 | PoC 报告 |

### 9.3 M1 任务（后端）

| ID | 任务 | 估算 | 依赖 | 产出 |
| --- | --- | ---: | --- | --- |
| B-1 | 多模块工程骨架、父 POM、依赖调整、包迁移 | 2 | M0 | 可编译工程 |
| B-2 | 数据根/配置/启动自检/日志框架/健康检查 | 1.5 | B-1 | 基础运行框架 |
| B-3 | 认证/会话/CSRF/防爆破/审计 | 2.5 | B-1 | 登录闭环 |
| B-4 | JobManager + SSE + 取消/超时 | 2.5 | B-1 | 任务框架 |
| B-5 | 实例 REST（CRUD/复制/选择/绑定） | 2.5 | B-4 | 实例接口 |
| B-6 | 运行时 REST（查询/安装/探测/删除） | 2 | B-4 | 运行时接口 |
| B-7 | LinuxSecretProtector + 账户 REST | 2 | B-3 | 账户闭环 |
| B-8 | 启动/停止/就绪/单实例限制 | 2 | B-4,B-6 | 启停闭环 |
| B-9 | DSH 反代（HTTP/WS/token/Cookie） | 3.5 | M0-5,B-2 | 工作台可达 |
| B-10 | 上传/下载/artifact/临时清理 | 2 | B-4 | 文件通道 |
| B-11 | 工具目录/shim 路径改造、环境变量白名单 | 1 | B-1 | 运行时适配 |
| B-12 | 插件/社区目录/整合包接口（不含 UI） | 3 | B-5,B-6,B-10 | 全量后端接口 |
| B-13 | 状态/DTO/错误模型/脱敏/审计细化 | 1.5 | B-5..B-10 | 契约稳定 |
| B-14 | 测试迁移（A 类）+ 新增后端测试 + WSL Linux 运行 | 3 | 上述 | 测试报告 |
| B-15 | 接口文档与临时冒烟页（便于前端联调） | 1 | 上述 | 联调入口 |

### 9.4 M2 任务（核心 UI）

| ID | 任务 | 估算 | 依赖 |
| --- | --- | ---: | --- |
| F-1 | Vue 工程骨架、Vite、路由、Pinia、API 客户端、构建接入 | 2 | B-1,B-15 |
| F-2 | 登录页、会话守卫、CSRF、改密流程 | 1.5 | B-3 |
| F-3 | AppShell/40px 标题栏/200px 侧栏/背景/字体/CSS tokens（视觉复刻） | 3 | F-1 |
| F-4 | 首页：启动区、实例切换器、任务卡、四态按钮 | 3 | F-3,B-5,B-8 |
| F-5 | 实例页 + 创建/编辑/复制/删除弹窗 | 3 | F-3,B-5 |
| F-6 | 运行时页（版本列表/安装/探测/删除/进度） | 2.5 | F-3,B-6 |
| F-7 | 账户页（列表/表单/绑定/预设） | 2.5 | F-3,B-7 |
| F-8 | 日志页 + SSE + 取消 + 复制回退 | 2 | F-3,B-4 |
| F-9 | 设置页（网络/外观/背景/关于许可） | 2.5 | F-3,B-2 |
| F-10 | 全局错误/忙碌态/轮询/未保存保护 | 2 | 上述 |
| F-11 | Playwright 核心用例 1–10 | 2.5 | F-4..F-9 |
| F-12 | 视觉对照与验收（截图对比桌面版、细节修正） | 2 | F-11 |

### 9.5 M3 任务（全量对齐）

| ID | 任务 | 估算 | 依赖 |
| --- | --- | ---: | --- |
| F-13 | 插件页（列表/添加/更新/移除/进度） | 2.5 | B-12,F-3 |
| F-14 | 整合包页（上传/预览/导入/导出/下载） | 3 | B-12,F-3 |
| F-15 | 社区插件目录页（搜索/分页/检查目标/安装） | 2.5 | B-12,F-3 |
| F-16 | 实例目录打包下载入口（替代打开文件夹） | 1 | B-10 |
| F-17 | E2E 全量 15 条 + 边界用例 | 2.5 | F-11 |
| F-18 | 细节打磨（动画、键盘、无障碍、错误文案、空状态） | 2.5 | 上述 |

### 9.6 M4 任务（打包与发布）

| ID | 任务 | 估算 | 依赖 |
| --- | --- | ---: | --- |
| P-1 | 构建脚本（Windows 构建 + Linux 包组装、Node 下载与校验、SHA-256） | 2 | M1/M2 |
| P-2 | install.sh / uninstall.sh / systemd / Nginx 模板 / 初始密码 / IP 白名单采集 | 2.5 | P-1 |
| P-3 | 升级/回滚/重置密码/备份脚本 | 1.5 | P-2 |
| P-4 | 源码包与许可材料、THIRD_PARTY_NOTICES 更新 | 1 | P-1 |
| P-5 | 部署文档、使用说明、迁移/备份说明 | 1.5 | P-2 |
| P-6 | 服务器全流程验收（§7.10）与你共同执行 | 2 | P-2 |
| P-7 | 安全清单核对、备份恢复演练 | 1 | P-6 |
| P-8 | 发布 v0.3.0（校验和、源码包、发布说明） | 0.5 | 全部 |

### 9.7 关键路径与并行建议

- 关键路径：M0-5（反代可行性）→ B-9（反代实现）→ F-4/F-5（核心页面）→ F-14（整合包 UI）→ P-6（部署验收）。
- 可并行：M2 的 F-3/F-9 与 M1 的后段接口；M3 的 F-13/F-15 与 F-14。
- 风险缓冲：总估算已含约 15% 缓冲；反代若推翻则触发 R1 预案，工期 +1–2 周。

## 10. 风险与应对

| ID | 风险 | 影响 | 概率 | 应对 |
| --- | --- | --- | --- | --- |
| R1 | DSH 工作台无法安全反代（绝对地址/WS/token） | 高 | 中 | M0-5 提前验证；预案：Nginx 动态 location + 一次性 token，或重新决策端口暴露；最迟 M0 结束前给出结论 |
| R2 | 系统 Node 24.15.0 与实测 24.21.0 行为差异 | 中 | 低 | 发布包自带 24.21.0 为默认；M0 两种都测；仅保留一种进发布 |
| R3 | 1.6 GiB 内存不足导致 OOM | 高 | 中 | 单运行实例 + -Xmx384m + swap；M0 实测；超过阈值则升级服务器（4 GiB）或限制 |
| R4 | HTTP 明文公网风险（密码/token 被窃听） | 高 | 中 | 来源 IP 白名单（云安全组 + Nginx）+ 强密码 + 防爆破；后续可加 HTTPS |
| R5 | Nginx 未安装、需系统改动 | 中 | 高（已确认） | 部署阶段先征得你同意再 apt install；脚本检测并提示 |
| R6 | systemd 环境找不到 Node（nvm 在 /root） | 高 | 高（已确认） | 自带 tools/node/pnpm；服务不依赖 nvm；启动自检验证 |
| R7 | 80 端口被云厂商/运营商策略限制 | 中 | 低 | 部署时用浏览器/curl 实测；失败则改用 8080 等已开放端口并更新 Nginx |
| R8 | 上传 2 GiB 与 1.6 GiB 内存/磁盘冲突 | 中 | 低 | 流式上传不整读内存；磁盘 28 GB 监控；限制并发上传 1 个 |
| R9 | 旧版本 DSH 未实测 | 中 | 中 | 界面标注“未实测”；能力探测失败给出明确错误；验收基线=最新版（Q13） |
| R10 | 上游 CLI/包结构变化导致兼容层失效 | 中 | 高（长期） | 沿用 Capabilities + compatibility.json；错误信息可操作；订阅上游发布 |
| R11 | GPL/HMCL 合规 | 高 | 低 | 保留许可、版权、源码包；UI 复刻属派生，继续 GPL；发布前检查 |
| R12 | 单实例限制与“全量对齐”预期差异 | 低 | 中 | 界面明确提示“服务器限制：同时最多 1 个实例”；文档说明；升级内存可放开 |
| R13 | systemd ProtectSystem 与工具写权限 | 中 | 中 | 只读发布目录 + data/tools/shims 可写（§6.2）；启动自检覆盖 |
| R14 | Playwright/浏览器在 HTTP 非安全上下文行为差异 | 低 | 中 | E2E 覆盖剪贴板回退等；不依赖 clipboard/service worker |
| R15 | 首次部署时你对命令不熟悉 | 中 | 高 | 逐条命令 + 预期输出 + 回滚步骤；关键操作先说明再执行 |
| R16 | 密码/密钥管理不当（忘记、泄露、误删 secret.key） | 高 | 中 | 首次登录改密；重置脚本；备份含 secret.key 并单独保管；文档提示 |

## 11. 待决事项（TBD）

| ID | 事项 | 何时决定 | 当前默认 |
| --- | --- | --- | --- |
| TBD-1 | 登录密码策略细节（初始密码生成/是否强制改密、会话时长、失败锁定阈值） | 发布前（M4） | 系统生成 20+ 位、首次强制改密、会话 12h、5 次/60s 锁定 |
| TBD-2 | Nginx 安装许可与执行方式 | 部署前 | 你同意后由你执行 apt install nginx；脚本先检测 |
| TBD-3 | DSH 实例 Node 内存上限（NODE_OPTIONS） | M0 实测后 | 暂不注入，按默认；若峰值过高再限制 |
| TBD-4 | 云安全组/SSH 来源收窄、关闭 5000/18789 | 部署前 | 建议执行；不影响开发 |
| TBD-5 | 发布名称最终确认 | 发布前 | PCL-DSH-Linux 0.3.0 |
| TBD-6 | 是否同时提供 Docker 形态 | 后续版本 | 不做 |
| TBD-7 | 来源 IP 白名单是否默认开启 | 部署时 | 默认开启（安装脚本采集你的 IP），可关闭 |
| TBD-8 | 主密钥轮换功能 | 后续版本 | 不做（文档提示） |
| TBD-9 | 是否启用 HTTPS（自签/IP 证书/域名） | 后续 | 不做，Nginx 预留配置 |

## 12. 附录

### 附录 A 环境变量与配置项

| 名称 | 位置 | 默认 | 说明 |
| --- | --- | --- | --- |
| --pcl.data / PCL_DSH_DATA | 启动参数/环境 | /opt/pcl-dsh-linux/data | 数据根目录 |
| PCL_DSH_SECRET_KEY | 环境（可选） | 空 | 主密钥（Base64/Hex 32 字节），优先于 secret.key |
| server.address / server.port | 启动参数 | 127.0.0.1 / 8080 | Spring Boot 监听 |
| server.forward-headers-strategy | 配置 | framework | 信任 127.0.0.1 的反代头 |
| spring.servlet.multipart.max-file-size | 配置 | 2048MB | 上传上限（与 Nginx 对齐） |
| pcl.session.timeout | server.json | 12h | 会话过期 |
| pcl.job.timeout.* | 配置 | 见 §4.3 | 各任务超时 |
| pcl.proxy.enabled | 配置 | true | 是否启用 /workbench 反代 |
| pcl.instance.max-running | 配置 | 1 | 单运行实例上限 |
| NODE_OPTIONS | 实例环境（可选） | 空 | DSH 实例 Node 内存参数（TBD-3） |
| HTTP_PROXY/HTTPS_PROXY/NO_PROXY | 实例环境 | 继承设置 | 由 settings.proxy 注入 |

### 附录 B 端口与路径速查

- 公网：80 → Nginx；SSH 22（建议收窄）。
- 本机：8080 Spring Boot；3080+ DSH 实例（仅回环）。
- 目录：/opt/pcl-dsh-linux/{current,releases,data}；日志 /opt/pcl-dsh-linux/data/logs；缓存 /opt/pcl-dsh-linux/data/cache。
- 配置文件：/opt/pcl-dsh-linux/data/config/{launcher-v2.json,accounts.json,server.json,secret.key,compatibility.json}。
- Nginx：/etc/nginx/conf.d/pcl-dsh-linux.conf、pcl-dsh-linux-map.conf；systemd：/etc/systemd/system/pcl-dsh-linux.service。

### 附录 C 与可行性分析报告的差异（决策更新）

| 报告中建议 | 最终决策 | 原因 |
| --- | --- | --- |
| 部署形态 A/B/C，推荐 B 容器化 | 改为自包含目录 + systemd（不使用 Docker） | 你希望少学新东西、直接可控；服务器资源与单用户场景适合 |
| 推荐 Docker 交付 | 不采用 | 同上；以后可选 |
| 数据迁移支持 | 全新开始 | 你确认无迁移需求 |
| 多用户/远程架构预留 | 单用户，明确不做 | 使用对象确定 |
| 来源 IP 白名单可选 | 默认开启（可关闭） | 公网 HTTP 风险补偿 |
| 前端可用组件库快速实现 | 全量复刻，自研轻量组件 | 你选择保守可靠 |
| 桌面端下载保留 | 移除 | Linux 无意义 |
| Windows 账户 DPAPI 兼容 | 不兼容，LinuxSecretProtector 新实现 | 技术必然 |
| 预期总工期 12–16 周 | 14–17 周（含缓冲） | 全量 UI 复刻 + 反代 + 自包含发布包 |

### 附录 D 术语表

| 术语 | 含义 |
| --- | --- |
| DSH | DeepSeek Harness，被启动器管理的 CLI/工作台程序 |
| HDSL | 本项目（桌面版）名称；Web 版命名 PCL-DSH-Linux 0.3.0 |
| 实例 | 一套独立的 DSH_HOME/工作区/端口配置 |
| 运行时 | 按精确版本安装的 DSH npm 包及其依赖 |
| Job | 一次长任务（安装/启动/导入/导出等） |
| SSE | Server-Sent Events，服务器向浏览器推送任务进度/日志 |
| 反代 | 反向代理；本文特指 Spring Boot 代浏览器访问 DSH 实例端口 |
| profile | DSH 的配置档案（web 为默认模板） |
| .dspack | 整合包格式（PackForge 容器 v2/v3 + manifest v4/v5） |
| systemd | Linux 服务管理器，负责常驻/自启/重启 |

### 附录 E 实施启动条件与确认项

- [ ] 你已确认本文档全部内容（尤其 §1.4 决策表、§9 里程碑、§11 TBD 默认值）。
- [ ] 你本机 JDK 21 安装完成并告知我（M1 编译需要）。
- [ ] 你同意在 WSL 中安装 Linux 构建/测试所需依赖（到 M0/M1 时会先给命令）。
- [ ] 你接受「单服务器最多同时运行 1 个 DSH 实例」与「公网 HTTP + 登录密码」的已知限制。
- [ ] 你接受部署阶段需要执行 apt install nginx（会先告知）。
- [ ] 你接受发布物继续 GPL-3.0-or-later 并要求保留版权声明与源码提供。

**未完成上述确认前，不开始写实现代码。**

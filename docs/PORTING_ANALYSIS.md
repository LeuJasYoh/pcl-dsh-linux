# HDSL → Linux WebUI 移植可行性分析报告

> **命名更新（2026-10-22）**：本项目的 Web 版正式命名为 **PCL-DSH-Linux**；本报告正文的分析对象仍为参考项目 HDSL 0.2.0，结论不变。

- **调研对象**：D:/Projects/DSHL/HDSL/（HDSL 0.2.0 参考源，VERSION 文件为 0.2.0）
- **目标形态**：运行在 Linux 上的 WebUI 软件，功能与 Windows 桌面版对齐；后端 Spring Boot（Java 21），前端 Vue 3 + TypeScript
- **目标产出位置**：D:/Projects/DSHL/PCL-DSH-Linux/（调研开始时为空目录）
- **报告日期**：2026-10-22
- **证据口径**：路径与行号均来自本次实际读取的源码或文档；标注【推测】的是未运行验证的推断；标注【不确】的是资料不足。本次**未**运行 HDSL、**未**执行构建与测试、**未**联网下载依赖，因此所有结论以静态代码与项目文档为依据，真实运行行为仍需 PoC 验证。

---

## 0. 结论摘要

1. **可行性高，且主要工作量不在"替换 Windows API"。** HDSL 0.2.0 的业务层（实例/运行时/整合包/插件/账户/下载）约 3,494 行纯 Java，基本已按跨平台方式编写：进程管理用 JDK ProcessHandle、文件与 HTTP 用 java.nio 与 java.net.http、编码统一 UTF-8，Node/pnpm 查找自带 POSIX 分支（`RuntimeService.java:145-176,541-551`）。真正 Windows-only 的只有 5 类：JavaFX 前端（2,765 行）、DPAPI 加密（23 行）、java.awt.Desktop（4 处调用）、jpackage/PowerShell 打包链（202 行）、Windows x64 桌面安装包下载过滤。
2. **真实工作量是"重建 Web 应用外壳"。** 需要新写：Spring Boot 服务化（REST、任务队列、SSE、鉴权、反向代理）、Vue 3 前端（8 个页面 + 全部对话框，替换 2,765 行 JavaFX/HMCL 代码）、Linux 密钥后端、进程组管理、文件上传/下载模型、DSH 工作台的浏览器访问通道、Docker/systemd 分发与 CI。
3. **复用比例（按代码行）**：业务后端 3,494 行中约 **99% 可复用**（原样约 60%、小改约 39%、必须重写 <1%）；JavaFX 前端 **0 行可运行代码复用**（仅布局、交互、配色可参考）；测试 24 类 138 个用例中约 **100 个可迁移**（纯逻辑或本机 loopback 测试，约占测试代码 73%）。把 UI 与测试一起计入，全仓约 **62% 代码行可保留、38% 需重写或新建**（不含新增 Web 基础设施代码）。
4. **Web 场景必须改的是产品语义而非平台 API**：本地文件选择变成上传/下载；打开浏览器/文件夹变成返回 URL 或下载流；Windows 桌面安装包下载在 Linux 上无意义；单用户"全局忙锁"要改成任务队列以支持多标签页；只绑 127.0.0.1 的 DSH 工作台需要直连或反向代理才能被浏览器访问。
5. **最大技术风险**：① DSH 工作台的浏览器可达性（启动地址含一次性引导 token，仅驻内存，`ProcessService.java:19-20,40,100-108`；新版未带 token 首页可能 401，`docs/runtime-compatibility.md:35`）；② Linux 上进程树终止是否可靠（现按 descendants 快照杀，守护化子进程可能逃逸，`ProcessService.java:77-79,89-93`）；③ Linux 上 Node/pnpm 与原生模块 ABI 未经实测；④ 账户启动注入依赖对已安装 Harness JS 源码的字符串探测（`AccountOverlay.java:15-77`），上游一改就可能失效。
6. **建议路线**：保留 Java 业务后端并拆出 Spring Boot 服务层；Vue 3 重写前端；首版定位**单机单用户（localhost，浏览器与启动器同机）**，DSH 工作台走同源反向代理；以 Docker 镜像 + 数据卷作为 Linux 交付形态；分 5 个里程碑，估算 12~16 周（粗估，见第 6 节）。

---

## 1. 项目全貌

### 1.1 这是什么

- HDSL（Hello DeepSeek Harness Launcher）**不是 DSH 本体**，而是 DeepSeek Harness 的实例管理器/启动器。它按精确版本从 npm 安装 DSH CLI 运行时、为每个实例隔离 DSH_HOME/agents-home/工作目录/端口、启动 Node 子进程并等待 Web 服务就绪、管理 API Key 账户并注入启动参数、导入导出 .dspack 整合包、管理插件，以及下载 Windows 桌面安装包（`README.md:3-8,14-24`；`docs/runtime-compatibility.md:3-14`）。
- 版本演进：旧版是 1,939 行单文件 Swing（`src/com/hdsl/Launcher.java`），不参与 Maven 构建（`README.md:94`），仅作迁移参考；0.2.0 把 UI 直接移植自 HMCL（固定提交 77eee17d...，10 个 Java 控件 + 2 个 CSS，`docs/HMCL_PORT.md:17-38`），业务拆为 core/runtime/account/download/pack 五个包。
- 产品界面（8 个路由）：首页（启动/切换实例/任务卡）、实例、下载（运行时/桌面版/插件目录）、账户、插件、整合包、任务与日志、设置；另有账户/实例/导入/导出/许可/确认等对话框（`ShellView.java:145-179,288-817`）。后端由 33 个动作统一分发：`Controller.dispatch(String,Map)`（`Controller.java:85-112,178-201`）。

### 1.2 工程结构与构建方式

- **形态**：单 Maven 模块，无多模块拆分；源码根仅 src/main/java 与 src/main/resources（`pom.xml:19-22`）。旧 Swing 源码放在 src/com/... 但不参与编译（pom 未声明额外 source root）。
- **构建**：JDK 21（`pom.xml:7`）；依赖 JavaFX 21.0.8 controls/swing、Jackson 2.19.2、SnakeYAML 2.4、commons-compress 1.28.0、jna-platform 5.17.0、JUnit 5.13.4（`pom.xml:9-16`）；maven-jar 主类 `com.hdsl.Main`、依赖复制到 target/lib（`pom.xml:26-27`）；`scripts/build.ps1` 下载并校验 Maven 3.9.16 后执行 `mvn -B -ntp verify`（`build.ps1:13-28`）；Windows 发布用 jpackage app-image（`package-windows.ps1:23`），随包 Node v24.21.0 win-x64 与 pnpm@10.34.0（`package-windows.ps1:26-42`）。
- **CI**：GitHub Actions，windows-latest + ubuntu-latest，Temurin 21，仅执行 `mvn -B -ntp verify`，无打包/发布（`.github/workflows/ci.yml:9-22`）。`docs/VALIDATION.md:100` 明确"CI 已配置 Windows/Linux 普通构建，但尚未推送运行"——即 Linux 编译兼容性有配置、无实测记录。
- **规模（本次实测统计）**：主代码 39 文件 / 6,299 行；测试 24 文件 / 3,575 行 / 138 个 @Test；旧 Swing 1,939 行（不构建）；构建打包脚本 5 个 PowerShell 共 202 行；文档 7 篇约 700 行；资源含 5 张背景图 + 图标约 9 MB。

### 1.3 模块与包职责

| 包 | 文件/行数 | 职责 | 关键类 |
| --- | --- | --- | --- |
| core | 5 / 607 | 应用编排、实例清单、进程管理、任务文案 | Controller（330）、InstanceStore（127）、ProcessService（125）、TaskProgress、Instance |
| runtime | 5 / 838 | 按版本安装 Harness、CLI 能力探测、启动命令、profile 依赖恢复、插件 CLI 桥 | RuntimeService（649）、Capabilities、PluginService（104）、SemVer、PluginEntry |
| account | 7 / 498 | 账户元数据与密文、启动注入 overlay、密钥保护接口 | AccountService（161）、AccountOverlay（219）、LaunchBinding（75）、WindowsSecretProtector |
| download | 2 / 610 | 桌面安装包元数据/下载、GitHub 插件目录核验 | DesktopDownloadService（228）、PluginCatalogService（382） |
| pack | 4 / 941 | .dspack 容器 v2/v3、manifest v4/v5、Overture 快照的检查/解压/导出与安全校验 | PackService（684）、SafeArchive（154）、TarSafety（84）、PackInspection |
| ui | 4 + 10 / 2,765 | JavaFX 外壳、8 页面、对话框、状态快照、HMCL 派生控件 | ShellView（1,296）、UiState（141）、UiActions、BackgroundCatalog、ui/hmcl 10 类（1,272） |
| 入口 | 2 / 40 | JavaFX Application、数据根解析、1.5s 轮询 | Main、HdslApplication |
| resources | 3 CSS / 398 | 主题、背景、图标、GPL 文本 | launcher.css、hmcl-root.css、hmcl-blue.css |

### 1.4 核心类与调用关系

```text
HdslApplication (JavaFX)  --new-->  Controller (implements UiActions, AutoCloseable)
       |                                |
       |-- ShellView (JavaFX) <---------+-- snapshot(): UiState (纯 record, 13 字段)
       |        |                       |
       |        +-- dispatch(action, args) --+
       |                                     |
Controller --> InstanceStore        (config/launcher-v2.json, FileLock)
           --> AccountService       (config/accounts.json, SecretProtector)
           --> ProcessService       (子进程 + HTTP 就绪探测 + 日志)
           --> RuntimeService       (懒加载；node/pnpm 子进程、npm 元数据、能力探测)
           --> DesktopDownloadService / PluginCatalogService / PackService (懒加载)
           --> PluginService(runtimeService) / accounts.prepare() / packs()

AccountService --> SecretProtector (默认 WindowsSecretProtector / DPAPI)
               --> AccountOverlay  (读取已装 Harness 公开包源码，生成 --patch)
               --> LaunchBinding   (内存密钥 + 环境变量 + 校验轮询)
PackService    --> SafeArchive / TarSafety
```

引用依据：`Controller.java:19-60,85-112,203-243`；`AccountService.java:66-85`；`RuntimeService.java:190-222,382-406,426-485,511-583`；`ProcessService.java:31-73`；`PackService.java:49,148,512-518`；`UiActions.java:7-9`；`HdslApplication.java:18-31`。

### 1.5 入口与主流程

**启动**：`Main.main` → `Application.launch(HdslApplication)`（`Main.java:5`）→ start()：解析数据根（-Dhdsl.data > HDSL_PORTABLE_ROOT > jpackage.app-path 父目录 > ~/.hdsl，`HdslApplication.java:31`）→ new Controller(root)（初始化实例清单文件锁、账户服务、进程服务，`Controller.java:53-54`）→ ShellView → Scene 1180x760 / 最小 960x650 → 首帧 render → 每 1.5s `dispatch("refresh")`（`HdslApplication.java:20-25`）→ 关闭时停轮询并 `controller.close()`（26, `Controller.java:318-328`）。

**流程 A「创建实例并启动」**：UI 动作 `createInstance` → Controller 校验后建目录并登记实例（`Controller.java:180`）→ `launch(id)` → `RuntimeService.install`（未安装时用 pnpm 安装 @deepseek-ai/dsh@精确版本，超时 20 分钟，`RuntimeService.java:190-222`）→ `inspect` 能力探测（执行 --help，35s / 60s，`382-406`）→ 组装启动命令（--profile / --port / --host 127.0.0.1 / --no-open，`473-485`）→ 注入环境（DSH_HOME、DSH_AGENTS_HOME、registry、proxy、PATH，`487-508`）→ 账户绑定（可选）→ `ProcessService.start` → `awaitReady`（HTTP/1.1 探测 loopback，90s 上限，`ProcessService.java:58-73`；`Controller.java:240`）。

**流程 B「导入整合包」**：`previewPack(path)` → inspect（格式识别 + 全量校验）→ `importPack` → 暂存目录解包 → 安装运行时 → 整目录移动到最终实例目录 → `restoreProfile`（pnpm 默认 frozen-lockfile，仅当错误为 ERR_PNPM_OUTDATED_LOCKFILE 时备份原锁并刷新）→ 最后登记实例；失败移入 trash（`Controller.java:286-311`；`RuntimeService.java:511-536`）。

**流程 C「绑定账户启动」**：`AccountService.prepare` → `AccountOverlay.plan` 读取已安装 Harness 公开包源码判断适配方式 → 生成 account.patch.json（必要时 legacy 桥与校验 mjs）→ 启动命令插入 `--patch` → 环境注入 HDSL_SELECTED_ACCOUNT_API_KEY → 轮询 account.validation.txt 的 ok/failed（20s 超时）（`AccountService.java:66-85`；`AccountOverlay.java:15-77,123-216`；`LaunchBinding.java:21-54`；`Controller.java:221-238`）。

### 1.6 数据目录与配置格式

```text
<data-root>/
  config/launcher-v2.json     实例清单与设置（schemaVersion=1，file lock: config/launcher.lock）
  config/accounts.json        账户元数据（schemaVersion=1，encryptedKey 为 DPAPI 密文 Base64）
  config/compatibility.json   可选的本机 CLI 能力声明（人工维护）
  instances/<id>/dsh-home     该实例的 DSH_HOME（profiles/<profile>/...）
  instances/<id>/workspace    默认工作目录
  instances/<id>/agents-home  隔离的 DSH_AGENTS_HOME
  instances/<id>/.hdsl-account/<uuid>/  启动期临时 overlay（进程退出后清理）
  runtimes/<精确版本>/        对应 Harness 安装与 pnpm-lock.yaml
  tools/{node,pnpm,shims}     内置 Node、pnpm 与 pnpm/插件包装脚本
  cache/{pnpm-store,registry-metadata,probes,npm,pack-staging}
  downloads/desktop/          桌面安装包
  logs/launcher-YYYY-MM-DD.log
  trash/                      删除的实例/运行时/失败导入
```

依据：`InstanceStore.java:22-24,43,87-89`；`Controller.java:54,67,223,314`；`RuntimeService.java:142,179,208,289,384,493`；`DesktopDownloadService.java:112`；`docs/USER_GUIDE.md:114-130`。launcher-v2.json 形如 `{"schemaVersion":1,"selected":"i-...","settings":{"proxy":"","registry":"https://registry.npmjs.org","background":""},"instances":[{"id":"i-...","name":"...","runtimeVersion":"0.2.0-rc.2","profile":"web","port":3080,"customCommand":"","accountId":""}]}`（`InstanceStore.java:37-39,114-121`）；accounts.json 的每行含 id（小写 UUID）、name、provider、baseUrl、model、encryptedKey（`AccountService.java:22,45-53,87-101,118-141`）。

### 1.7 关键机制（决定移植方式）

- **任务模型**：Controller 单 worker 串行执行变更动作 + 全局 busy 原子锁（`Controller.java:23,100-111`）；进度只以中文字符串承载（"进行中：" + 阶段，`Controller.java:64,103,106-107`），`TaskProgress` 从 pnpm 输出提取"已解析/复用/下载/写入"计数（`TaskProgress.java:10-16`）；日志 800 行环形缓冲并逐行同步写文件（`Controller.java:61-69`）。
- **状态模型**：`UiState` 13 字段不可变快照（`UiState.java:6-19`），每次操作与每次轮询都全量重建（`Controller.java:72-83`）；UI 按当前路由做差异重绘（`ShellView.java:114-125`）。
- **进程模型**：每实例一个 Node 子进程；3 个虚拟线程分别抓 descendants、逐行读 stdout（并解析启动 URL）、waitFor；停止时先 destroy 子孙再 destroy 根，3s 未退则 destroyForcibly（`ProcessService.java:26-27,31-57,74-93`）。
- **账户密钥**：DPAPI CurrentUser 加密后 Base64 存入 accounts.json；启动时内存解密，密钥仅经环境变量传给 Harness，日志按敏感值与环境变量名脱敏（`AccountService.java:46-52`；`LaunchBinding.java:11,32-35`；`ProcessService.java:109-113`）。

---

## 2. 平台耦合清单

### 2.1 总体判断

HDSL 0.2.0 的"Windows 耦合"比标题给人的印象低很多：

- **真正 Windows-only**：JavaFX 桌面外壳、DPAPI/JNA、jpackage 打包链、java.awt.Desktop 的 4 处调用、Windows 桌面安装包下载过滤。
- **已有跨平台实现或替代**：进程树终止用 JDK ProcessHandle（不是 taskkill；后者仅在旧 Swing 源码 `Launcher.java:1449`）；Node/pnpm 查找已有 `node/bin/node` 与 `pnpm.cjs` 的 POSIX 分支（`RuntimeService.java:145-176`）；pnpm shim 已有 sh 版本（`538-552`）；路径校验虽按 Windows 最严，但注释明确"在 Linux 导入时同样生效"（`SafeArchive.java:14,77-88`）——这是安全策略而非平台依赖。
- **明确不存在**：Windows 注册表调用（全库无 HKEY/Advapi32；grep 到的 "Registry" 均指 npm registry）、GBK/chcp 编码转换（0 处）、Win32 进程 API（主代码）、硬编码盘符。

### 2.2 硬耦合（必须替换或重写）

| # | 耦合点 | 具体位置 | Linux 影响 | 替换方案 |
| --- | --- | --- | --- | --- |
| 1 | JavaFX 前端与入口 | `ui/**` 共 2,765 行；`HdslApplication.java:15-32`；`Main.java:5`；`pom.xml:9-10` | 无法在服务器运行/浏览器呈现 | Vue 3 重写；入口换 Spring Boot；server 构建剔除 JavaFX 依赖 |
| 2 | DPAPI/JNA 账户加密 | `WindowsSecretProtector.java:3-4,8-22`；`AccountService.java:20`；`pom.xml:14` | Linux 保存/读取直接抛 IOException；项目文档已承认"其他平台当前没有统一账户加密后端"（`docs/accounts-and-downloads.md:16`） | 新增 SecretProtector 实现（AES-256-GCM + 主密码/环境密钥/0600 文件或 libsecret）；旧 DPAPI 密文不可解，提示重新输入 |
| 3 | java.awt.Desktop | `Controller.java:9,92,93,96,97` | headless Linux 抛异常 | 改为 API 返回 URL/路径或文件流；前端开新标签/下载 |
| 4 | jpackage 数据根与打包 | `HdslApplication.java:31`；`scripts/package-windows.ps1:23-55`；`build-icon.ps1` | 仅产 Windows 便携包 | Dockerfile / systemd 服务；数据根用 -Dhdsl.data 或 HDSL_PORTABLE_ROOT |
| 5 | Windows 桌面安装包下载 | `DesktopDownloadService.java:94-99`（windowsX64Installer 过滤 .exe/.msi/.zip、win/x64） | Linux 上无实际用途 | 产品决策：移除/隐藏页面；或改为查询 Linux 桌面端（上游是否存在【不确】） |
| 6 | Windows 工具链候选名/包装器 | `RuntimeService.java:145-176`（node.exe/npm.cmd/pnpm.cmd 优先）、`538-552`（pnpm.cmd 或 sh shim） | 已有 POSIX 分支，但需在镜像提供 linux-x64 Node+pnpm 且保持可执行位 | 预置 tools/node/bin/node 与 tools/pnpm/bin/pnpm.cjs；对齐 Node 24.x / pnpm 10.34 |
| 7 | HMCL 派生 UI/CSS | `ui/hmcl/*` 10 类 1,272 行；`hmcl-root.css`（220 行）、`hmcl-blue.css`（65 行）；`docs/HMCL_PORT.md:17-38` | JavaFX 控件与 -fx-* CSS 在浏览器不可用 | 仅参考布局/交互/色板重写 Vue 组件；保留 GPL 与上游版权声明 |

### 2.3 软耦合（已有跨平台分支或只需小改）

| 耦合点 | 位置 | 现状与 Linux 影响 |
| --- | --- | --- |
| 进程启动与终止 | `ProcessService.java:31-57,74-93,89-93`；`RuntimeService.java:617-621` | ProcessBuilder + ProcessHandle.descendants + destroy/destroyForcibly，跨平台；但按快照杀子孙对 setsid/守护化进程不保证，建议 Linux 用进程组（setsid + kill(-pgid)）或容器 pid namespace + tini |
| Web 就绪探测 | `ProcessService.java:58-73,101-108` | HTTP/1.1 + 显式 NO_PROXY 探测 127.0.0.1；新版须 401/403 且已捕获内存 bootstrap URL 才算就绪；容器内 127.0.0.1 仅容器可见，浏览器访问需代理 |
| 端口 | `ProcessService.java:94-99`（连接探测而非 bind）；`Controller.java:247-248`；`InstanceStore.java:100,121` | 逻辑跨平台；1024-65535、从 3080 起分配；容器/反向代理需规划端口映射 |
| 文件锁 | `InstanceStore.java:24-28` | FileChannel.tryLock 防同一数据目录双开；跨平台；容器单副本可用，NFS 语义需注意 |
| 路径/文件系统语义 | `InstanceStore.java:123`；`RuntimeService.java:627-632`；`SafeArchive.java:41-42,52-53,76,77-88,127-139`；`PackService.java:499-510` | Windows 保留名/尾部点空格/冒号/UNC 校验、NFC+小写判重、大小写冲突拒绝、目标链禁 symlink/junction/reparse、ATOMIC_MOVE 失败回退普通 move。对 Linux：安全策略可保留；会拒绝"仅大小写不同"的合法 Linux 包；数据卷挂载点若本身是符号链接会被拒绝；FAT/SMB 不支持 |
| 自定义启动命令 | `Controller.java:216` | Windows 用 cmd.exe /d /s /c，其他用 /bin/sh -c，已有 Linux 分支；但 Web 服务端执行用户命令是注入风险，建议首版禁用或改受控 argv |
| 环境变量与代理 | `Controller.java:251-259`；`RuntimeService.java:35-45,487-508`；`ProcessService.java:37` | 注入 HTTP(S)_PROXY、NPM_CONFIG_*、PNPM_*、PATH、XDG_CACHE_HOME；子进程清空环境后只注入调用方环境。服务端化后需避免默认继承宿主全部 env（`Controller.java:218` 目前 new HashMap<>(System.getenv())） |
| 编码 | `pom.xml:7,25`；`ProcessService.java:46`；`RuntimeService.java:596`；`InstanceStore.java:51`；`AccountService.java:82`；`AccountOverlay.java:116,158,213` | 全链路 UTF-8，无 GBK 转换；Linux 默认 UTF-8，基本零改动；中文文件名/profile 需在 URL/JSON 正确编码 |
| 字体 | `launcher.css:5,103` | 指定 Microsoft YaHei UI / Consolas；Linux 需 CJK 字体栈（Noto Sans CJK 等），CSS 全部重写 |
| 资源体积 | 背景 5 张 1672x941，单张 1.36-1.57 MB；图标 PNG 1.55 MB（本次实测） | 首屏约 9 MB；Web 建议转 WebP/多尺寸/懒加载【推测】 |
| 端口与 URL 暴露 | `RuntimeService.java:482`（--host 127.0.0.1）；`ProcessService.java:19-20,100-108` | 实例只绑 127.0.0.1，bootstrap URL 仅驻内存；Web 模式浏览器需拿到该 URL 或经代理 |

### 2.4 "疑似耦合"的澄清（经代码核对的否定项）

- **注册表**：无任何注册表 API 调用。DPAPI 是系统加密服务而非注册表写入；grep `HKEY|Advapi|Registry` 的命中均为 npm registry。
- **taskkill / cmd 拼接**：0.2.0 主代码无。`taskkill /T /F` 仅出现在不参与构建的旧 Swing `src/com/hdsl/Launcher.java:1449`。
- **盘符**：无硬编码盘符。文件选择器会把用户本机绝对路径传入后端（`ShellView.java:960,1013`；`Controller.java:193-195`），Web 端必须改为上传/下载模型。
- **解压安装**：commons-compress 处理 ZIP/TAR（`pom.xml:13`），纯 Java，无系统 unzip/7z 调用；无 Windows 目录链接创建（pnpm junction 由 pnpm 内部完成，`Controller.java:299-300` 仅为注释说明）。
- **文件锁/编码**：见 2.3，均跨平台。

### 2.5 UI/资源层耦合（Web 端必须重写）

- **自定义无边框窗口**：UNDECORATED + 6px 边沿缩放 + 标题栏拖动 + 双击最大化（`MainWindowPane.java:211,216-257`）；浏览器无法控制窗口，Web 端应删除最小化/最大化/关闭与拖动逻辑，只保留 40px 页头视觉。
- **布局尺寸语义**：标题栏 40px / 主色 #5C6BC0、侧栏 200px、右下启动按钮 200x55（`MainWindowPane.java:100,123`；`RootPage.java:19`；`UiAcceptanceTest.java:714-718`）——可平移为 CSS。
- **桌面交互**：FileChooser 打开/保存（`ShellView.java:732-736,954-960,1009-1013`）、系统剪贴板（`681-686`）、Tooltip/MenuButton/TabPane/ComboBox/Dialog（`139-140,313-329,439-445,1127-1143`）均需浏览器等价实现。
- **背景渲染**：JavaFX 中按 cover 手算 scale、内置图右对齐、自定义图居中（`MainWindowPane.java:170-205`；`BackgroundCatalog.java:11,21-43`）——Web 用 background-size:cover + position:right center。
- **HMCL 派生 CSS**：hmcl-root.css / hmcl-blue.css 均为 JavaFX 专有 -fx-* 语法，不能直接用于浏览器；色板（#5C6BC0 等）可手工转 CSS 变量。
- **界面验收测试**：`UiAcceptanceTest.java` 956 行依赖 JavaFX toolkit、截图与事件注入（`29-30,59-92,800-816`），Linux headless 不可跑；需改为 Playwright 浏览器 E2E，现有 15 条验收行为可平移为用例（见 4.5）。

---

## 3. 复用性评估

### 3.1 统计口径

分类定义：**A 原样复用** = 不改或仅改包名/配置即可编译运行；**B 小改** = 改动集中在入口/构造/少量平台分支，1-2 天量级；**C 重写** = 目标形态下代码不可用或语义不成立。行数按本次实测文件行数统计。

### 3.2 业务后端（3,494 行）

| 包 | 总行 | A 原样 | B 小改 | C 重写 | 说明 |
| --- | ---: | ---: | ---: | ---: | --- |
| core | 607 | 277 | 330 | 0 | A：Instance 7、TaskProgress 18、InstanceStore 127、ProcessService 125；B：Controller 330 |
| runtime | 838 | 189 | 649 | 0 | A：SemVer 36、Capabilities 45、PluginEntry 4、PluginService 104；B：RuntimeService 649 |
| account | 498 | 314 | 161 | 23 | A：SecretProtector 9、AccountOverlay 219、LaunchBinding 75、AccountEntry 6、ProviderPreset 5；B：AccountService 161；C：WindowsSecretProtector 23 |
| download | 610 | 382 | 228 | 0 | A：PluginCatalogService 382；B：DesktopDownloadService 228 |
| pack | 941 | 941 | 0 | 0 | PackService 684、SafeArchive 154、TarSafety 84、PackInspection 19 |
| **后端合计** | **3,494** | **2,103（60%）** | **1,368（39%）** | **23（<1%）** | 纯 Java，无 JavaFX 依赖 |

### 3.3 完整代码与测试

| 范围 | 行数 | 可保留（A/B） | 需重写/新建 | 结论 |
| --- | ---: | ---: | ---: | --- |
| 业务后端 | 3,494 | 3,471（99.3%） | 23 | 核心资产 |
| UI（JavaFX） | 2,765 | 152（UiState 141 + UiActions 11 可作 API DTO/契约） | 2,613 | 代码不可复用，交互/视觉可参考 |
| 入口（Main/HdslApplication） | 40 | 0（数据根解析逻辑可提取） | 40 | 换 Spring Boot |
| 测试（24 类/138 用例） | 3,575 | 约 2,600（约 73%，100 个用例） | 约 975（UI 验收测试） | 纯逻辑/loopback 测试可迁移 |
| 构建打包脚本（PowerShell） | 202 | 0 | 202 | 换 Dockerfile / CI |
| **合计** | **10,076** | **约 6,223（62%）** | **约 3,853（38%）** | 不含新增 Web 基础设施代码 |

### 3.4 A 类清单（原样复用）

| 文件 | 行数 | 理由与注意点 |
| --- | ---: | --- |
| runtime/SemVer.java | 36 | 纯 SemVer 比较，无依赖 |
| runtime/Capabilities.java | 45 | 帮助文本解析为能力集，保留未知参数 |
| runtime/PluginEntry.java | 4 | 纯 record |
| runtime/PluginService.java | 104 | 读写 profile package.json + 调 Harness CLI，跨平台 |
| download/PluginCatalogService.java | 382 | 纯 java.net.http + Jackson/SnakeYAML，loopback 可测（20 项测试） |
| account/AccountOverlay.java | 219 | 纯 JSON/文本/路径；读取已装 Harness 源码特征属业务逻辑而非平台调用 |
| account/LaunchBinding.java | 75 | 纯文件/环境变量；校验文件已用 POSIX 0600（`AccountOverlay.java:199,204`） |
| account/SecretProtector.java | 9 | 接口，跨平台 |
| account/AccountEntry.java / ProviderPreset.java | 6 / 5 | 纯 record |
| core/InstanceStore.java | 127 | Jackson + FileLock + 原子写 + 旧版迁移，跨平台 |
| core/ProcessService.java | 125 | JDK 进程/HTTP，无 Win32；建议增强进程组而非重写 |
| core/TaskProgress.java | 18 | 纯文本解析 |
| core/Instance.java | 7 | 纯 record |
| pack/PackService.java / SafeArchive.java / TarSafety.java / PackInspection.java | 684 / 154 / 84 / 19 | 纯 Java 归档安全逻辑，941 行；25 项测试覆盖 |
| download/DesktopDownloadService.java | 228 | HTTP/重定向白名单/ETag/SHA-256/流式下载逻辑可复用；Windows 文件过滤与产品语义需改 |
| ui/UiState.java / UiActions.java | 141 / 11 | 纯 record/接口，可直接作 API DTO 与服务边界（包名 com.hdsl.ui 建议迁到 api 包） |

### 3.5 B 类清单（小改）

| 文件 | 行数 | 需要的改动 |
| --- | ---: | --- |
| core/Controller.java | 330 | 移除 4 处 java.awt.Desktop；拆 UI 依赖（仅保留 UiState/UiActions）；加 Job/SSE/鉴权；状态字符串改枚举 + 文案；日志异步/广播；路径类动作改上传/下载 |
| runtime/RuntimeService.java | 649 | POSIX 分支已存在；补 Linux 工具发现与镜像内 Node/pnpm；环境变量白名单；超时/取消对接 Job；可选进程组增强 |
| account/AccountService.java | 161 | 默认 protector 按 OS 选择（包内构造器已留接缝，`AccountService.java:20-24`） |
| download/DesktopDownloadService.java | 228 | 若保留，改 Linux 目标或隐藏页面 |
| HdslApplication 数据根逻辑 | 约 10 | 提取为 DataRootResolver（环境变量/-D/默认 ~/.local/share/hdsl） |

### 3.6 C 类清单（重写/新建）

- **必须重写**：「ui/**」2,613 行 + `Main` + `HdslApplication` 的 JavaFX 部分（Vue 3 + TS 重写）；`WindowsSecretProtector`（Linux 密钥后端）；4 处 Desktop 动作（随 Controller 改造）；`scripts/*.ps1` 202 行（Dockerfile/CI/发布脚本）；旧 Swing `Launcher.java` 1,939 行不迁移。
- **必须新建**（不属于重写但工作量最大）：REST/Job/SSE 服务层、鉴权与安全、文件上传/下载、DSH 工作台反向代理、Docker/systemd 分发与健康检查、Vue 组件库与状态管理。前端新代码量估算 6,000~10,000 行 TypeScript/Vue【推测，基于页面与交互清单】。

### 3.7 复用比例结论

- **业务后端：约 99% 可复用**（原样 60% + 小改 39%），这是本项目最重要的可移植性资产。
- **前端：0 行 JavaFX 可执行代码复用**，但现有「全量快照 + 字符串动作 + Map 参数」的 UI 契约（`UiActions.java:7-9`；`Controller.java:85-112`）与 33 个动作表可以直接映射为 HTTP API，前端重写有明确边界。
- **测试：约 73% 可迁移**（100/138），8 个显式开关与跳过矩阵可继承；唯一不可迁移的是 JavaFX UI 验收测试，需替换为浏览器 E2E。
- **总代码行：约 62% 保留、38% 重写/新建**（不含新增 Web 基础设施代码）；实际交付风险主要来自"新增 Web 外壳"与"Linux 运行时未实测"，而非"业务逻辑要重写"。

## 4. 架构建议

### 4.1 目标形态与部署选项

| 选项 | 形态 | 改造量 | 主要代价 | 结论 |
| --- | --- | --- | --- | --- |
| **A 单机本机 WebUI** | Spring Boot 绑 127.0.0.1:8080，浏览器与启动器同机；DSH 工作台直连 127.0.0.1:实例端口（后端下发短时 token URL） | 最小 | 只能本机用；多标签页仍有并发问题；无法远程 | **推荐作为首个里程碑（M0/M1）目标** |
| **B 容器化单用户** | Docker 镜像（Temurin 21 + Node 24 + pnpm 10.34）+ 数据卷 /data；浏览器可本机或经反代远程；DSH 工作台由 Spring Boot 同源反向代理 | 中 | 需实现反代/token 透传、容器网络与数据卷、镜像构建与升级 | **推荐作为 Linux 交付形态**；与 A 共用同一代码，仅配置不同 |
| C 多用户服务器（多租户） | 每用户独立数据根、任务队列、账户密钥、资源限额、审计 | 大（+6~10 周） | 安全、鉴权、隔离、GPL/合规复杂度显著上升 | 首版**不承诺**，架构上预留（数据根可参数化） |

推荐：**代码按 B 设计、A 可运行、C 不承诺**。三者的业务代码完全共用，差异集中在启动参数、鉴权开关与反向代理。

### 4.2 总体架构

```text
Browser (Vue 3 + TS SPA)
   |  HTTP(S) / SSE（localhost 或反向代理）
Spring Boot (hdsl-server)
   |-- REST API + 静态资源（构建后的 Vue 产物）
   |-- JobManager：队列、jobId、取消、阶段/进度/日志广播
   |-- Web 安全：token/Origin/CSRF、上传限制、日志脱敏
   |-- DSH 反向代理：/instances/{id}/ -> 127.0.0.1:<port>（透传启动 token，支持 WebSocket）
   +-- 复用 hdsl-core：
         Controller（拆分后） / InstanceStore / RuntimeService / ProcessService
         PackService / SafeArchive / TarSafety / PluginService / PluginCatalogService
         AccountService / AccountOverlay / LaunchBinding / LinuxSecretProtector
   |
   +-- 文件系统：/data（launcher-v2.json、accounts.json、instances/、runtimes/、cache/、logs/、trash/）
   +-- 子进程：每个实例一个 Node DSH 进程（DSH_HOME / agents-home / workspace / 独立端口）
   |
外部：npm registry（可配/代理）、GitHub API、DeepSeek 官网、dspack 市场（可选）
```

### 4.3 代码组织与 Maven 模块划分

建议拆成三模块（父 POM 聚合），保留原包名以减少迁移 diff：

```text
hdsl/ (parent)
  hdsl-core/    core/runtime/account/download/pack + api 包（UiState/UiActions 迁入 com.hdsl.api）
                依赖：Jackson、SnakeYAML、commons-compress；不依赖 JavaFX/JNA/Spring
  hdsl-server/  Spring Boot 应用：REST、Job/SSE、安全、反代、静态资源
                依赖：hdsl-core、spring-boot-starter-web（按需 security）
  hdsl-web/     Vue 3 + TS + Vite；构建产物复制到 hdsl-server 的 static/ 或独立静态托管
  packaging/    Dockerfile、systemd unit、健康检查脚本、发布说明
```

- 理由：把纯逻辑与 Web 外壳分离，测试可直接在 hdsl-core 上跑；server 可剔除 JavaFX/JNA 依赖；前端可独立开发调试。
- 代价：包名/依赖配置调整（小），需处理 UiState/UiActions 的包迁移。
- Spring Boot 版本：Java 21 基线，实施时锁定当时稳定版（本报告不预设具体版本号）。
- 备选后端方案（供决策）：① 保留 Java 业务层 + Spring Boot（推荐，复用最大化）；② 用 Node/NestJS 重写后端（可统一 TS 技术栈，但业务层 99% 复用化为零，风险高，不推荐）；③ 双服务混合（Java 管业务、Node 管前端 BFF，过度设计，不推荐）。

### 4.4 Spring Boot API 设计

**总原则**：保留 33 个动作的语义与参数键；资源化路径；读接口返回 DTO，长任务返回 202 + jobId；进度/日志走 SSE；错误统一为 {code, message, details}；状态用枚举 + 中文文案双字段（现状中文状态串见 `ProcessService.java:28`、`Controller.java:64,103,107,317`）。

**读接口（来自 refresh/snapshot 与发现类动作）**

| 端点 | 对应原动作 | 说明 |
| --- | --- | --- |
| GET /api/state | refresh | 聚合快照（首屏/轮询兜底）；建议返回 version/ETag 供增量判断 |
| GET /api/instances | refresh / snapshot | 实例列表（含状态、profile、端口、路径脱敏） |
| GET /api/instances/{id} | openInstanceFolder | 元数据；路径可返回相对/不透明标识 |
| GET /api/runtimes | refresh | 已安装运行时 + 兼容性摘要 |
| GET /api/runtimes/available | refreshVersions | npm 可用版本（60s 超时，`RuntimeService.java:54-86`） |
| GET /api/accounts | refresh | 账户公开元数据（无密钥，`UiState.java:102-107`） |
| GET /api/settings | refresh | proxy/registry/background（新增加服务端设置单独配置文件） |
| GET /api/logs/tail?lines=800 | refresh | 日志尾部（环形缓冲 800 行，`Controller.java:65-67`） |
| GET /api/instances/{id}/plugins | refreshPlugins | 插件列表（只读，`PluginService.java:17-59`） |
| GET /api/plugin-catalog?q=&page=&refresh= | searchPluginCatalog | GitHub dsh-plugin 目录 |
| GET /api/plugin-catalog/{owner}/{repo}?refresh= | inspectPluginProject | 安装目标解析 |
| GET /api/desktop/releases?source= | refreshDesktop | Windows 桌面发行版（如保留） |
| GET /api/jobs/{id} | — | 任务状态轮询兜底 |
| GET /api/jobs/{id}/events | — | SSE：status/phase/log/result/error（推荐） |
| GET /api/health | — | 健康检查（容器用） |

**写接口（变更类动作，长任务标 *）**

| 端点 | 对应原动作 | 返回/说明 |
| --- | --- | --- |
| POST /api/instances | createInstance | 201 + Instance；参数 name/version/profile/port/accountId |
| PUT /api/instances/{id} | saveInstance | 200；换版本会复制 profile（`Controller.java:268-272`） |
| DELETE /api/instances/{id} | deleteInstance | 200 + trash 路径（`Controller.java:198,314`） |
| POST /api/instances/{id}/copy | copyInstance | *202（导出+导入，`Controller.java:275-284`） |
| PUT /api/instances/{id}/selection | selectInstance | 200 |
| POST /api/instances/{id}/launch | launch | *202（安装最长 20 分钟 + 就绪 90s，`Controller.java:207-242`） |
| POST /api/instances/{id}/stop | stop | 200（同步 3+4s 进程树停止，`ProcessService.java:74-88`） |
| GET /api/instances/{id}/web-url | openWeb | 返回内存 bootstrap URL（含 token）或反代地址；仅运行中可用（`Controller.java:245`） |
| PUT /api/instances/{id}/account | bindAccount | 200；要求实例已停止（`Controller.java:183,250`） |
| POST /api/instances/{id}/plugins | addPlugin / installCatalogPlugin | *202；目录安装需先经 inspect 校验（`Controller.java:185`） |
| PATCH /api/instances/{id}/plugins/{name} | updatePlugin | *202 |
| DELETE /api/instances/{id}/plugins/{name} | removePlugin | *202 |
| POST /api/runtimes | installRuntime | *202（pnpm 安装超时 20 分钟，`RuntimeService.java:209`） |
| POST /api/runtimes/{version}/probe | probeRuntime | *202（35-60s 帮助探测，`RuntimeService.java:382-401`） |
| DELETE /api/runtimes/{version} | removeRuntime | 409 若实例引用（`Controller.java:190`） |
| POST /api/accounts | saveAccount | 201；不回显密钥；编辑空 key 保留旧密文（`AccountService.java:32-55`） |
| PUT /api/accounts/{id} | saveAccount | 200 |
| DELETE /api/accounts/{id} | deleteAccount | 409 若实例引用（`Controller.java:182`） |
| PUT /api/settings | saveSettings | 200（校验见 `Controller.java:251-259`） |
| POST /api/packs/preview | previewPack | 上传文件 + 200 预览（替代服务器绝对路径） |
| POST /api/packs/import | importPack | *202（上传 + 导入；暂存与回滚语义保留，`Controller.java:286-311`） |
| POST /api/packs/export | exportPack | *202，完成后返回下载链接/流（替换写服务器路径） |
| POST /api/jobs/{id}/cancel | cancelTask | 200；映射 interrupt + 协作式取消（`Controller.java:94,173`） |
| POST /api/desktop/downloads | downloadDesktop | *202（如保留该功能） |
| GET /api/desktop/assets/{id}/file | openDownloadFolder | 返回文件流/下载链接（如保留） |

**Job/SSE 协议建议**

- POST 长任务 → 202 {jobId}；GET /api/jobs/{jobId} → {state, phase, startedAt, finishedAt, error, resultRef}。
- SSE /api/jobs/{jobId}/events：事件类型 status（阶段）、log（行 + 序号）、result（完成）、error（失败）；带心跳与 Last-Event-ID；断线重连从日志序号续传。
- 复用现有日志汇聚点（`Controller.java:61-69` 的 Consumer<String> log）改广播即可；日志环形缓冲 800 行继续作为回放源。
- 超时对齐：pnpm 安装 20 分钟、launch 就绪 90s、账户校验 20s、帮助探测 35/60s、桌面下载 15 分钟/2GiB（`RuntimeService.java:209,606`；`Controller.java:240`；`LaunchBinding.java:39`；`DesktopDownloadService.java:26,148`）。反代读超时须大于这些值，SSE 用心跳保活。
- 兼容旧语义：忙时返回 409（原「当前有任务正在执行」）；前端可轮询兜底。

**鉴权与安全（Web 化新增）**

- 默认只绑 127.0.0.1；启动生成随机 token（写 0600 文件或启动日志提示），前端同源获取；所有写操作校验 Origin/Host 与 token；接口不接受任意服务器路径（改为上传/受控目录/不透明 id）。
- 保留日志脱敏（`ProcessService.java:109-113`），并确保账户密钥不进 API/日志/SSE；环境变量继承改为白名单（`Controller.java:218` 现继承 System.getenv()）。
- 首版禁用 customCommand（`Controller.java:213-216`）或仅管理员可配置；禁止把整合包/插件清单字段拼接进 shell。
- 若未来远程/多用户：加登录、每用户数据根、任务隔离与资源限额（不在首版）。

### 4.5 Vue 3 前端还原方案

**技术栈**：Vue 3 + TypeScript + Vite + Vue Router + Pinia；HTTP 用 fetch/axios；SSE 用 EventSource。组件库可选（Element Plus/Naive UI 用于表单/对话框，或自研轻量组件以贴近 HMCL 视觉）。推荐**自研外壳 + 轻量组件库**：外壳布局严格对齐参考界面，表单控件可用库以省时间。

**路由与页面**（与现有 8 路由对应）：/（首页）、/instances、/runtimes（"下载"）、/accounts、/plugins、/packs、/logs、/settings。

**组件映射**

| 现有 JavaFX 组件 | Vue 组件 | 要点 |
| --- | --- | --- |
| MainWindowPane（40px 标题栏 + 窗口按钮） | AppShell.vue + PageHeader.vue | 浏览器不提供窗口按钮，删除最小化/最大化/关闭与拖动；保留标题/任务进度/返回 |
| RootPage（200px 三组侧栏） | SideNav.vue | 组标题 + 分隔线 + 7 个导航项 + 当前实例项；aria-current |
| AdvancedListBox / TwoLineListItem / ClassTitle | NavList.vue / NavItem.vue / GroupLabel.vue | 悬停滚动条、双行布局、选中态 |
| MainPage（右下启动区 + 切换器 + 箭头动画） | LaunchPane.vue / InstanceSwitcher.vue | 200x55 双行按钮、200ms 箭头旋转、菜单改页内下拉 |
| RipplerContainer | CSS hover 120ms | 无 JavaFX 动画依赖 |
| 8 个页面 + 任务卡 + 日志页 | views/*.vue | 页面结构按 4.4 的接口取数；任务卡阶段/计时/计数；日志仅当在底部时自动滚动（`ShellView.java:696-706`） |
| Dialog/Alert/TextInputDialog | Modal.vue / ConfirmDialog.vue / toast | 统一焦点管理与 ESC 关闭 |
| FileChooser（导入/导出/背景） | 上传组件 + 下载链接 | 改造后端为 upload/asset API；背景存数据目录 assets/ 并以 URL 提供 |
| 剪贴板 | navigator.clipboard.writeText | 需 HTTPS/localhost；失败回退 textarea |
| 背景选择/渲染 | CSS background-size:cover + position:right center | 内置图 + 自定义图统一走服务端资源 URL |

**状态管理**：Pinia store 按 UiState 的 13 个字段拆分（instances/runtimes/accounts/plugins/catalog/desktop/settings/logs/status/currentInstanceId/packPreview）；动作层封装 dispatch(action,args) → REST；轮询 1.5s 作为兜底，SSE 优先；未保存表单在轮询到达时不得被覆盖（现桌面版用同样语义，`ShellView.java:107-108,721-724`；`UiAcceptanceTest.java:113-122`）；busy 时按钮全局禁用、写操作返回 409 时提示排队/重试。

**交互还原要点（来自验收行为）**：启动按钮四态（无实例→创建；未安装→下载并启动；运行中→打开 Web + 停止；已装→启动）；任务卡阶段/已用时/四项计数（不造百分比）；实例表单校验（名称 1-160、端口 1024-65535、profile 规则）；账户表单（provider 预设自动填 baseUrl/model、密码提交/取消后清空、编辑留空保留）；导出先选范围（默认当前 profile）；版本查询不占全局 busy；背景 5 张 + 自定义 + 重置；版权与 GPL/附加条款可离线查看（`ShellView.java:778-787`；`UiAcceptanceTest.java:124-709`）。

**E2E 验收**：把现有 `UiAcceptanceTest` 的 15 条行为平移为 Playwright 用例；现有节点 id（page-/nav-/instance-/account-/background-/window-/task-/catalog-）改 data-testid；最小窗口尺寸（960x650）改为视口断点；中文 profile（如"研究 工作区"）与长名称作为必测项。

### 4.6 数据与状态对应

- **磁盘格式保持兼容**：launcher-v2.json（schemaVersion=1）与实例目录结构不变，便于从 Windows 迁移数据；accounts.json 的 encryptedKey 字段换成 Linux 密文（增加 "kdf"/"protector" 标识，旧 DPAPI 密文标记为"需重新输入"而不自动删除）。
- **API 状态契约**：首版继续用 UiState 作为聚合 DTO（Jackson 可直接序列化 record），前端 TypeScript 接口按字段镜像；后续再拆分为资源化 DTO。注意不要在响应中暴露服务器绝对路径（`UiState.InstanceItem.path` 现为绝对路径）。
- **状态文案**：后端返回枚举 + 中文 message；前端 i18n 兜底，避免把日志/中文硬编码散落在服务端。
- **日志**：保留 800 行环形缓冲 + 按日文件日志（`Controller.java:61-69`）；SSE 以行序号支持断点续传。
- **设置**：现有 settings（proxy/registry/background）保留；新增服务端设置（bind 地址、端口、token、数据根、反向代理开关）放到独立 server 配置文件或环境变量；background 从绝对路径改为资源 id/URL（否则换机/Linux 失效，`BackgroundCatalog.java:28-40` 现存绝对路径）。
- **迁移策略**：复制整个数据目录 → 校验 schemaVersion → 账户标记重新输入 Key → 实例目录/dsh-home 原样可用；Windows 独有的 desktop 下载目录与旧 DPAPI 账户不可用；整合包内符号链接/权限位在 Linux 导入时按新规则校验（见风险 13）。

### 4.7 Web 场景必须换实现的能力

| 能力 | 桌面版现状 | Web 场景实现 | 备注 |
| --- | --- | --- | --- |
| 本地文件访问（导入/导出/背景） | FileChooser 传绝对路径（`ShellView.java:960,1013`；`Controller.java:193-195`） | 上传 API + 服务端暂存 + 下载流；背景上传为资源 | 必须限制大小/类型/清理；禁止任意路径参数 |
| 打开浏览器/文件夹 | java.awt.Desktop（`Controller.java:92-97`） | 返回 URL/文件流；前端新标签/下载 | 实例文件夹无浏览器等价物，需产品决策（显示路径/打包下载） |
| DSH 工作台访问 | 直接打开 http://127.0.0.1:port/?token=...（内存 URL，`ProcessService.java:100-108`） | A 直连：后端校验后把 URL 交给前端；B 反代：Spring Boot 代理 /instances/{id}/ 并透传 token/WS | 反代需处理 401、cookie、WebSocket、绝对 URL；token 仅短时有效且不写日志 |
| 进程托管 | 桌面进程内管理子进程（`ProcessService.java:31-57`） | 服务器进程管理 + 进程组/cgroup；容器内用 tini；崩溃重启策略 | 防止孤儿进程与端口泄漏 |
| 端口占用 | 连接探测 + 端口分配（`ProcessService.java:94-99`；`Controller.java:247-248`） | 保留；容器内端口仅容器可见，通过反代暴露 | 避免与宿主端口冲突 |
| 账户密钥 | DPAPI（`WindowsSecretProtector`） | LinuxSecretProtector：AES-256-GCM，密钥来自环境变量/主密码派生/0600 文件或 libsecret | 旧密文不可迁移；支持轮换 |
| 单实例锁 | config/launcher.lock | 保留（防多进程写同一数据目录）；Web 多会话由任务队列处理 | 容器单副本 |
| 剪贴板 | 系统剪贴板（`ShellView.java:681-686`） | navigator.clipboard | 权限/HTTPS 限制 |
| 窗口管理 | 自定义标题栏/拖动/缩放（`MainWindowPane.java:211-257`） | 页头 + 浏览器行为 | 无桌面壳时不可还原 |
| Windows 桌面安装包下载 | 下载 .exe/.msi/.zip（`DesktopDownloadService.java:94-99`） | 移除/隐藏；或改为 Linux 桌面端（上游是否有 Linux 安装包【不确】） | 产品决策 |
| 自定义启动命令 | cmd/sh -c（`Controller.java:216`） | 首版禁用或管理员白名单 | 命令注入风险 |
| 多用户 | 无（全局单例 Controller） | 首版不做；架构预留数据根参数化 | 见 4.1 选项 C |

**DSH 工作台访问的三个技术选项**（需你拍板，见 5.3 Q1）：

1. **直连 loopback**：前端拿到后端签发的短时 URL 后直接打开 127.0.0.1:port。改动最小，但只适用于浏览器与启动器同机；多标签页/多实例可并行。
2. **同源反向代理（推荐用于容器/远程）**：Spring Boot 提供 /instances/{id}/ 反向代理，注入/透传 token，支持 WebSocket；浏览器只访问一个源，便于鉴权。代价：需处理 DSH 的 401/绝对 URL/WS，且要防止 SSRF（只允许本机回环目标）。
3. **改 DSH 绑定 0.0.0.0**：依赖 DSH 自身鉴权暴露到网络，安全风险高、且上游是否支持 --host 0.0.0.0 未验证，不推荐。

### 4.8 并发、任务与取消

- **JobManager**：单变更 worker（保持 pnpm store 与实例目录串行安全）+ 发现类线程池（版本查询/目录查询/桌面查询并行，现有架构已如此，`Controller.java:23-24,87-88,137-169`）；FIFO 队列 + 最大排队数；任务状态 PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED。
- **取消**：POST /api/jobs/{id}/cancel → 置 cancel 标志 + interrupt 工作线程（沿用 `Controller.java:94,173` 的协作式检查）；进程停止独立为 POST /api/instances/{id}/stop（确保即使任务被杀也能停进程）。
- **实例级锁**：launch/stop/save/delete 对同一实例互斥，避免桌面版"靠全局 busy"掩盖的问题。
- **SSE**：一任务一事件流或全局多路复用（推荐全局 /api/events?topics=job:{id},logs）；心跳 15s；断线续传；日志量大时只推增量行。
- **前端**：busy 态禁用写操作；重复提交幂等键（避免网络重试重复导入）。

### 4.9 构建、CI 与分发

- **构建**：父 POM + Maven，前端 pnpm build 产物打入 server 静态资源；去掉 JavaFX/JNA 依赖；保留 commons-compress/Jackson/SnakeYAML。
- **CI**：ubuntu-latest 跑 build+unit tests；可选 windows-latest 保留旧行为对照；新增 Docker 构建与镜像扫描；发布产物：jar、Docker 镜像、源码包（GPL 对应源码）。现有 CI 仅 mvn verify（`ci.yml:9-22`），且 Linux 未实跑（`docs/VALIDATION.md:100`）。
- **镜像**：基础 eclipse-temurin:21-jre；固定安装 Node 24.x 与 pnpm 10.34.0（校验和）；非 root 用户；/data 卷；HEALTHCHECK GET /api/health；容器 init/tini 处理僵尸进程。
- **运行方式**：① docker run -p 127.0.0.1:8080:8080 -v hdsl-data:/data hdsl:latest（单用户本机）；② jar + systemd（无容器）；③ 开发模式 Vite dev server 代理到 Spring Boot。
- **版本/升级**：保留 VERSION 与数据 schemaVersion；升级镜像不改 /data 格式，新增迁移逻辑。
- **许可**：整仓 GPL-3.0-or-later + HMCL §7 附加条款（`pom.xml:6`；`LICENSES/HMCL-ADDITIONAL-TERMS.md`）；分发二进制/镜像时须提供对应源码并保留可见版权（现「关于」页显示 HMCL 版权，`ShellView.java:780`）。Web 前端若明显沿袭 HMCL 视觉，建议继续保留署名与许可；是否构成衍生作品需法务判断【不确】。

---

## 5. 风险与未知项

### 5.1 风险清单

| # | 风险 | 证据 | 影响 | 可能性 | 缓解 |
| --- | --- | --- | --- | --- | --- |
| 1 | DSH 工作台浏览器可达性/鉴权 token | `ProcessService.java:19-20,58-73,100-108`；`docs/runtime-compatibility.md:35` | 高 | 高 | M0 PoC：直连与反代各验证一次；token 短时下发、不写日志、支持 WebSocket |
| 2 | 进程树终止对守护化进程不保证 | `ProcessService.java:77-79,89-93`；`RuntimeService.java:617-621` | 中 | 中 | setsid/进程组 kill(-pgid) 或容器 pid namespace + tini；停止后校验端口释放 |
| 3 | Linux 上 Node/pnpm/原生模块 ABI 未实测 | `RuntimeService.java:145-176`；现有验证均在 Windows | 高 | 中 | M0 在 Ubuntu x64/ARM64 实测真实 DSH 版本；固定 Node/pnpm 版本；准备 CI |
| 4 | AccountOverlay 依赖上游 JS 源码特征 | `AccountOverlay.java:15-77,87-92` | 中 | 高（上游升级时） | 能力探测 + 明确失败；账户可解绑；维护适配测试矩阵 |
| 5 | DPAPI 密文不可迁移 | `docs/accounts-and-downloads.md:16`；`WindowsSecretProtector.java:9-21` | 中 | 确定 | 迁移时提示重新输入；不自动删除旧文件；备份原目录 |
| 6 | 文件模型改为上传/下载后语义变化 | `Controller.java:193-195`；`ShellView.java:960,1013` | 中 | 确定 | 设计 asset API + 临时文件清理 + 大小上限；UI 明示"上传"语义 |
| 7 | 单用户全局忙锁与多标签页冲突 | `Controller.java:100` | 中 | 高 | Job 队列 + 409/排队提示 + 每实例锁；前端串行化写操作 |
| 8 | 无鉴权/路径遍历/命令注入（服务端化后） | Controller 全部动作；`Controller.java:213-216` | 高 | 中 | 默认 localhost + token + Origin；禁 customCommand；参数白名单；上传校验 |
| 9 | 反向代理超时/SSE 断流 | 20 分钟安装、90s 就绪（`RuntimeService.java:209`；`Controller.java:240`） | 中 | 中 | SSE 心跳、读超时配置、轮询兜底、任务状态持久化 |
| 10 | 许可合规 | `pom.xml:6`；`docs/HMCL_PORT.md:17-52` | 高 | 中 | 保留声明与对应源码；法务确认前端重写后的边界；不改许可头 |
| 11 | UI 还原度与工作量 | ui 2,765 行；`UiAcceptanceTest.java` 956 行 | 中 | 高 | 分页面里程碑；先核心后边缘；Playwright 验收；可选组件库提速 |
| 12 | 背景资源体积与首屏 | 5 张背景约 7.3 MB + 图标 1.55 MB（实测） | 低 | 高 | 转 WebP/缩略图/懒加载 |
| 13 | 整合包路径规则对 Linux 过严 | `SafeArchive.java:41-53,77-88`；`PackService.java:499-510` | 中 | 中 | 提供诊断信息/兼容模式开关；真实 Linux 包测试 |
| 14 | 数据卷文件锁/权限/NFS | `InstanceStore.java:24-28` | 低 | 中 | 单副本部署；数据目录 0700；文档说明 NFS 不支持 |
| 15 | 多用户/公网暴露未设计 | 全局单例 Controller/InstanceStore | 高 | 低（首版不做） | 明确边界；架构预留；后续再实现 |
| 16 | Windows 桌面下载功能语义错位 | `DesktopDownloadService.java:94-99` | 低 | 确定 | 移除/隐藏页面 |
| 17 | 上游 DSH 版本演进（CLI 参数/profile/插件） | `Capabilities` + compatibility.json 机制 | 中 | 高 | 沿用兼容层与人工声明；维护测试矩阵与发布节奏 |

### 5.2 未知项（必须 PoC 验证，不能靠推断）

1. DSH 0.1.x / 0.2.x 在 Linux x64（及 ARM64）上 install + launch + ready 是否与 Windows 一致；原生插件/optionalDependencies 的 ABI 是否可用。
2. DSH 工作台是否允许被 iframe 嵌入/反向代理（CSP、Origin、WebSocket、绝对 URL、cookie）；bootstrap token 在代理下如何保持有效。
3. DSH 是否提供 Linux 桌面端安装包（决定"桌面下载"页面去留）。
4. pnpm 在 Linux 对包内绝对链接/junction 的处理差异；真实整合包在 Linux 的导入成功率。
5. 现有测试在 Linux 的实际通过率（CI 配置存在但未运行，`docs/VALIDATION.md:100`）。
6. HMCL 派生视觉若重写，许可边界（法务）。
7. 目标 Linux 发行版/架构（x86_64 / ARM64）、是否容器、是否有 systemd。
8. 多用户/远程访问是否真实需求（决定鉴权与隔离投入）。

### 5.3 必须由你回答才能定下来的问题清单（按阻塞优先级）

| # | 问题 | 选项与代价 | 我的推荐默认值 |
| --- | --- | --- | --- |
| Q1 | WebUI 部署与访问形态？ | A 单机本机浏览器（最简单）；B 容器/远程访问（需反代+鉴权）；C 多用户服务器（工作量大） | A 起步、B 交付、C 不做 |
| Q2 | 是否需要迁移现有 Windows 便携目录的数据？ | 迁移（实例清单/整合包可复用，API Key 需重输）；或全新开始 | 支持迁移，Key 重输 |
| Q3 | Linux 上"桌面版下载"页面如何处理？ | 移除 / 隐藏 / 改为查询 Linux 端（上游是否有【不确】） | 首版移除 |
| Q4 | 前端还原度？ | 1:1 复刻 HMCL 视觉（含 GPL 声明）；或重新设计现代界面 | 保留布局与色板，视觉可现代化，保留版权声明 |
| Q5 | 账户密钥方案？ | 主密码（每次解锁）/ 环境变量密钥（容器友好）/ 系统 keyring / 0600 文件（最简） | 容器用环境变量，本机用 0600 文件；后续可选 keyring |
| Q6 | 分发形态？ | Docker 镜像 / jar + systemd / deb/AppImage / 仅源码 | Docker 镜像 + jar |
| Q7 | 功能范围？ | 首版 1:1 全功能；或按里程碑逐步交付 | 按里程碑（见第 6 节） |
| Q8 | 并发与多会话？ | 单会话即可；或要求多标签页/多人同时操作 | 支持多标签页（任务队列），不支持多用户 |
| Q9 | 认证？ | 本机 localhost 免登录；或密码/令牌 | localhost + 随机 token，无登录 |
| Q10 | 是否保留「自定义启动命令」？ | 保留（灵活但有注入风险）；或禁用 | 首版禁用 |
| Q11 | 目标发行版与架构？ | Ubuntu 22.04/24.04、Debian、其他；x86_64 或含 ARM64 | Ubuntu x86_64 优先 |
| Q12 | 发布与开源范围？ | 开源继续 GPL-3.0-or-later；或闭源（需法务处理 HMCL 派生与 GPL 义务） | 继续 GPL-3.0-or-later |
| Q13 | 是否允许删除 JavaFX/JNA 依赖并重写 UI？ | 允许（推荐）；或保留 JavaFX（则不是 WebUI） | 允许 |
| Q14 | 首版是否需要公网/远程访问与 HTTPS？ | 不需要（本机/内网）；或需要 | 首版不需要，后续可选 |

---

## 6. 结论与建议里程碑

### 6.1 可移植性结论

**结论：可行，属中低风险工程。** 判断依据：

1. **业务层已跨平台**：3,494 行业务代码中约 99% 可原样或小改复用；Windows 硬耦合只有 DPAPI（23 行）与桌面外壳/打包（2,800 余行，但本就需重写）。
2. **UI 与业务已解耦为良好契约**：「异步动作 + 全量快照」的 UiActions/UiState 接口天然适合映射为 HTTP/SSE（`UiActions.java:7-9`；`UiState.java:6-19`）。
3. **安全与健壮性逻辑可继承**：归档安全校验、下载校验、进程所有权、日志脱敏、取消/超时、原子写与文件锁等均已实现，迁移后可继续使用。
4. **数据格式自描述**：launcher-v2.json/accounts.json/实例目录/整合包格式与平台无关（除 DPAPI 密文与 Windows 路径习惯），迁移成本可控。

**真正难点**：不是"平台 API 改写"，而是 Web 化带来的产品与安全设计（DSH 工作台浏览器可达性、文件模型、密钥、任务并发、多用户边界）与前端重写。

**工作量粗估**（1 名全栈 + AI 辅助，仅供参考）：后端服务化 3~4 周；Vue 前端 5~8 周；账户/插件/整合包 3~4 周；Docker/CI/安全 1~2 周；测试迁移与验收 2~3 周。顺序执行约 14~20 周，合理并行可压缩到 **12~16 周**；不含多用户与公网加固。

### 6.2 推荐路线

- **后端**：保留 Java 业务层（99% 复用）→ 拆 hdsl-core / hdsl-server → Spring Boot 提供 REST + Job/SSE + 反代 + 静态资源。
- **前端**：Vue 3 + TS + Vite + Pinia 重写 8 页面与对话框；先核心页面后边缘功能；Playwright 验收。
- **账户**：可插拔 SecretProtector（Linux AES-GCM + 密钥来源可配）；旧 DPAPI 密文标记重新输入。
- **交付**：Docker 镜像（内嵌 JDK 21 + Node 24 + pnpm 10.34）+ 数据卷；同时保留 jar + systemd。
- **安全**：默认 localhost + token + Origin 校验；禁用 customCommand；上传/下载白名单；日志脱敏。
- **许可**：继续 GPL-3.0-or-later + HMCL 附加条款，保留可见版权与源码提供。

### 6.3 分阶段实施顺序（里程碑）

| 阶段 | 周期（估） | 内容 | 验收标准 |
| --- | --- | --- | --- |
| **M0 决策与 PoC** | 1.5~2 周 | 回答 Q1/Q5/Q6/Q11；在 Ubuntu 直接复用 Controller+InstanceStore+ProcessService+RuntimeService 跑通「安装运行时→启动→就绪→停止→端口释放」；验证 DSH 工作台直连/反代/token；记录 Linux 测试通过率 | Linux 上真实安装并启动 1 个 DSH 版本，浏览器打开工作台 HTTP 200；停止后端口释放；PoC 报告更新风险表 |
| **M1 后端服务化核心** | 3 周 | hdsl-core/hdsl-server 拆分；实例/运行时/设置/日志 REST；JobManager+SSE；Linux SecretProtector；文件上传/下载基础；剔除 JavaFX/JNA；迁移 A 类测试 | API 契约测试通过；core/runtime/pack/download 的 A 类测试在 Linux 通过；两实例并发操作不乱序 |
| **M2 Vue 核心 UI** | 4~5 周 | AppShell/侧栏/首页/实例/下载(运行时)/日志/设置；对接 REST/SSE；背景/字体/CSS；Playwright 核心用例 | 8 页面中 5 个可用；启动/停止/切换实例/任务进度/日志可用；UiAcceptanceTest 15 条行为在浏览器复现 |
| **M3 账户/插件/整合包** | 3.5~4 周 | Linux 密钥后端 + 账户注入；插件列表/安装（含目录核验）；整合包上传/检查/导入/导出；DSH 反代完善 | 账户绑定启动校验 ok；真实 .dspack 导入成功；插件增删；导出可再导入 |
| **M4 打包/安全/发布** | 2~3 周 | Dockerfile + CI（Linux build/test/package）；健康检查/数据卷/日志；token/Origin/上传限额；许可与源码包；迁移文档；端到端验收 | 干净 Linux 用 docker run 完成 M0 全流程；升级保留数据；安全清单通过；文档齐全 |

依赖关系：M0 → M1 → M2 与 M3 可部分并行（M3 依赖 M1 的 API，前端 M2 与后端 M1 契约先行）；M4 最后。每个里程碑结束做一次《移植实施方案》修订与风险复评。

### 6.4 下一步（等待你的反馈）

1. 请先回答 5.3 的问题清单；按我们的沟通约定，建议从 **Q1（部署与访问形态）** 开始，一次一个。
2. 你确认方案后，我再产出《移植实施方案》与任务拆解（模块、接口契约、里程碑细化），**在得到你确认前不开始写实现代码**。
3. 本报告写入 D:/Projects/DSHL/PCL-DSH-Linux/docs/PORTING_ANALYSIS.md，是本次唯一创建的文档。

---

## 附录 A 代码规模与关键文件（实测）

| 范围 | 文件数 | 行数 | 说明 |
| --- | ---: | ---: | --- |
| src/main/java | 39 | 6,299 | 业务 + JavaFX UI + 入口 |
| src/test/java | 24 | 3,575 | 138 个 @Test |
| src/com/hdsl/Launcher.java | 1 | 1,939 | 旧 Swing，不参与构建 |
| 构建/打包脚本 | 5 | 202 | build.ps1、package-windows.ps1、package-source.ps1、archive-preview.ps1、build-icon.ps1 |
| 文档 docs/*.md | 7 | 约 700 | 含 HMCL 移植、验证记录、格式说明 |
| 资源 | — | 约 9 MB | 5 张背景、图标、CSS、许可文本 |

关键文件：`src/main/java/com/hdsl/core/Controller.java`（编排）、`core/ProcessService.java`（进程/就绪）、`runtime/RuntimeService.java`（安装/能力/启动命令）、`account/AccountOverlay.java`（账户注入）、`pack/PackService.java`（整合包）、`ui/ShellView.java`（界面）、`ui/UiState.java`（状态契约）、`src/test/java/com/hdsl/ui/UiAcceptanceTest.java`（界面验收基线）。

## 附录 B 测试资产迁移分类

| 测试类 | 用例数 | 迁移性 | 说明 |
| --- | ---: | --- | --- |
| pack/PackServiceTest | 25 | A 可直接迁移 | 合成归档，覆盖格式/路径/链接/vendor/导出往返 |
| download/PluginCatalogServiceTest | 20 | A | loopback 模拟 GitHub/npm |
| download/DesktopDownloadServiceTest | 14 | A | loopback 下载/ETag/摘要/取消 |
| runtime/RuntimeServiceTest | 12 | A/B | 5 项依赖 Node 夹具（Assumptions） |
| account/AccountServiceTest | 11 | A/B | 仅 1 项 DPAPI 用 assumeTrue(Windows)（`:39`） |
| core/ProcessServiceTest | 9 | A | 本机 java 子进程 + loopback |
| runtime/ReleaseCohortCacheTest | 7 | A | 全夹具 |
| core/ControllerTest | 7 | A | FakeRuntime 替换外部边界 |
| core/DiscoveryCancellationTest / VersionRefreshTest / InstanceStoreTest / TaskProgressTest / FirstLaunchProgressTest | 4+4+4+2+1 | A | 发现/取消/存储/进度 |
| runtime/PluginServiceTest | 5 | A/B | 1 项需 Node |
| runtime/VersionQueryHttpTest | 3 | A | loopback HTTP |
| core/AccountReferenceTest | 2 | B | 整体 @EnabledOnOs(WINDOWS)（`:12`） |
| 集成类（RuntimeIntegrationTest、ControllerRuntimeIntegrationTest、AccountRuntimeIntegrationTest、DesktopDownloadIntegrationTest、PluginCatalogLiveTest、PackRealFixtureTest、PackDependencyIntegrationTest） | 各 1 | B | 需真实运行时/网络/真实包，有显式开关 |
| ui/UiAcceptanceTest | 1（956 行） | C 不可迁移 | 依赖 JavaFX Stage/截图/事件注入；改 Playwright |

可继承的跳过开关：-Dhdsl.uiTest、-Dhdsl.integration、-Dhdsl.controllerIntegration、-Dhdsl.realPackFixtures、-Dhdsl.packDependencyIntegration、-Dhdsl.account.integration、-Dhdsl.desktopIntegration、-Dhdsl.catalogLive（分别在对应测试类中定义）；另 DPAPI assumeTrue 与 Node 缺失 abort 需替换为 Linux 条件。

## 附录 C 关键结论证据索引

| 结论 | 证据 |
| --- | --- |
| 业务层是纯 Java、与 JavaFX 解耦 | Controller 仅依赖 UiActions/UiState（`UiActions.java:7-9`；`UiState.java:6-19`）；core/runtime/account/download 无 JavaFX import |
| 进程树终止非 Windows 专有 | `ProcessService.java:31-57,74-93`；`RuntimeService.java:617-621`；taskkill 仅在旧源码 `Launcher.java:1449` |
| Node/pnpm 已有 POSIX 分支 | `RuntimeService.java:145-176,538-552`；打包脚本仅 Windows 部分需替换（`package-windows.ps1:26-42`） |
| DPAPI 是唯一账户加密实现 | `WindowsSecretProtector.java:8-22`；`AccountService.java:20`；`docs/accounts-and-downloads.md:16` |
| 任务模型为单 worker + 全局忙锁 | `Controller.java:23,100-111,318-328` |
| 状态为全量快照、UI 轮询 1.5s | `UiState.java:6-19`；`HdslApplication.java:24-25`；`Controller.java:72-83` |
| 账户注入链路 | `AccountService.java:66-85`；`AccountOverlay.java:15-77,123-216`；`LaunchBinding.java:21-54` |
| 整合包导入事务边界 | `Controller.java:286-311`；`PackService.java:148-158`；`SafeArchive.java:14,77-88,127-139` |
| 构建与发布链 | `pom.xml:6-27`；`build.ps1:13-29`；`package-windows.ps1:23-55`；`ci.yml:9-22` |
| 许可 | `pom.xml:6`；`README.md:96`；`docs/HMCL_PORT.md:17-52`；`LICENSES/HMCL-ADDITIONAL-TERMS.md` |
| 测试规模与开关 | 24 类 138 个 @Test（本次实测）；`docs/VALIDATION.md:14,22,55` |
| Web 访问 token 仅驻内存 | `ProcessService.java:19-20,40,100-108`；`docs/runtime-compatibility.md:35` |

## 附录 D 调研方法、边界与未验证事项

- **方法**：只读遍历源码/文档/测试/构建脚本；对关键结论做交叉核对（文档陈述 vs 代码实现）；统计行数与 @Test 数量；抽查构建脚本与 CI 配置；未运行 HDSL、未执行 mvn/测试、未联网下载、未修改参考项目任何文件。
- **未验证（明确标注）**：Linux 运行行为（DSH 安装/启动/就绪/反代）、现有测试在 Linux 的实际通过率、Linux 上 pnpm/符号链接/权限位行为、DSH 工作台 iframe/反代兼容性、上游是否提供 Linux 桌面端、GPL 衍生边界（法务）、Vue 前端实际工作量。
- **假设**：目标为单机/单容器 Linux 环境、Java 21 可部署、浏览器为现代 Chromium/Firefox、外网可访问 npm registry 与 GitHub（可配代理）。
- **行号说明**：所有行号基于本次读取的 HDSL 0.2.0 快照；参考项目未改动，后续如需引用请以该快照为准。

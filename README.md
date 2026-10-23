# PCL-DSH-Linux

> 在 Linux 服务器上以 WebUI 方式管理 DeepSeek Harness（DSH）：安装运行时、创建与启停实例、管理账户/插件/整合包，并通过浏览器使用 DSH 工作台。

PCL-DSH-Linux 是 [HDSL](https://github.com/jiefing/HDSL) 桌面版的 Linux WebUI 移植版。它**不是** DSH 本身，也不修改 DSH 的上游代码；它复用 HDSL 的业务逻辑，把桌面启动器常驻到 Linux 服务器上，用 Nginx + Spring Boot + Vue 3 提供单用户 Web 管理界面。

- 当前版本：**0.3.2**（最新发行版 <https://github.com/LeuJasYoh/pcl-dsh-linux/releases/tag/v0.3.2>）
- 代码仓库：<https://github.com/LeuJasYoh/pcl-dsh-linux>
- 目标平台：Ubuntu 24.04 x86_64（headless 服务器，systemd + Nginx）
- 许可：GPL-3.0-or-later，含 HMCL 第 7 条附加条款；详见 LICENSE、LICENSES/、THIRD_PARTY_NOTICES.md

---

## 目录

- [1. 项目定位与功能](#1-项目定位与功能)
- [2. 快速开始（服务器部署）](#2-快速开始服务器部署)
- [3. 使用指南](#3-使用指南)
- [4. 架构设计](#4-架构设计)
- [5. 配置参考](#5-配置参考)
- [6. 运维与故障排查](#6-运维与故障排查)
- [7. 已知限制与设计取舍](#7-已知限制与设计取舍)
- [8. 项目结构（开发者参考）](#8-项目结构开发者参考)
- [9. 文档、许可与致谢](#9-文档许可与致谢)

---

## 1. 项目定位与功能

### 1.1 它解决什么问题

HDSL 桌面版把 DSH 的安装、实例、账户、插件和整合包管理做成了一个本地图形程序。PCL-DSH-Linux 把这些能力搬到 Linux 服务器：

- 服务器 7×24 常驻，任意桌面浏览器都能访问；
- DSH 实例仍只监听服务器回环地址，由 Spring Boot 反向代理工作台，不把 DSH 端口暴露到公网；
- 账户 API Key 用服务器本地主密钥加密保存，不写入日志；
- 运行时、Node、pnpm 可随发布包自带，服务器不需要单独安装 Node 工具链。

**设计边界**：单用户、单服务器、最多同时运行 1 个 DSH 实例；只保证桌面浏览器（最小约 960px）；当前通过 HTTP 访问，由来源 IP 白名单/云安全组提供第一层防护。

### 1.2 功能一览

| 模块 | 能力 |
| --- | --- |
| 登录/会话 | 单管理员密码、首次登录强制改密、BCrypt、会话 12 小时、CSRF、登录限流、审计日志 |
| 首页/启动 | 当前实例状态、一键启动、任务进度（SSE）、工作台入口 |
| 实例 | 创建/编辑/删除、复制、改版本/改 profile（以复制方式）、启停、端口自动分配、单实例运行限制、直连登录入口 |
| 下载（运行时） | 查询 npm 上可用 DSH 版本、按精确版本安装、能力探测、删除（引用保护） |
| 账户 | 预设供应商 + 自定义 OpenAI/Anthropic 兼容端点、API Key 加密存储、绑定实例并在启动时注入 |
| 插件 | 查看已装插件、安装/更新/移除；社区插件目录（GitHub）检索与安装 |
| 整合包 | dspack/Overture 格式预览、导入（含运行时与依赖恢复、失败回滚）、导出（分享包不含凭据） |
| 任务与日志 | 单任务队列、SSE 实时阶段/日志、取消、日志尾部查看；审计日志落盘 |
| 设置 | npm registry、下载代理、内置/自定义背景 |
| 工作台 | /workbench/{id}/ 反向代理 DSH 页面，支持 HTTP、SSE、WebSocket |
| 直连登录 | 远程访问下 DeepSeek 账号 OAuth 的 SSH 隧道命令 + 回环直连地址（含一次性 token） |

### 1.3 它是怎么组成的

- **pcl-dsh-core**：从 HDSL 0.2.0 迁移的业务层（实例存储、进程、运行时、账户/启动注入、整合包、插件、目录），去掉 JavaFX/桌面耦合，保持 com.hdsl.* 包名。
- **pcl-dsh-server**：新增 Spring Boot 3.5 服务（Java 21），提供 REST API、会话安全、Job/SSE、文件上传下载、工作台反代和静态资源入口（生产环境静态文件由 Nginx 直出）。
- **pcl-dsh-web**：Vue 3 + TypeScript + Vite + Pinia 前端，复刻 HDSL 桌面版的 8 个页面 + 登录页。
- **packaging / scripts**：发布包、systemd、Nginx、安装/卸载/重置密码脚本，以及 Windows 上的构建/打包脚本。

---

## 2. 快速开始（服务器部署）

### 2.1 环境要求

| 项目 | 要求 |
| --- | --- |
| 操作系统 | Ubuntu 24.04 x86_64（其他现代 Linux 可尝试，未验收） |
| CPU/内存 | 最低 2 vCPU / 2 GiB + swap；推荐 4 GiB。Java 服务常驻约 350 MB，DSH 实例的 Node 进程可能占用 1–2 GB |
| 磁盘 | 建议 ≥ 20 GB；每个 DSH 运行时约 300–700 MB，pnpm store 会增长 |
| Java | JDK/JRE 21 或更高（apt install openjdk-21-jre-headless） |
| Nginx | 由宿主机安装（apt install nginx），作为唯一公网入口 |
| 网络 | 服务器需能访问 npm registry（安装运行时）、GitHub（插件目录）；可配置镜像/代理 |
| 权限 | root/sudo（安装 systemd 服务、Nginx 站点、创建 pcl-dsh 系统用户） |
| Node/pnpm | **不需要单独安装**：发布包自带 Node 24.22.1 + pnpm 10.34.0（0.3.2 发行包） |

端口规划：

| 端口 | 监听 | 用途 | 是否公网 |
| --- | --- | --- | --- |
| 80 | 0.0.0.0（Nginx） | Web 入口 | 是（建议安全组限制来源 IP） |
| 8080 | 127.0.0.1（Java） | Spring Boot API/反代 | 否 |
| 3080–65535 | 127.0.0.1（Node） | DSH 实例，自动分配 | 否（经 Spring Boot 反代） |
| 22 | 0.0.0.0（SSH） | 运维/直连登录隧道 | 建议限制来源 IP |

### 2.2 安装

以 root（或 sudo）在服务器上执行：

```bash
# 1. 安装前置依赖（如果尚未安装）
apt update && apt install -y nginx openjdk-21-jre-headless

# 2. 下载并校验发行包
cd /root
curl -fsSLO https://github.com/LeuJasYoh/pcl-dsh-linux/releases/download/v0.3.2/SHA256SUMS
curl -fsSLO https://github.com/LeuJasYoh/pcl-dsh-linux/releases/download/v0.3.2/pcl-dsh-linux-0.3.2-linux-x64.tar.gz
sha256sum -c SHA256SUMS --ignore-missing

# 3. 解压并安装
tar -xzf pcl-dsh-linux-0.3.2-linux-x64.tar.gz
cd pcl-dsh-linux-0.3.2-linux-x64
sudo bash install.sh
```

安装脚本会：

- 创建 pcl-dsh 系统用户和 /opt/pcl-dsh-linux/{releases,current,data}；
- 安装 systemd 服务（开机自启、异常重启）并启动；
- 安装 Nginx 站点配置（可选交互式输入来源 IP 白名单）；
- 等待 /api/health 就绪，输出初始密码文件位置。

安装完成后：

```bash
systemctl status pcl-dsh-linux
curl -s http://127.0.0.1/api/health
cat /opt/pcl-dsh-linux/data/initial-password
```

浏览器访问 http://<服务器IP>/，用初始密码登录；首次登录会强制修改密码。

> 如果通过云安全组/防火墙访问，确保 80 端口放行；不要对公网开放 8080 或 3080。

### 2.3 首次使用流程

1. **登录并改密**：登录后设置新密码（至少 8 位，不能与初始密码相同）。
2. **安装运行时**：进入「下载」页，选择 Harness 版本（当前验收基线为 0.2.1-rc.2 及 npm 最新版），点击安装，等待任务完成。
3. **创建实例**：进入「实例」页，填写名称、选择运行时版本和 profile（默认 web），端口留空自动分配；创建后点击「启动实例」。
4. **打开工作台**：实例变为「运行中」后，点击「打开工作台」，浏览器进入 /workbench/{id}/。
5. **配置账户（推荐 API Key）**：在「账户」页添加供应商和 API Key，然后在实例上绑定；下次启动自动注入。
6. **如需 DeepSeek 账号网页登录**：见 [3.8 直连登录](#38-直连登录远程账号-oauth)。

首次安装运行时依赖网络，npm 下载可能较慢；可在「设置」页配置 registry/代理，或提前预热服务器上的 pnpm store。

### 2.4 升级、回滚、卸载

**升级**：下载新版本发行包，解压后同样执行 sudo bash install.sh。脚本会安装到 releases/<版本> 并切换 current 软链，data/ 数据不受影响；服务重启后实例需要手动重新启动。

**回滚**：下载旧版本发行包重新执行安装脚本（或手工把 /opt/pcl-dsh-linux/current 指回旧版本目录后重启服务）。回滚前建议备份 data/，并确认旧版本兼容当前数据文件。

**卸载**：

```bash
cd /root/pcl-dsh-linux-0.3.2-linux-x64
sudo bash uninstall.sh            # 保留 /opt/pcl-dsh-linux/data
sudo bash uninstall.sh --purge    # 连数据目录和 pcl-dsh 用户一起删除
```

**重置管理员密码**（忘记密码）：

```bash
cd /root/pcl-dsh-linux-0.3.2-linux-x64   # 或当前发行版目录
sudo bash reset-password.sh
```

该脚本会停止服务、移走 data/config/server.json（重新生成初始密码）并重启服务；其他数据保留。

### 2.5 备份与恢复

备份对象是 **data 目录 + 版本号 + Nginx/systemd 配置**。最简单的停机备份：

```bash
systemctl stop pcl-dsh-linux
tar -czf /root/pcl-dsh-data-$(date +%F).tar.gz -C /opt/pcl-dsh-linux data
cp /etc/nginx/conf.d/pcl-dsh-linux.conf /root/pcl-dsh-nginx.conf
systemctl start pcl-dsh-linux
```

恢复：安装相同版本发行包 → 停止服务 → 把备份的 data/ 还原到 /opt/pcl-dsh-linux/data（保持 pcl-dsh 所有者与 0700 权限）→ 启动服务 → 登录验证。

> ⚠️ data/config/secret.key 是账户 API Key 的主密钥。丢失后已保存的 Key 无法解密，只能重新输入；备份文件等同于敏感凭据，请妥善保管。

### 2.6 安全建议（部署时至少做这些）

- 云安全组只放行你的来源 IP 访问 80/22；关闭未使用端口。
- 安装时启用 Nginx 来源 IP 白名单，或手工编辑 /etc/nginx/conf.d/pcl-dsh-linux.conf。
- 首次登录立即改密；不要把密码写入文档/仓库。
- 如需公网长期使用，建议后续在 Nginx 增加 HTTPS（当前版本未内置证书管理）。
- 定期备份 data/（含 secret.key）；备份文件不要公开。
- 不要把 8080/3080 暴露到公网，也不要在 Nginx 中新增指向实例端口的 location。

### 2.7 可以在 Windows 上直接运行吗？

**简短结论：官方发行包不行；Java 服务本体可以，但属于“技术可行、未官方验收”的手工运行方式。**

- 发行包是 **Linux x64 专用**：里面是 Linux 版 Node 二进制、bash 安装/卸载脚本、systemd unit、Nginx 配置和 Linux 路径；不能拿到 Windows 上直接解压运行，也没有 Windows 安装包/服务封装。
- **Java 服务本体是跨平台的**。本机实测：JDK 21 + 系统 Node（v24.22.1）可以启动 ~~release/pcl-dsh-linux.jar~~，~~/api/health~~ 返回 200，启动自检能识别系统 Node/pnpm。核心代码本身保留了 Windows 分支（~~node.exe~~、~~pnpm.cmd~~、~~.cmd~~ shim 等）。
- 但服务端默认 **不提供前端静态资源**（~~spring.web.resources.add-mappings=false~~），所以还需要：
  1. JDK 21（运行 jar）；
  2. Node 24 + **pnpm 10.34.x**（建议精确匹配；其他 pnpm 版本未验证运行时安装）；
  3. 前端：在 ~~pcl-dsh-web~~ 下 ~~npm ci && npm run dev~~（Vite 已配置把 ~~/api~~、~~/workbench~~ 代理到 ~~127.0.0.1:8080~~），或自己用 Windows Nginx 提供 ~~dist/~~ 并反向代理；
  4. 手工启动：~~java -jar pcl-dsh-linux.jar --pcl.data-root=D:\pcl-data --server.port=8080~~（不要复用 Linux 的 ~~data/runtimes~~，DSH 原生模块必须为 Windows 重新安装）。
- 不建议把 Windows 作为生产形态：没有 Windows 服务化/自动启动、没有 install.sh、账户密钥的 0600 权限在 Windows 上只能“尽力而为”、DSH 运行时/插件生态未验收。生产环境建议 Linux 服务器或 WSL2。

---

## 3. 使用指南

### 3.1 首页 / 启动

首页展示当前选中的实例、运行状态和后端连接状态。没有实例时主按钮为「创建实例」；有实例但未运行时为「启动实例」，运行中显示「打开工作台」和「停止」。

启动是**长任务**：后端会检查运行时（未安装则先下载安装）、探测 CLI 能力、准备账户注入、启动 Node 子进程，并等待网页服务就绪（最长 90 秒）。任务进度通过 SSE 实时显示在「任务与日志」页；任务运行期间其他写操作会收到 409，需要等待或取消。

### 3.2 实例

- **创建**：名称必填，版本可手输（必须存在于服务器已安装运行时列表，或由安装流程自动下载），profile 默认 web，端口留空自动从 3080 起分配。
- **编辑**：可改名称、端口、账户绑定；版本/profile 变更（以及「复制」）以**复制新实例**的方式实现，原实例保留，避免破坏已有环境。
- **启动/停止**：同一时间只允许 1 个实例运行；实例运行中仍可随时停止。停止会终止该实例的 Node 进程树并释放端口。
- **删除**：数据移动到 data/trash/，不会立即物理删除（便于误删恢复/排查）。
- **直连登录**：见 [3.8](#38-直连登录远程账号-oauth)。

实例状态包括「未启动」「启动中」「运行中」「进程运行中，服务未就绪」「已退出」等；「运行中」表示 HTTP 网页服务已确认可访问。

### 3.3 运行时（下载页）

- 从 npm 查询 Harness 可用版本（支持精确版本输入）。
- 安装运行时：创建独立目录、生成 pnpm workspace、pnpm install（含原生模块 postinstall），并探测版本能力（--patch/--profile/--port 等）。
- 删除运行时：被实例引用时拒绝删除；删除出错的文件移动到 trash/。
- 运行时安装是 Job，进度/日志通过 SSE 展示；网络慢时可能耗时数分钟，建议配置 registry/代理。

### 3.4 账户与凭据

- 支持预设：DeepSeek、OpenAI、Anthropic、Google Gemini、OpenRouter、Groq、Mistral、Moonshot（国际/国内）、MiniMax（国际/国内）、Z.ai/智谱 Coding、xAI、Cerebras、Together、Hugging Face，以及自定义 OpenAI Chat Completions / OpenAI Responses / Anthropic Messages 端点。
- 保存时只写入加密后的密文；UI 不回显 API Key。账户列表状态「已保存 · API 连接未验证」表示**只校验了格式与加密保存，没有实际调用供应商 API**。
- 在实例上绑定账户后，启动时会生成一次性的注入补丁与校验脚本，API Key 只在该 Node 进程内存中使用，不写盘、不写日志；启动完成后临时文件清理。
- 供应商/模型/DSH 版本不兼容时，启动任务会失败并给出明确错误，可在任务日志查看原因。

### 3.5 插件

- 「已装插件」查看当前实例 profile 下的依赖；安装/更新/移除通过 DSH CLI 执行，要求实例**已停止**。
- 社区插件目录通过 GitHub 检索仓库，校验目标包名后再安装；需要服务器能访问 GitHub API。
- 插件操作是 Job；原生模块/特定插件是否可用取决于 DSH 与运行环境，属于上游生态范围。

### 3.6 整合包

- 支持 .dspack / Overture 格式的预览与导入；导入时会校验包结构、路径安全、SHA-256，并在需要时安装/恢复运行时和依赖；失败会回滚到 trash/。
- 导出：
  - **profile 模式**：导出当前实例当前 profile（默认）；
  - **home 模式**：仅适用于非 web 的已初始化 profile；
  - 分享包不会包含账户凭据、会话、日志等敏感数据。
- 导入是长任务，依赖网络安装运行时/依赖时可能耗时较长；预览登记 30 分钟过期，导出下载产物 24 小时过期。

### 3.7 任务与日志

- **任务**：所有长操作（安装运行时、启动实例、导入/导出、插件变更、复制实例）都是 Job。同一时间只允许一个变更任务，忙时返回 409 并提示等待。
- **SSE**：任务详情通过 /api/jobs/{id}/events 推送状态、阶段、日志、结果、错误；支持断线重连（保留最近事件），可取消。
- **日志**：页面读取 data/logs/launcher-YYYY-MM-DD.log 的尾部（脱敏后）；审计日志单独写入 data/logs/audit-YYYY-MM-DD.log，目前没有 Web 审计查看器，需要 SSH 查看。
- 任务历史保存在内存中，服务重启后历史任务/事件不再保留；持久化日志仍可查看。

### 3.8 直连登录（远程账号 OAuth）

**现象**：在远程浏览器里通过 /workbench/{id}/ 打开 DSH 工作台一切正常，但点击「登录 DeepSeek 账号」会失败。

**根因**：这是上游 DSH 的设计限制，不是本项目反代 bug。DSH 的账号 OAuth 回调地址被严格限制为回环地址（http://127.0.0.1:<端口> / localhost / [::1]）；远程页面的 origin 是服务器公网地址，既过不了校验，OAuth 回调也到不了服务器。

**解决方案**（一次性登录，登录后凭据保存在服务器实例目录，之后可继续用正常地址）：

1. 在实例卡片点击「直连登录」，复制弹窗中的 **SSH 隧道命令**：
   ```bash
   ssh -L 3080:127.0.0.1:3080 root@<服务器地址>
   ```
2. 在本机终端执行该命令（保持窗口打开）。
3. 在浏览器打开弹窗中的 **回环直连地址（含一次性 token）**：http://127.0.0.1:3080/?token=...
4. 在这个页面完成 DeepSeek 账号登录；回调会经隧道回到服务器实例。
5. 关闭隧道，回到 http://<服务器IP>/workbench/{id}/ 继续使用。

注意：

- token 由实例每次运行时生成，**实例重启后直连地址会变化**，需要重新打开对话框获取；
- 直连地址含访问 token，请勿外传；
- 如果只是想调用模型，推荐使用 **API Key 模式**，不涉及网页 OAuth，远程访问完全可用；
- 详细排障见 [docs/REMOTE_ACCOUNT_LOGIN.md](docs/REMOTE_ACCOUNT_LOGIN.md)。

### 3.9 设置

- **npm registry**：运行时/插件安装使用的 npm 源，可配置镜像或代理。
- **下载代理**：访问 npm/GitHub 等外网时使用的代理。
- **背景**：选择内置背景或上传自定义图片（WebP），只影响 PCL 界面。
- 设置保存在数据目录的 config/launcher-v2.json 中，与其他实例清单一起管理。

---

## 4. 架构设计

### 4.1 总体架构

```text
                         桌面浏览器（现代 Chromium / Firefox / Edge）
                                      │  HTTP :80（可选来源 IP 白名单）
                                      ▼
                    ┌─────────────────────────────────────┐
                    │            Nginx（宿主机）           │
                    │  /            静态 Vue SPA           │
                    │  /api/        → 127.0.0.1:8080      │
                    │  /workbench/  → 127.0.0.1:8080      │
                    │    （SSE / WebSocket，禁用缓冲）      │
                    └──────────────────┬──────────────────┘
                                       │ 127.0.0.1:8080
                                       ▼
                    ┌─────────────────────────────────────┐
                    │  Spring Boot：pcl-dsh-server         │
                    │  ├─ 登录/会话/CSRF/限流/审计          │
                    │  ├─ REST API + JobManager + SSE      │
                    │  ├─ /workbench 反向代理（HTTP/SSE/WS）│
                    │  ├─ 文件上传/下载、预览、artifact     │
                    │  └─ 调用 pcl-dsh-core 业务层          │
                    └──────────────────┬──────────────────┘
                                       │
              ┌────────────────────────┴────────────────────────┐
              ▼                                                 ▼
   ┌──────────────────────────────┐                ┌──────────────────────────────┐
   │ pcl-dsh-core（复用 HDSL）      │                │ 数据目录                      │
   │ InstanceStore / ProcessService│                │ /opt/pcl-dsh-linux/data      │
   │ RuntimeService / AccountService│               │ config / instances / runtimes│
   │ PackService / PluginService   │                │ cache / logs / trash / assets│
   │ PluginCatalogService          │                └──────────────────────────────┘
   └──────────────┬───────────────┘
                  │ spawn（最多 1 个运行实例）
                  ▼
   ┌──────────────────────────────────────────────┐
   │ Node DSH 实例：127.0.0.1:3080+                │
   │ node <runtime>/node_modules/@deepseek-ai/dsh  │
   │   --profile web --port <port> --host 127.0.0.1│
   └──────────────────────────────────────────────┘
```

### 4.2 组件职责

| 组件 | 职责 |
| --- | --- |
| Nginx | 公网唯一入口；静态文件直出；/api/、/workbench/ 反代；SSE/WS 支持；上传大小限制；登录接口限流；可选 IP 白名单；/workbench/ 关闭访问日志避免 token 泄漏 |
| Spring Boot | 会话安全、REST API、Job/SSE、反代、上传下载、健康检查、静态资源兜底 |
| pcl-dsh-core | 实例清单与文件锁、Node 进程管理、运行时安装/探测、账户加密与启动注入、整合包安全读写、插件、GitHub 目录 |
| Node DSH 实例 | 每个实例一个独立 Node 进程，监听回环端口；由 Java 启动/停止，最多同时运行 1 个 |
| systemd | 常驻 Java 服务、开机自启、异常重启、资源/权限约束 |
| 数据目录 | 唯一持久化状态；升级/回滚不影响 |

### 4.3 请求路径与端口

| 路径 | 处理者 | 鉴权 |
| --- | --- | --- |
| /、/login、/instances 等 | Nginx 静态 SPA（try_files 回退 index.html） | 页面本身公开，数据接口需会话 |
| /api/auth/login、/api/health | Spring Boot | 匿名 |
| /api/** | Spring Boot | 会话 +（写操作）CSRF |
| /api/jobs/{id}/events | Spring Boot SSE | 会话 |
| /workbench/{instanceId}/** | Spring Boot 反代 → 实例回环端口 | 会话；代理目标白名单 |
| /assets/**、*.js/css/svg/... | Nginx 静态（长缓存） | 公开 |

Java 只监听 127.0.0.1:8080；DSH 实例只监听 127.0.0.1:3080+。公网只能看到 Nginx 80。

### 4.4 进程与生命周期

- systemd 启动 java -jar pcl-dsh-linux.jar（-Xms128m -Xmx384m -XX:MaxMetaspaceSize=160m）。
- 用户点击启动后：Job 线程检查运行时 → 安装（如缺失）→ 探测 CLI 能力 → 构造启动命令 → 如有账户绑定生成补丁/校验脚本 → 启动 Node 子进程 → 最长 90 秒轮询 HTTP 就绪 → 记录 Web 地址。
- Java 进程退出时由 systemd 控制组结束整个进程树；ProcessService 只管理自己启动的进程，不会误杀其他 Node。
- 服务/服务器重启后**不会自动启动 DSH 实例**，需要手动点启动；实例进程异常退出也不会自动拉起（Java 服务本身由 systemd 拉起）。
- 端口从 3080 起自动分配，启动前检查端口是否空闲；端口冲突会返回明确错误。

### 4.5 数据与持久化

```text
/opt/pcl-dsh-linux/
├── current -> releases/0.3.2
├── releases/0.3.2/
│   ├── pcl-dsh-linux.jar
│   ├── static/                    # Vue 构建产物
│   └── tools/
│       ├── node/                  # 内置 Node 24.22.1
│       └── pnpm/                  # 内置 pnpm 10.34.0
└── data/                          # 0700，pcl-dsh 所有；升级不动
    ├── config/
    │   ├── launcher-v2.json       # 实例清单 + 设置（schemaVersion=1）
    │   ├── accounts.json          # 账户元数据 + AES-GCM 密文
    │   ├── server.json            # BCrypt 密码、会话等
    │   ├── secret.key             # 主密钥（0600，首次启动生成）
    │   └── compatibility.json     # 可选：CLI 能力人工声明
    ├── initial-password           # 首次启动生成；改密后删除
    ├── instances/<id>/
    │   ├── dsh-home/              # DSH_HOME / profiles
    │   ├── workspace/             # 实例工作目录
    │   └── .hdsl-account/         # 启动时账户临时补丁（运行期）
    ├── runtimes/<version>/        # 已安装 DSH 运行时
    ├── tools/shims/               # 运行期生成的 pnpm/插件桥
    ├── assets/backgrounds/        # 自定义背景
    ├── cache/
    │   ├── pnpm-store/            # pnpm 缓存（可复用/可重建）
    │   ├── registry-metadata/     # npm 版本元数据
    │   ├── probes/                # 能力探测产物
    │   ├── pack-staging/          # 导入暂存
    │   ├── uploads/               # 整合包预览（30 分钟）
    │   └── exports/               # 导出 artifact（24 小时）
    ├── logs/
    │   ├── launcher-YYYY-MM-DD.log
    │   └── audit-YYYY-MM-DD.log
    ├── trash/                     # 删除实例/运行时/失败导入的回滚点
    └── backups/                   # 预留（当前无自动备份脚本）
```

要点：

- data/config/launcher-v2.json 沿用 HDSL schema，升级时保持兼容；账户文件只存密文。
- secret.key 与 accounts.json 权限 0600；整个 data 目录 0700。
- 导出 artifact 元数据持久化（重启后链接仍可用，TTL 24h）；预览登记在内存 + 上传文件（TTL 30min，重启清理）。
- Job 历史在内存中，不持久化；launcher 日志按日文件持久化。
- trash/ 需要人工清理；cache/pnpm-store 可安全重建。

### 4.6 安全设计

| 层 | 措施 |
| --- | --- |
| 网络 | Nginx 唯一公网入口；Spring Boot/DSH 只监听回环；云安全组 + 可选 Nginx allow/deny 白名单 |
| 登录 | 24 位随机初始密码，首次登录强制修改；BCrypt；连续 5 次失败/60 秒锁定；会话 12h；HttpOnly + SameSite=Lax；CSRF token |
| 审计 | 登录成功/失败/锁定、改密、实例启停、账户/设置/删除等写操作记录到 audit-*.log，日志脱敏 |
| 账户密钥 | AES-256-GCM；主密钥来自 PCL_DSH_SECRET_KEY 或 config/secret.key（0600）；API Key 不出现在 API/日志/前端状态 |
| 反代 | /workbench 目标固定 127.0.0.1 + 实例登记端口，拒绝外部 Host/URL；转发时过滤 JSESSIONID/XSRF-TOKEN；Set-Cookie 改写 Path；token 只在内存 |
| 文件 | 上传大小 2 GB；路径白名单/防穿越；整合包安全归档读取；下载 no-store；临时目录限制在 data 下 |
| 日志 | 统一脱敏（API Key/token/password/Authorization）；/workbench 关闭 Nginx 访问日志避免 token 进日志 |
| 进程 | systemd 限制 ProtectSystem=strict / NoNewPrivileges / PrivateTmp，只写 data；只结束自己启动的进程 |

> 当前为 HTTP 明文：密码、会话 Cookie、工作台 token 在公网传输时可能被窃听；来源 IP 白名单和云安全组是主要补偿措施。后续如需公网使用，建议在 Nginx 增加 HTTPS。

### 4.7 任务系统（JobManager）

- 单例全局任务：同一时间只允许一个变更任务；忙时同步写操作和提交任务都返回 409 CONFLICT_BUSY，不排队（与桌面版行为一致）。
- 状态机：RUNNING → SUCCEEDED / FAILED / CANCELLED；每个任务有类型、阶段、消息、日志、结果/错误。
- 任务类型：INSTALL_RUNTIME、PROBE_RUNTIME、LAUNCH_INSTANCE、COPY_INSTANCE、PLUGIN_MUTATE、IMPORT_PACK、EXPORT_PACK。
- SSE：/api/jobs/{id}/events，事件 state/log/result/error，15 秒心跳，支持 Last-Event-ID 断线续传；页面在「任务与日志」展示。
- 取消：协作式检查 + 中断工作线程；启动任务取消时会停止本次创建的进程并清理账户临时文件。
- 超时：启动 90 秒就绪、运行时安装 20 分钟、其他任务有各自上限；超时标记失败并尽量清理。

### 4.8 DSH 工作台反向代理

工作台不直接暴露实例端口，所有访问都经过 Spring Boot：

1. 浏览器打开 /workbench/{id}/；如果没有会话，先登录 PCL。
2. 后端校验实例存在、正在运行且已就绪，目标只能是 127.0.0.1:<实例端口>。
3. 如果请求没有 DSH 的 dsh-auth-* Cookie 且没有 token，后端 302 到带一次性 token 的路径（token 来自实例启动时打印的 dsh web: 地址，只保存在 Java 内存）。
4. DSH 校验 token 后 303 到 ./ 并下发 Cookie；后端转发时重写 Set-Cookie 的 Path/Domain，让浏览器只在 /workbench/{id} 下携带。
5. 后续 HTTP、SSE（EventSource）和 WebSocket（如 /api/remote.mux）都由 Spring Boot 流式转发；/workbench/ 在 Nginx 侧禁用缓冲、关闭访问日志、放宽超时。
6. 实例未运行/未就绪时返回 409 并给出中文原因；上游连接失败返回 502。

「直连登录」是另一条路径：后端从实例运行时的 bootstrap 地址中取出 token，生成 direct-access 响应（隧道命令 + 回环 URL），供用户在本机通过 SSH 隧道完成 DSH 账号 OAuth；不改变 DSH 只监听回环的事实。

### 4.9 账户注入（AccountOverlay / LaunchBinding）

1. 账户保存：AccountService 用 LinuxSecretProtector 加密 API Key，写入 config/accounts.json；返回给前端的只有元数据与 hasKey。
2. 绑定实例：实例记录 accountId；要求实例已停止。
3. 启动时：LaunchBinding 在实例目录生成一次性目录（account.patch.json、校验脚本），把 provider/baseUrl/model/apiKeyEnv 注入到 DSH profile，并通过 CLI 参数 --patch 传入；API Key 通过环境变量传入，只存在于进程内存。
4. 校验：等待 DSH 写出 account.validation.txt；成功则保留 overlay 供进程运行期使用，失败则停止进程并清理。
5. 兼容性：overlay 依赖 DSH 包的内部结构，**以发行时最新 DSH 为基线**；上游结构变化时启动会失败并提示，需要使用匹配的运行时版本。

### 4.10 整合包（PackService）

- 支持 .dspack / Overture 格式；解析 manifest、profile/plugins、运行时版本。
- 预览：安全归档读取（拒绝路径穿越/绝对路径/链接），计算 SHA-256，登记 30 分钟。
- 导入：按 manifest 恢复运行时（缺失时安装）、恢复 profile 文件/插件依赖、校验，创建新实例；失败时移动到 trash/，不污染现有实例。
- 导出：profile 或 home 模式；生成安全 tar/zip 包，排除账户、会话、日志等敏感数据；输出为 24 小时有效的下载 artifact。
- 限制：home 模式仅适用于非 web 的已初始化 profile；导入可能触发网络安装，耗时较长。

### 4.11 前端设计

- Vue 3.5 + TypeScript + Vite 7 + Pinia + Vue Router；路由懒加载，构建产物由 Nginx 直出。
- 页面：登录、首页/启动、实例、下载、账户、插件、整合包、任务与日志、设置、关于（弹窗）。
- 布局复刻桌面版：40px 标题栏、200px 侧栏、200×55 启动按钮；最小桌面宽度约 960px，不提供移动端布局。
- 浏览器限制的等价实现：窗口按钮禁用但保留视觉；文件选择后立即上传；剪贴板 API 在 HTTP 非安全上下文失败时回退为手动复制文本框；实例「打开文件夹」改为显示路径；customCommand 不提供入口且后端拒绝非空值。
- 状态管理：登录态、实例/任务/设置等集中在 Pinia；未登录时不轮询受保护接口；任务期间轮询/SSE 降级。

### 4.12 工具链与发布包

发布包（pcl-dsh-linux-0.3.2-linux-x64.tar.gz）包含：

```text
pcl-dsh-linux-0.3.2-linux-x64/
├── install.sh / uninstall.sh / reset-password.sh
├── pcl-dsh-linux.service
├── nginx-pcl-dsh-linux.conf
├── VERSION
├── LICENSE / LICENSES/ / THIRD_PARTY_NOTICES.md
├── docs/...
└── release/
    ├── pcl-dsh-linux.jar
    ├── static/
    └── tools/
        ├── node/        # Node 24.22.1 (linux-x64)
        └── pnpm/        # pnpm 10.34.0（node pnpm.cjs 调用）
```

运行时查找顺序：**发布包 current/tools → data/tools → 系统 PATH**。因此服务器不需要安装 Node/pnpm；如果替换/删除发布包 tools，会回退到系统工具（需要自行保证版本兼容）。

---

## 5. 配置参考

### 5.1 应用配置（application.yml / 启动参数）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| server.address | 127.0.0.1 | 只监听回环；不要改成 0.0.0.0 |
| server.port | 8080 | Spring Boot 端口；与 Nginx 配置一致 |
| pcl.data-root | PCL_DSH_DATA 环境变量或 ./data | 数据目录；生产为 /opt/pcl-dsh-linux/data |
| pcl.tool-root | 空（自动推断） | 发布包只读 tools 目录；systemd 显式传 current/tools |
| pcl.session-timeout | 12h | 会话有效期 |
| pcl.sse-heartbeat | 15s | SSE 心跳间隔 |
| pcl.sse-timeout | 30m | SSE 连接上限 |
| pcl.login-window | 60s | 登录失败统计窗口 |
| pcl.login-lock | 60s | 达到失败上限后的锁定时长 |
| pcl.login-max-failures | 5 | 窗口内允许失败次数 |
| pcl.job-timeout | 20m | 默认 Job 超时（部分任务有独立上限） |
| pcl.proxy-enabled | true | 启用 /workbench 反代；关闭时 web-url 返回回环绝对地址（仅本机开发用） |
| pcl.proxy-timeout | 60s | 反代普通请求超时 |
| pcl.proxy-connect-timeout | 5s | 反代连接超时 |

环境变量：

- PCL_DSH_DATA：覆盖数据目录（systemd 已设置）。
- PCL_DSH_SECRET_KEY：可选，32 字节 Base64/hex 主密钥；设置后覆盖 config/secret.key。一旦使用环境变量保存密钥，备份/迁移时必须同时保留该值，否则账户 Key 无法解密。

### 5.2 systemd（pcl-dsh-linux.service）

- 用户/组 pcl-dsh；ProtectSystem=strict、ProtectHome=true、PrivateTmp=true、NoNewPrivileges=true。
- ExecStart 中固定 --server.address=127.0.0.1 --server.port=8080 --pcl.data-root=... --pcl.tool-root=.../current/tools。
- 内存：-Xms128m -Xmx384m -XX:MaxMetaspaceSize=160m -XX:+ExitOnOutOfMemoryError；Java 常驻约 350 MB。
- 日志：journalctl -u pcl-dsh-linux；应用日志另写 data/logs/launcher-*.log。

### 5.3 Nginx（/etc/nginx/conf.d/pcl-dsh-linux.conf）

- listen 80 default_server；root /opt/pcl-dsh-linux/current/static。
- location ^~ /api/：反代 8080，关闭缓冲，延长超时以支持 SSE/长任务。
- location = /api/auth/login：额外 limit_req（10 次/分钟，burst 5）。
- location ^~ /workbench/：反代 8080，转发 Upgrade（WebSocket），关闭缓冲，access_log off。
- 静态 *.js/css/png/webp/svg/... 长缓存；SPA 路由回退 index.html。
- 可选 allow/deny 白名单；client_max_body_size 2048m；server_tokens off；X-Forwarded-For 使用覆盖式 $remote_addr（防止客户端伪造）。

### 5.4 数据文件

| 文件 | 说明 |
| --- | --- |
| config/launcher-v2.json | 实例清单、当前选中、设置（proxy/registry/background）；升级保持 schema 兼容 |
| config/accounts.json | 账户元数据 + AES-GCM 密文；不要手工编辑 |
| config/server.json | 管理员密码哈希、会话、初始改密标记；忘记密码用 reset-password.sh |
| config/secret.key | 主密钥；0600；备份必须包含，且不能公开 |
| initial-password | 首次启动生成的初始密码；改密后自动删除 |
| config/compatibility.json | 可选：人工声明 CLI 能力，覆盖探测结果 |

---

## 6. 运维与故障排查

### 6.1 常用命令

```bash
systemctl status pcl-dsh-linux
systemctl restart pcl-dsh-linux
journalctl -u pcl-dsh-linux -f
curl -s http://127.0.0.1/api/health
curl -s http://127.0.0.1/api/health | jq .version   # 确认当前版本
tail -f /opt/pcl-dsh-linux/data/logs/launcher-$(date +%F).log
tail -f /opt/pcl-dsh-linux/data/logs/audit-$(date +%F).log
du -sh /opt/pcl-dsh-linux/data/*
df -h /opt
```

### 6.2 常见问题

| 现象 | 排查/处理 |
| --- | --- |
| 浏览器 502 / 首页打不开 | 看 systemctl status pcl-dsh-linux、journalctl -u pcl-dsh-linux -n 100；确认 Java 监听 127.0.0.1:8080、Nginx nginx -t 与 80 端口 |
| 启动服务失败 | 检查 Java 版本 ≥21、数据目录权限（pcl-dsh:pcl-dsh 0700）、8080 是否被占用；journalctl 会有中文原因 |
| 任务返回 409 CONFLICT_BUSY | 已有变更任务在运行；等任务结束或取消后再操作 |
| 实例启动失败/未就绪 | 看任务日志；常见原因是运行时安装失败（网络）、端口占用、账户 overlay 与 DSH 版本不兼容、90 秒内未就绪 |
| 端口被占用 | 启动前会检查端口；实例启动失败会提示端口；停止实例后重试，必要时在实例设置中改端口 |
| 安装运行时很慢/超时 | 服务器到 registry.npmjs.org 慢；在设置页配置 registry/代理，或复用 cache/pnpm-store；可重试 |
| 插件目录打不开/限流 | GitHub API 无认证限流（约 60 次/小时）；稍后重试或给服务器配置网络代理 |
| 工作台 401/页面空白 | 确认已登录 PCL、实例「运行中」；清 Cookie 后重进；看 Nginx/Java 日志 |
| 远程登录 DeepSeek 账号失败 | 上游 OAuth 回环限制；按 [3.8](#38-直连登录远程账号-oauth) 用「直连登录」SSH 隧道；或改用 API Key |
| 忘记密码 | 运行发行包内 reset-password.sh（会重新生成初始密码） |
| 账户全部消失/提示无法读取 | 检查 accounts.json 是否损坏；系统会降级为空列表并记录审计，需要重新保存 API Key |
| 升级后实例状态异常 | 升级会重启 Java，实例进程被停止；手动启动实例；回滚则重新安装旧版本并恢复数据备份 |

### 6.3 升级/回滚注意

- 数据目录不随版本替换；升级前建议先停机备份 data/。
- 回滚需要重新执行旧版本发行包的 install.sh（或手工切换 current 链接）并重启服务；旧版本必须兼容当前数据 schema。
- 升级/回滚后正在运行的 DSH 实例不会自动恢复，需要手动启动。
- 不要直接删除 data/config/secret.key，否则账户 API Key 无法解密。

### 6.4 磁盘与日志维护

- data/trash/ 是删除实例/运行时/失败导入的回滚点，确认无用后手工清理。
- data/cache/pnpm-store 可重建；data/cache/exports 24 小时自动清理；data/cache/uploads 30 分钟自动清理。
- 应用/审计日志按日生成；当前安装脚本未配置 logrotate，长期运行请自行配置轮转（建议保留 30 天）。
- 运行时不用的版本可在「下载」页删除（被实例引用会拒绝）。

---

## 7. 已知限制与设计取舍

这些是当前版本明确的行为/限制，不是安装故障：

1. **单用户/单服务器**：不做多用户、多租户、RBAC；每台服务器一个管理员。
2. **最多同时运行 1 个 DSH 实例**：由后端硬限制；换更大内存/多实例需要改代码。
3. **远程 DeepSeek 账号网页登录需要 SSH 隧道**：上游 DSH OAuth 只接受回环回调；PCL 提供「直连登录」复制隧道命令和 token，但不自动建立隧道，token 会随实例重启变化。API Key 模式不受影响。
4. **HTTP 明文访问**：未内置 HTTPS/证书管理；密码、会话、token 在公网明文传输。建议来源 IP 白名单/云安全组，或自行在 Nginx 终止 TLS。
5. **无自动备份/自动升级**：备份、升级、回滚、清理需要按本文命令手工执行；没有 Web 备份/恢复 UI。
6. **Job 历史不持久化**：服务重启后任务列表/SSE 事件丢失；日志文件仍保留。artifact 元数据持久化 24h，预览 30min。
7. **DSH Node 内存未设上限**：只限制了 Java 堆；没有注入 NODE_OPTIONS=--max-old-space-size，也没有实例级内存监控/自动重启；2 GiB 服务器建议准备 swap 并只跑一个实例。
8. **账户状态不验证 API 连通性**：保存时只校验格式/加密，不调用供应商接口；Key/模型错误要到实际使用时才暴露。
9. **插件/整合包生态覆盖有限**：自动化测试以 mock/固定样例为主；真实第三方插件（原生模块）、真实来源的 .dspack、极端包/大包未在服务器上全面验收。
10. **插件目录依赖 GitHub API**：未配置 token，受匿名限流和网络影响。
11. **部分 DSH 根路径请求不经过反代**：DSH 页面内少数请求使用站点根绝对路径（如 /dsh-claude-style/*、/username、/usage），Nginx 会按静态/SPA 处理，可能 404 或返回 index.html。主工作台可正常使用，但个别插件/账号/用量面板可能异常；目前没有通用改写方案。
12. **DSH 版本兼容性**：以 npm 最新版为验收基线；旧版本未实测，上游包结构变化可能导致账户 overlay/插件/反代失效；能力探测只能覆盖已知部分。
13. **自定义启动命令/环境变量禁用**：customCommand 无前端入口，后端拒绝非空值；不能像桌面版那样自定义启动参数。
14. **重启后不自动恢复实例**：systemd 只保证 Java 服务运行，DSH 实例需要手动启动；实例崩溃不会自动拉起。
15. **仅 Linux x64、桌面浏览器、中文界面**：发布包内置 Node 为 linux-x64；没有 ARM64 包；不支持手机/窄屏；无 i18n/无障碍专项。
16. **发布/CI**：发布包由本机 PowerShell 手工构建（可指定 -NodeTarball），CI 只做后端测试 + 前端构建，不产出发布包/不跑 E2E；校验只有 SHA-256，没有签名。
17. **单任务队列**：忙时 409 不排队，长安装/导入期间不能执行其他写操作（与桌面版一致）。
18. **主密钥轮换不支持**：替换 secret.key 会导致已存 API Key 无法解密，需要重新输入。
19. **Nginx 白名单为安装时交互配置**：非交互安装会跳过，需要手工编辑配置；没有 Web 动态 IP 管理。
20. **审计日志无 UI**：只能 SSH 查看文件；没有检索/导出/告警。

---

## 8. 项目结构（开发者参考）

> 本章面向想从源码构建/二次开发的人；日常部署不需要。

```text
PCL-DSH-Linux/
├── pom.xml                     # Maven 父工程（Java 21，Spring Boot 3.5）
├── pcl-dsh-core/               # 从 HDSL 迁移的业务层（com.hdsl.*）
├── pcl-dsh-server/             # Spring Boot 服务（com.hdsl.server.*）
├── pcl-dsh-web/                # Vue 3 + TS 前端
├── packaging/                  # install/uninstall/reset 脚本、systemd、Nginx 模板
├── scripts/                    # build.ps1 / mvn.ps1 / package-linux.ps1
├── docs/                       # 方案、验收、部署、反代与账户文档
├── .github/workflows/ci.yml    # ubuntu/windows 后端测试 + 前端构建
└── target/dist/                # 打包产物（不提交）
```

### 8.1 从源码构建

前置：JDK 21、Maven 3.9+、Node 20.19+/24、npm。

```bash
# 后端（含测试）
./scripts/mvn.ps1 -B -ntp verify        # Windows PowerShell
# 或 mvn -B -ntp verify

# 前端
cd pcl-dsh-web
npm ci
npm run build

# 前端 E2E（需要 Playwright Chromium；会临时启动后端+Vite）
npm run e2e
```

### 8.2 生成发布包（Windows）

```powershell
.\scripts\package-linux.ps1 -NodeTarball 'D:\path\node-v24.22.1-linux-x64.tar.xz'
```

产物在 target/dist/：pcl-dsh-linux-<version>-linux-x64.tar.gz、.sha256、-source.zip、SHA256SUMS。不指定 -NodeTarball 时脚本会联网下载默认 Node 版本（当前脚本默认 24.21.0，发行版实测为 24.22.1；建议显式指定）。

### 8.3 测试现状

- 后端：core 105 + server 78 项 JUnit 测试。
- 前端：Playwright E2E 11 条（登录、布局、实例、运行时、设置、日志、许可、复制/改版本、409 回退等）。
- 真实 DSH 联调：pcl-dsh-web/scripts/live-workbench.cjs（需要真实后端 + 运行时，默认不跑）。
- 更多设计/验收细节见 docs/：

| 文档 | 内容 |
| --- | --- |
| [docs/PORTING_ANALYSIS.md](docs/PORTING_ANALYSIS.md) | 移植可行性分析 |
| [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) | 完整实施方案与决策 |
| [docs/DSH_WORKBENCH_PROXY.md](docs/DSH_WORKBENCH_PROXY.md) | 工作台反向代理设计 |
| [docs/M0_RESULTS.md](docs/M0_RESULTS.md) / [docs/DEPLOYMENT_VALIDATION.md](docs/DEPLOYMENT_VALIDATION.md) | Linux PoC 与部署验收记录 |
| [docs/REMOTE_ACCOUNT_LOGIN.md](docs/REMOTE_ACCOUNT_LOGIN.md) | 远程账号 OAuth 限制与直连登录 |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | 开发/测试说明 |
| [docs/RELEASE_NOTES_0.3.2.md](docs/RELEASE_NOTES_0.3.2.md) | 当前版本发布说明（历史 0.3.1、0.3.0） |

---

## 9. 文档、许可与致谢

- 上游项目：[HDSL](https://github.com/jiefing/HDSL)、DeepSeek Harness（DSH）。
- 本项目为独立移植实现，界面复刻 HDSL 桌面版；上游版权与许可声明保留在 LICENSE、LICENSES/、THIRD_PARTY_NOTICES.md 与界面「关于」中。
- 许可：**GPL-3.0-or-later**，另含 HMCL 第 7 条附加条款；分发二进制时请同时提供对应源码（Releases 中附源码包）。
- 问题/建议：欢迎在 [Issues](https://github.com/LeuJasYoh/pcl-dsh-linux/issues) 反馈；部署前请先阅读第 2、6、7 节。

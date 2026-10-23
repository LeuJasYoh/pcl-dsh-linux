# PCL-DSH-Linux 0.3.2

新增「实例直连登录」助手，并为远程访问下的 DeepSeek 账号网页登录限制补充文档与界面引导。

## 新功能

- **实例「直连登录」**：实例卡片的「直连登录」按钮打开对话框，提供两步操作与一键复制：
  1. 本机终端执行的 **SSH 隧道命令**（`ssh -L <端口>:127.0.0.1:<端口> root@<服务器地址>`；
     服务器地址按当前浏览器访问的地址自动生成）；
  2. **回环直连地址（含一次性 token）**（`http://127.0.0.1:<端口>/?token=...`）。
- 新增后端接口 `GET /api/instances/{id}/direct-access`，返回
  `{ready, port, directUrl, tunnelCommand, sshHost, sshUser, note}`；实例未运行/未就绪时仍给出
  隧道命令并说明原因，不返回不可用的直连地址。
- 剪贴板不可用（非安全上下文）时对话框提供手动复制文本框，不谎报复制成功。

## 文档

- 新增 `docs/REMOTE_ACCOUNT_LOGIN.md`：说明上游 DSH 的 DeepSeek 账号 OAuth **只接受回环回调地址**
  （`loginOrigin()` 严格校验），因此远程浏览器无法直接完成网页登录，需配合 SSH 隧道；包含根因代码、
  替代方案（API Key 模式）、排障清单与版本说明。
- README 增加「已知限制」条目与文档索引。

## 验证

- 后端：core 105 + **server 78** 项测试通过（新增 direct-access 集成测试：匿名 401、未知实例 404、
  未运行/未就绪分支）。
- 前端：`vue-tsc --noEmit` + `vite build` 通过。
- 真实服务器实测：接口返回就绪状态、带 token 的直连地址与基于访问域名的隧道命令；
  静态资源与页面正常加载。

## 自包含工具链（不变）

发布包 `release/tools/` 内置 Node.js **24.22.1**（linux-x64，SHA-256 校验）与 pnpm **10.34.0**
（`bin/pnpm.cjs` 纯 JS 入口）；服务按「发布包 tools → data/tools → 系统 PATH」查找工具，服务器无需安装 Node 工具链。

## 安装（Ubuntu 24.04 x86_64）

前置：JDK 21（或更高）、Nginx、root/sudo。

1. 下载并校验发布包：`sha256sum -c pcl-dsh-linux-0.3.2-linux-x64.tar.gz.sha256`
2. 解压并安装：

       tar -xzf pcl-dsh-linux-0.3.2-linux-x64.tar.gz
       cd pcl-dsh-linux-0.3.2-linux-x64
       sudo bash install.sh

3. 浏览器访问 http://<服务器IP>/ ，初始密码见 /opt/pcl-dsh-linux/data/initial-password（首次登录后修改）。

## 升级/回滚

数据目录 /opt/pcl-dsh-linux/data 不受影响；升级仅替换 releases/<版本> 并切换 current 软链，回滚切回旧版本目录即可。

## 许可

GPL-3.0-or-later，含 HMCL 第 7 条附加条款。发布物包含 LICENSE、LICENSES/、THIRD_PARTY_NOTICES.md 与对应源码包。

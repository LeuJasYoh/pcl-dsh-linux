# PCL-DSH-Linux 0.3.1

发布工程更新：发行版内置完整 Node 工具链，服务器部署不再依赖系统 Node/pnpm。

## 变更

- **打包脚本支持本地 Node 压缩包**：`package-linux.ps1` 新增 `-NodeTarball` 参数，可直接内置本地 `node-vX.Y.Z-linux-x64.tar.xz`，无需从 nodejs.org 下载（本发行包内置 Node **24.22.1**，此前为 24.21.0）。
- **完整性校验不降级**：使用本地压缩包时仍自动获取官方 `SHASUMS256.txt` 做 SHA-256 校验；校验文件不可达时仅警告并跳过，不阻断打包。
- **修复打包脚本隐患**：Node 解压的排除项此前硬编码 `node-v24.21.0-linux-x64`，换用其他 Node 版本时会失效；现按 `$NodeVersion` 动态生成。
- 应用功能与 0.3.0 完全一致（core 105 + server 77 项测试通过）。

## 自包含工具链

发布包 `release/tools/` 内置：

| 工具 | 版本 | 说明 |
| --- | --- | --- |
| Node.js | 24.22.1 (linux-x64) | 官方压缩包，SHA-256 校验通过 |
| pnpm | 10.34.0 | `bin/pnpm.cjs` 纯 JS 入口（`node pnpm.cjs` 调用方式兼容） |
| npm / npx / corepack | 随 Node 附带 | install.sh 会修复 Windows 打包造成的符号链接问题 |

服务按「发布包 tools → data/tools → 系统 PATH」顺序查找 node/pnpm，优先使用内置工具，服务器无需安装 Node 工具链。

## 安装（Ubuntu 24.04 x86_64）

前置：JDK 21（或更高）、Nginx、root/sudo。

1. 下载并校验发布包：`sha256sum -c pcl-dsh-linux-0.3.1-linux-x64.tar.gz.sha256`
2. 解压并安装：

       tar -xzf pcl-dsh-linux-0.3.1-linux-x64.tar.gz
       cd pcl-dsh-linux-0.3.1-linux-x64
       sudo bash install.sh

3. 浏览器访问 http://<服务器IP>/ ，初始密码见 /opt/pcl-dsh-linux/data/initial-password（首次登录后修改）。

## 升级/回滚

数据目录 /opt/pcl-dsh-linux/data 不受影响；升级仅替换 releases/<版本> 并切换 current 软链，回滚只需切回旧版本目录。详见 docs/DEPLOYMENT_VALIDATION.md。

## 打包（维护者）

    .\scripts\package-linux.ps1 -NodeTarball '<本地 node-vX.Y.Z-linux-x64.tar.xz>'

不指定 `-NodeTarball` 时行为与 0.3.0 相同（自动从 nodejs.org 下载默认版本）。

## 许可

GPL-3.0-or-later，含 HMCL 第 7 条附加条款。发布物包含 LICENSE、LICENSES/、THIRD_PARTY_NOTICES.md 与对应源码包。

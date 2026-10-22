# PCL-DSH-Linux

PCL-DSH-Linux 是 [HDSL](https://github.com/jiefing/HDSL) 桌面版的 Linux WebUI 移植版本：在 Linux 服务器上以 Spring Boot 服务运行，浏览器访问，负责管理 DeepSeek Harness（DSH）实例、运行时、账户、插件与整合包。

- 目标平台：Ubuntu 24.04 x86_64（headless），Nginx 作为公网入口。
- 前端：Vue 3 + TypeScript，界面全量复刻 HDSL 桌面版。
- 后端：Spring Boot 3.5.x（Java 21），复用 HDSL 0.2.0 的业务层（实例/运行时/账户/整合包/目录）。
- 许可：GPL-3.0-or-later（含 HMCL 第 7 条附加条款）。详见 LICENSE、LICENSES/、THIRD_PARTY_NOTICES.md。

## 当前状态

M1 起步阶段：已建立 Maven 多模块骨架并迁入可复用的 core 业务代码；Web 层与前端仍在开发中。方案见 [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md)。

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

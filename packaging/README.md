# packaging

发布与部署资产（M4 使用）：

- `pcl-dsh-linux.service`：systemd 单元模板（服务用户 pcl-dsh，数据目录 /opt/pcl-dsh-linux/data，Java 只监听 127.0.0.1:8080）。
- `nginx-pcl-dsh-linux.conf`：Nginx 站点模板（80 端口入口、静态资源直出、/api 反代、/workbench 反代 + WebSocket、SSE 不缓冲、2 GiB 上传、登录限流）。
- install.sh：安装/升级（创建 pcl-dsh 用户、安装 systemd、按需配置 Nginx、等待健康检查、打印初始密码位置）。
- uninstall.sh：卸载（默认保留 data；--purge 才删除）。
- reset-password.sh：忘记密码时重置（备份并重建 server.json）。
- 发布包由 scripts/package-linux.ps1 生成：内含 Node 24.21.0 linux-x64、pnpm 10.34.0、可执行 jar、前端静态资源、许可与源码包。

Windows 打包无法保留 Linux 可执行位与符号链接：install.sh 会在安装时 chmod node 并补齐 npm/npx/corepack 符号链接，因此请用 `sudo bash install.sh` 运行安装脚本。

目标目录布局（详见 docs/IMPLEMENTATION_PLAN.md §2.6）：

    /opt/pcl-dsh-linux/
      current -> releases/<version>
      releases/<version>/{pcl-dsh-linux.jar, static/, tools/{node,pnpm}}
      data/{config,instances,runtimes,tools/shims,cache,logs,trash}

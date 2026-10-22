# packaging

发布与部署资产（M4 使用）：

- `pcl-dsh-linux.service`：systemd 单元模板（服务用户 pcl-dsh，数据目录 /opt/pcl-dsh-linux/data，Java 只监听 127.0.0.1:8080）。
- `nginx-pcl-dsh-linux.conf`：Nginx 站点模板（80 端口入口、静态资源直出、/api 反代、/workbench 反代 + WebSocket、SSE 不缓冲、2 GiB 上传、登录限流）。
- install.sh / uninstall.sh / 升级脚本：待发布包结构与静态资源接入完成后提供（M4）。

目标目录布局（详见 docs/IMPLEMENTATION_PLAN.md §2.6）：

    /opt/pcl-dsh-linux/
      current -> releases/<version>
      releases/<version>/{pcl-dsh-linux.jar, static/, tools/{node,pnpm}}
      data/{config,instances,runtimes,tools/shims,cache,logs,trash}

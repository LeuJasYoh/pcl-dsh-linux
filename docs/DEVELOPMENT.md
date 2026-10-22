# 本地开发与构建

## 依赖

- JDK 21（本机：D:/DevelopSoftware/Java/JDK-21）
- Maven 3.9.x（本机：D:/DevelopSoftware/Apache-Maven/apache-maven-3.9.9）
- Node 24 + npm（本机 Node v24.22.1；仅前端构建/开发需要）

当前 DSH 会话的 PATH 未展开变量，构建统一使用脚本（脚本内会显式设置 JAVA_HOME/PATH）：

    powershell -File scripts/mvn.ps1 -B -ntp verify     # 后端测试
    powershell -File scripts/build.ps1                  # 后端打包 + 前端构建
    powershell -File scripts/build.ps1 -SkipWeb         # 只做后端

前端单独开发：

    cd pcl-dsh-web
    npm install
    npm run dev        # http://127.0.0.1:5173 ，/api 代理到 8080
    npm run build

## 本地联调

1. 启动后端（临时数据目录，避免污染仓库）：

       java -jar pcl-dsh-server/target/pcl-dsh-linux.jar --pcl.data-root=./.dev-data

   首次启动会把随机初始管理员密码写入 .dev-data/initial-password。
2. 启动前端 dev server：cd pcl-dsh-web && npm run dev。
3. 浏览器打开 http://127.0.0.1:5173/ ，用初始密码登录并按提示改密。

## 目录

- pcl-dsh-core：复用业务层（纯 Java，无 Spring/JavaFX）
- pcl-dsh-server：Spring Boot Web 服务
- pcl-dsh-web：Vue 3 + TypeScript 前端
- packaging：systemd / Nginx 模板（发布阶段）
- docs：可行性分析、实施方案、M0 PoC 手册

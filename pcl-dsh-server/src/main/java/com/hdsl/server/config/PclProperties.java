package com.hdsl.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * PCL-DSH-Linux 自有配置（{@code pcl.*}）。
 *
 * <p>数据目录优先级由 application.yml 的占位符实现：
 * 环境变量 {@code PCL_DSH_DATA} &gt; 系统属性 {@code -Dpcl.data} &gt; {@code ./data}。</p>
 */
@ConfigurationProperties(prefix = "pcl")
public class PclProperties {

    /** 数据根目录，所有持久化状态都位于该目录下。 */
    private Path dataRoot = Path.of("data");

    /** 对外展示的版本号。 */
    private String version = "0.3.0";

    /** 会话空闲过期时间（滑动过期），默认 12 小时。 */
    private Duration sessionTimeout = Duration.ofHours(12);

    /** SSE 心跳间隔，默认 15 秒。 */
    private Duration sseHeartbeat = Duration.ofSeconds(15);

    /** SSE 单条连接的服务端超时，默认 30 分钟（任务事件会提前结束）。 */
    private Duration sseTimeout = Duration.ofMinutes(30);

    /** 登录失败计数窗口，默认 60 秒。 */
    private Duration loginWindow = Duration.ofSeconds(60);

    /** 达到失败次数后的锁定时长，默认 60 秒。 */
    private Duration loginLock = Duration.ofSeconds(60);

    /** 窗口内允许的连续失败次数，默认 5 次。 */
    private int loginMaxFailures = 5;

    /** 变更任务默认超时，默认 20 分钟（各任务类型可显式覆盖）。 */
    private Duration jobTimeout = Duration.ofMinutes(20);

    /** 发布包只读 tools 目录（Node/pnpm）；留空时自动从 jar 所在目录推断，例如 /opt/pcl-dsh-linux/current/tools。 */
    private String toolRoot = "";

    /** 是否启用 /workbench/{instanceId}/** 工作台反向代理（关闭时 web-url 返回回环绝对地址，供本机开发）。 */
    private boolean proxyEnabled = true;

    /** 反代普通请求等待上游响应头/首个字节的超时，默认 60 秒（SSE/WS 长连接不受此限制）。 */
    private Duration proxyTimeout = Duration.ofSeconds(60);

    /** 反代连接上游（127.0.0.1:实例端口）的建立超时，默认 5 秒。 */
    private Duration proxyConnectTimeout = Duration.ofSeconds(5);

    public Path getDataRoot() {
        return dataRoot;
    }

    public void setDataRoot(Path dataRoot) {
        this.dataRoot = dataRoot;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Duration getSessionTimeout() {
        return sessionTimeout;
    }

    public void setSessionTimeout(Duration sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    public Duration getSseHeartbeat() {
        return sseHeartbeat;
    }

    public void setSseHeartbeat(Duration sseHeartbeat) {
        this.sseHeartbeat = sseHeartbeat;
    }

    public Duration getSseTimeout() {
        return sseTimeout;
    }

    public void setSseTimeout(Duration sseTimeout) {
        this.sseTimeout = sseTimeout;
    }

    public Duration getLoginWindow() {
        return loginWindow;
    }

    public void setLoginWindow(Duration loginWindow) {
        this.loginWindow = loginWindow;
    }

    public Duration getLoginLock() {
        return loginLock;
    }

    public void setLoginLock(Duration loginLock) {
        this.loginLock = loginLock;
    }

    public int getLoginMaxFailures() {
        return loginMaxFailures;
    }

    public void setLoginMaxFailures(int loginMaxFailures) {
        this.loginMaxFailures = loginMaxFailures;
    }

    public Duration getJobTimeout() {
        return jobTimeout;
    }

    public void setJobTimeout(Duration jobTimeout) {
        this.jobTimeout = jobTimeout;
    }

    public String getToolRoot() {
        return toolRoot;
    }

    public void setToolRoot(String toolRoot) {
        this.toolRoot = toolRoot;
    }

    public boolean isProxyEnabled() {
        return proxyEnabled;
    }

    public void setProxyEnabled(boolean proxyEnabled) {
        this.proxyEnabled = proxyEnabled;
    }

    public Duration getProxyTimeout() {
        return proxyTimeout;
    }

    public void setProxyTimeout(Duration proxyTimeout) {
        this.proxyTimeout = proxyTimeout;
    }

    public Duration getProxyConnectTimeout() {
        return proxyConnectTimeout;
    }

    public void setProxyConnectTimeout(Duration proxyConnectTimeout) {
        this.proxyConnectTimeout = proxyConnectTimeout;
    }
}

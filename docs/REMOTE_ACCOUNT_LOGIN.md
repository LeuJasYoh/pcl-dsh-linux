# 远程访问下的 DeepSeek 账号网页登录限制

本文说明为什么在**远程浏览器访问**（Nginx + `/workbench/` 反代）时，实例内的
「DeepSeek 账号网页登录」会失败（界面提示“登录失败 / 操作未完成，请重试”），以及可用的
替代做法。这是上游 DSH 的设计约束，不是本项目的缺陷。

## 1. 现象

- 通过 `http://<服务器IP>/workbench/<实例id>/` 打开工作台，页面本身正常。
- 点击登录 DeepSeek 账号 → 立即或短暂等待后失败，提示“登录失败 / 操作未完成，请重试”。
- 服务器到 DeepSeek 的网络是通的（`api.deepseek.com`、`platform.deepseek.com` 均可访问），
  因此不是网络或反代连通性问题。

## 2. 根因

DSH 的 DeepSeek 账号登录是 OAuth 流程，并且**回调地址被严格限制为回环地址**：

```js
// @deepseek-ai/dsh-deepseek-account-platform/lib/types/protocol.js
export function loginOrigin(value) {
    const explicitPort = /^http:\/\/(?:localhost|127\.0\.0\.1|\[::1\]):([0-9]+)\/?$/i.exec(value)?.[1];
    if (explicitPort === undefined
        || url.protocol !== 'http:'
        || !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
        /* ... */) {
        throw new PlatformAuthError('protocol');   // 非回环地址直接拒绝
    }
    return `${url.protocol}//${url.hostname}:${Number(explicitPort)}`;
}
```

```js
// 登录入口：回调地址 = 校验后的回环 origin + /oauth/callback
async startSignIn(client, callbackOrigin, loginSource) {
    const origin = loginOrigin(callbackOrigin);
    ...
    const redirectUri = `${attempt.origin}/oauth/callback`;
}
```

由此产生两个必然结果：

1. **远程浏览器无法通过校验**：页面 origin 是 `http://<服务器IP>`，不属于回环地址，
   登录请求在参数校验阶段就失败，界面显示“操作未完成”。
2. **即使通过校验，回调也回不到服务器**：OAuth 回调始终是
   `http://127.0.0.1:<实例端口>/oauth/callback`，浏览器会被重定向到**用户本机**的
   127.0.0.1，而不是服务器上的实例。

> 这是 DSH 面向桌面/本机场景的设计（桌面版实例与浏览器在同一台机器）。反向代理无法改变
> 这个协议约束，本项目也不会绕过上游的安全校验。

### 不受影响的功能

- 工作台页面本身、API 调用、SSE / WebSocket 反向代理；
- **API Key 模式**（在 DSH 里选“添加 API Key”，或在 PCL 的「账户」页绑定 API Key 并在实例上绑定）：
  这种方式不依赖网页 OAuth，远程访问可正常使用；
- 实例管理、运行时安装、插件、整合包、日志等所有 PCL 功能。

## 3. 解决方案：SSH 隧道完成一次登录

登录成功后凭据保存在**服务器实例目录**（`dsh-home/.credentials.yaml`），之后可通过正常的
`/workbench/` 地址使用，无需每次登录。

### 步骤

1. 在本机终端建立隧道（把本机端口映射到服务器实例端口）：

   ```bash
   ssh -L <实例端口>:127.0.0.1:<实例端口> root@<服务器IP>
   ```

2. 在浏览器打开**回环直连地址**（含一次性 token）：

   ```
   http://127.0.0.1:<实例端口>/?token=<token>
   ```

   此时页面 origin 是回环地址，OAuth 流程合法，DeepSeek 登录页 → 回调 → 隧道 → 服务器实例，
   登录完成。

3. 关闭隧道，回到 `http://<服务器IP>/workbench/<实例id>/` 继续使用（已保持登录态）。

### 在 PCL 界面中获取以上内容

实例列表每个实例的「**直连登录**」按钮会打开对话框，直接提供：

- **SSH 隧道命令**（可一键复制）；
- **回环直连地址（含 token）**（实例就绪后显示，可一键复制）；
- 两步操作说明与安全提示。

实现位置：`GET /api/instances/{id}/direct-access` → 返回
`{ready, port, directUrl, tunnelCommand, sshHost, sshUser, note}`；前端组件
`pcl-dsh-web/src/components/DirectAccessDialog.vue`。

> token 由实例在每次运行时生成，实例重启后直连地址会变化；隧道命令只做端口映射，
> 不改变服务器监听地址。直连地址含访问 token，请勿外传。

## 4. 备选方案

### 4.1 使用 API Key（推荐给“只想跑模型”的场景）

在 PCL「账户」页绑定供应商（如 DeepSeek 官方 `api.deepseek.com` 或任意 OpenAI 兼容端点），
并在实例上绑定该账户；启动实例时由启动器注入（`--patch account.patch.json`）。
这种方式不涉及网页 OAuth，远程使用完全正常。

### 4.2 在服务器本机浏览器登录（不推荐）

若服务器带桌面环境（WSLg / 远程桌面），可以在服务器本机浏览器完成登录；headless 生产服务器
通常不具备条件。

## 5. 排障清单

| 现象 | 检查点 |
| --- | --- |
| 对话框显示“实例未运行 / 未就绪” | 先启动实例，等待状态变为“运行中” |
| 直连地址为空 | 实例运行时未输出 token（版本差异），停止后重新启动实例 |
| 隧道命令执行报端口占用 | 本机端口被占用时改用其他本地端口：`ssh -L 13080:127.0.0.1:<实例端口>`，并访问 `http://127.0.0.1:13080/...` |
| 登录后仍显示未登录 | 确认登录是在**隧道打开时**完成的，且浏览器地址为 127.0.0.1；再检查实例 `dsh-home/.credentials.yaml` 是否生成 |

## 6. 版本说明

本文依据 DSH 0.2.1-rc.x 的实现（`@deepseek-ai/dsh-deepseek-account-platform`）。上游若在
后续版本放宽回环限制（例如支持可配置的外部回调地址），本限制可能解除；届时以实际行为为准。

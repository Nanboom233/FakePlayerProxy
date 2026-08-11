# Research: Paper / Spigot connection throttle

- Query: Paper 目标服为何会拒绝 Velocity 在 provisional backend 关闭后立即发起的 raw reconnect；确认配置、默认值、判定键、记录阶段、拒绝消息、安全等待公式以及 1.20 到 26.2 的变化。
- Scope: mixed（本项目调用链 + Paper 官方文档、官方源码与官方构建元数据）
- Date: 2026-08-11

## Findings

### 结论摘要

当前 Paper 26.2 的相关配置是服务器根目录 `bukkit.yml` 中的
`settings.connection-throttle`。默认值为 `4000`，单位是毫秒，含义是同一
IP 两次 Login 入口尝试之间必须达到的时间间隔。Paper 官方文档明确写为
“自上次尝试以来的毫秒数”；官方默认配置资源同样写入 `4000`。

源码只在值 `> 0` 时执行检查，因此 `0` 和任意负值都禁用该检查。另有一个
重要例外：当目标 Paper 启用了 Spigot Bungee forwarding 或 Paper Velocity
forwarding 时，`CraftServer#getConnectionThrottle()` 强制返回 `-1`，即使
`bukkit.yml` 中仍是正值也不会执行节流。

普通 TCP 情况下，节流键是 `Connection#getRemoteAddress()` 中已解析的
`InetAddress`：不包含源端口、hostname 或用户名。loopback 地址与 Unix domain
socket 被豁免。FakePlayerProxy 的两条目标连接都由 Velocity 发起，因此在没有
受信代理转发改变地址的普通部署中，Paper 看到的是 Velocity 主机的源 IP；第二次
连接会与 provisional backend 共用同一个节流槽。

检查不发生在 TCP `accept`，也不等到 Login Start。当前源码在解码并分派
`ClientIntentionPacket`（Minecraft handshake）后进入 `beginLogin`，随即读取时间、
检查并写入表；这早于协议版本拒绝和安装 `ServerLoginPacketListenerImpl`，所以也早于
用户名所在的 Login Start。FakePlayerProxy raw tunnel 把第二次 `TRANSFER` handshake
改写为 `LOGIN` 后发给目标，因此正好进入这个检查。

命中条件是：

```text
currentTimeMillis - lastAttemptMillis < connectionThrottleMillis
```

允许条件因此是时间差 `>= T`，其中 `T = settings.connection-throttle`。首次允许的
尝试会记录当前时间；更关键的是，**被拒绝的尝试也会先把该 IP 的时间戳刷新为当前
时间**，然后断开。这意味着每次过早实测都会把等待窗口重新开始。关闭第一条连接
不会删除时间戳。

默认拒绝行为是在 Login 协议中发送 `ClientboundLoginDisconnectPacket`，然后以同一
原因断开。当前默认文字是：

```text
Connection throttled! Please wait before reconnecting.
```

该文字可在 `config/paper-global.yml` 的
`messages.kick.connection-throttle` 修改。节流分支本身没有 info/warn 日志；只有检查
内部抛出异常时才写 debug 日志。因此可观察证据主要是客户端收到的 Login Disconnect
（在本项目 raw handoff 后会作为目标字节透明转发）和随后的连接关闭，而不是 Paper
控制台必然出现一条“throttled”日志。

### 对当前 Transfer 流程的等待推导

设 Paper 对第一次 provisional handshake 的记录时间为 `t0`，最近一次被拒绝尝试的
记录时间为 `tr`，有效的最后记录为 `L = max(t0, tr)`，目标配置为 `T`。第二次目标
handshake 到达 Paper 的时间 `t1` 必须满足：

```text
t1 >= L + T
```

因此精确剩余等待是 `max(0, L + T - now)`。但 Velocity 不知道 Paper 的 `L`，也没有
标准 Minecraft 协议字段或 Velocity API可读取远端 `bukkit.yml` 的 `T`。Paper 拒绝包
只给可配置文字，不携带数值。除非运营侧共享该配置、允许 Velocity 读取同机配置，
或另建目标插件/管理通道，否则 Velocity 无法自动获知远端值。

对现有实现，若延迟从 provisional backend 已关闭之后才开始，固定等待 `T` 毫秒是
保守保证：关闭发生在第一次 handshake 记录之后；没有中间重试时，第二次 handshake
到达只会更晚。生产调度还应保证实际经过时间不少于 `T`，不能提前触发。按当前默认值，
目标端两次有效 handshake 的间隔下限是 `4000 ms`；“延迟 1 秒”只有在第一次记录到
第二次到达之间的其他流程已经额外消耗至少约 3 秒，或目标 `T <= 1000` / 已禁用节流
时才可能成功，不能作为默认安全值。若刚刚已有一次失败实测，则必须从那次失败重新
累计完整 `T`。

配置协同的可靠方案是让部署显式提供 `T`（或在目标端将其设为 `0`/负值），而不是由
Velocity 猜测。若目标启用了 Paper Velocity forwarding，则源码会自动返回 `-1`；但
本任务的 raw online-mode tunnel 不应假定这一点，因为它并不使用 Velocity forwarding
协议。

### Paper 1.20 与 26.2 的证据边界

Paper 官方 Fill 元数据把 Paper `1.20.1` build 196 对应到源码提交
`773dd724469bae89d0c2075edc3d1ddc8d5b0b18`。该提交的官方 Paper patch 上下文已经显示：

- 以 `InetAddress address` 查 `throttleTracker`；
- 使用 `System.currentTimeMillis()` 差与 `connectionThrottle` 比较；
- 命中时刷新时间戳；
- 发送 Login Disconnect 并断开；
- 默认文字当时已经由 Paper global configuration 提供。

当前 Paper `main` 提交 `c9e894d3cc03f21f80de4f4061a795e11941e89a` 的
`gradle.properties` 明确为 Minecraft/Paper `26.2`。端点对比表明普通非 loopback TCP
连接的核心语义（按 IP、毫秒窗口、handshake 阶段、失败刷新时间戳、Login Disconnect）
没有变化。可直接看到的实现演进是：

- 1.20.1 patch 上下文只用字符串排除 `127.0.0.1`；26.2 使用
  `InetAddress#isLoopbackAddress()`，覆盖完整 loopback 范围。
- 26.2 另行豁免 Unix domain socket。
- 新协议已有 `TRANSFER` handshake，但目标在本项目中收到的是改写后的 `LOGIN`；二者
  都在 `beginLogin` 入口共用当前检查逻辑。
- 代码从旧的编号 patch 布局迁到当前 source patch 布局，配置名和默认值未变。

这不是对 1.20.0 到 26.2 每个 Paper build 的逐一审计；结论严格限于官方 1.20.1 build
196 端点与当前 26.2 源码。现有一手证据足以确认本次 live test 的等待决策，不把中间
所有版本都未变作为未经证明的结论。

## Files Found

- `plugin/patch/0001-server-hello-marker.patch:743`：`startTransfer()` 在 PostLogin 完成后立即调用 `transferToHost`，当前没有节流等待。
- `plugin/patch/0001-server-hello-marker.patch:1929`：第二次 Login Start 到达后才建立 raw target TCP 连接。
- `plugin/patch/0001-server-hello-marker.patch:2009`：向目标写入 intent 改为 `LOGIN` 的 handshake，随后写原 Login Start。
- `.trellis/tasks/08-10-client-login-negotiation-research/design.md:86`：设计要求先关闭 provisional backend，再完成短 Login 和 Transfer。
- `.trellis/tasks/08-10-client-login-negotiation-research/prd.md:84`：R3 当前要求在 Login Acknowledged 后 Transfer，但未规定 throttle 间隔。

## Code Patterns And External References

- Paper 官方 `bukkit.yml` 文档（当前结构、默认值与毫秒语义）：https://docs.papermc.io/paper/reference/bukkit-configuration/
- Paper docs 源数据（`settings.connection-throttle`）：https://github.com/PaperMC/docs/blob/main/src/config/paper/bukkit.yml
- Paper 26.2 默认 `bukkit.yml` 资源（默认 `4000`）：https://github.com/PaperMC/Paper/blob/c9e894d3cc03f21f80de4f4061a795e11941e89a/paper-server/src/main/resources/configurations/bukkit.yml#L12-L19
- Paper 26.2 配置读取与 forwarding 自动禁用：https://github.com/PaperMC/Paper/blob/c9e894d3cc03f21f80de4f4061a795e11941e89a/paper-server/src/main/java/org/bukkit/craftbukkit/CraftServer.java#L869-L875
- Paper 26.2 handshake 节流实现：https://github.com/PaperMC/Paper/blob/c9e894d3cc03f21f80de4f4061a795e11941e89a/paper-server/patches/sources/net/minecraft/server/network/ServerHandshakePacketListenerImpl.java.patch#L43-L76
- Paper 26.2 默认拒绝文字：https://github.com/PaperMC/Paper/blob/c9e894d3cc03f21f80de4f4061a795e11941e89a/paper-server/src/main/java/io/papermc/paper/configuration/GlobalConfiguration.java#L84-L94
- Paper 官方 global config 文档源数据（可配置消息）：https://github.com/PaperMC/docs/blob/main/src/config/paper/paper-global.yml
- Paper 26.2 版本声明：https://github.com/PaperMC/Paper/blob/c9e894d3cc03f21f80de4f4061a795e11941e89a/gradle.properties#L1-L6
- Paper 官方 1.20.1 构建元数据：https://fill.papermc.io/v3/projects/paper/versions/1.20.1/builds
- Paper 1.20.1 build 196 对应 patch 上下文：https://github.com/PaperMC/Paper/blob/773dd724469bae89d0c2075edc3d1ddc8d5b0b18/patches/server/0261-Configurable-connection-throttle-kick-message.patch

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`：当前 Vanilla Transfer/raw tunnel 的连接边界、事件循环和失败处理约束。
- `.trellis/spec/language/java.md`：诊断消息应明确失败阶段；本研究没有修改代码或 spec。
- `.trellis/tasks/08-10-client-login-negotiation-research/prd.md`：R3/R4 与 live-test acceptance criteria 是本问题的直接需求来源。
- `.trellis/tasks/08-10-client-login-negotiation-research/design.md`：第一次 provisional backend 与第二次 raw target 连接的时序来源。

## Caveats / Not Found

- 没有发现 Velocity 标准 API 或 Minecraft login 协议能够读取远端 Paper 的节流毫秒值；
  这是从 Paper 当前 wire 行为（仅发送可配置 disconnect 文字）与本项目 raw tunnel 边界得出的
  否定性结论，不等价于穷举所有第三方管理插件。
- 源码用 `System.currentTimeMillis()` 而非单调时钟。系统时钟向后调整会延长实际窗口；正常
  实测不应依赖恰好等于边界的跨主机时钟假设。
- Paper 根据实际远端 `InetAddress` 判定。HAProxy、forwarding、loopback、Unix socket、
  多出口地址或 IPv4/IPv6 路由变化会改变是否共用同一个槽；本次结论针对两条连接从同一
  Velocity TCP 源 IP 到达目标的部署。
- 未逐一审计 1.20.x、1.21.x、26.1 和 26.2 的每个构建；版本结论只比较官方 1.20.1
  build 196 与当前 26.2 提交。

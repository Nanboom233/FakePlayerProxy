# Research: ServerConnectedEvent 登录状态取得

> 状态：已由 `registration-connection-lifecycle.md` 替代。该方案漏掉首次配置阶段的 Registry Packet，不进入实施上下文。

- Query: 在不改变 `AutomationService` 由 `ServerConnectedEvent` 注册的前提下，Plugin 如何取得 MCProtocolLib `ClientboundLoginPacket` 所需的 `entityId`、dimension、world 和 `PlayerSpawnInfo`。
- Scope: internal / mixed
- Date: 2026-08-13

## Findings

### 结论

不改注册时点。

Patch 应增强同一个 `ServerConnectedEvent`。

事件应直接提供本次准确的 `ServerConnection` 和本次 `ClientboundLoginPacket`。

Plugin 在唯一的 `ServerConnectedEvent` listener 中完成以下操作：

1. 从 `getPlayer()` 取得 `Player`。
2. 从 `getServerConnection()` 取得本次后端连接。
3. 从 `getLoginPacket()` 取得 `entityId`、world 集合和 `PlayerSpawnInfo`。
4. 创建并注册 `AutomationService`。

该方案不需要以下结构：

- Login Packet listener 的第二注册点。
- 以 `Player` 或 UUID 为键的临时登录状态 Map。
- 在 `VelocityServerConnection` 中长期保存登录 Packet。
- Plugin 对 Velocity 内部 `JoinGamePacket` 的反射访问。

### 固定 Velocity 的事件顺序

固定上游为 Velocity commit `843a47e2a38325309cd66133149fc9a984f76bb8`。版本来源见 `plugin/patch/velocity-base.properties:1-2`。

`TransitionSessionHandler.handle(JoinGamePacket)` 按以下顺序执行：

1. `JoinGamePacket` 作为当前方法的局部参数存在。见 `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/TransitionSessionHandler.java:91`。
2. Velocity 停止后端自动读取。见同文件 `:112-114`。
3. Velocity 发布并等待 `ServerConnectedEvent`。见同文件 `:114-116`。
4. 事件完成后，Velocity 才调用 `handleBackendJoinGame(packet, serverConn)`。见同文件 `:124-139`。
5. `handleBackendJoinGame` 把 `entityId` 写入 `VelocityServerConnection`。见 `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ClientPlaySessionHandler.java:627-650`。
6. Velocity 随后才把本次后端写入 `ConnectedPlayer.connectedServer`。见 `TransitionSessionHandler.java:141-147`。
7. Velocity 最后恢复后端自动读取。见同文件 `:144-147`。

因此，`ServerConnectedEvent` 是可靠的初始化屏障。

事件执行时，后端不会继续发送 GAME Packet。

但是，现有事件内容不足。

### 现有公共 API 不足

现有 `ServerConnectedEvent` 只保存以下数据：

- `Player`。
- `RegisteredServer`。
- 可空的前一个 `RegisteredServer`。

证据见 `plugin/build/server/source/api/src/main/java/com/velocitypowered/api/event/player/ServerConnectedEvent.java:28-57`。

事件执行时，`Player.getCurrentServer()` 仍返回旧连接或空值。

`ConnectedPlayer.getCurrentServer()` 只读取 `connectedServer`。见 `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ConnectedPlayer.java:292-295`。

本次连接仍位于 `connectionInFlight`。

该内部字段可以通过 Plugin 现有 helper 取得准确连接。

但是，连接本身仍没有完整登录状态。

`VelocityServerConnection` 只长期保存一个可空 `entityId`。见 `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/VelocityServerConnection.java:65-75`。

该字段要到事件完成后才写入。

`VelocityServerConnection` 不保存以下数据：

- world 名称集合。
- dimension registry ID。
- world 名称。
- hashed seed。
- game mode。
- previous game mode。
- debug 和 flat 标志。
- last death position。
- portal cooldown。
- sea level。

因此，现有 `Player`、`ServerConnection` 和 `VelocityServerConnection` 不能重建完整 `PlayerSpawnInfo`。

### `JoinGamePacket` 已包含所需线上字段

Velocity 的 `JoinGamePacket` 在 26.2 解码以下字段：

- `entityId`。
- hardcore。
- world 名称集合。
- max players。
- view distance。
- simulation distance。
- reduced debug info。
- respawn screen。
- limited crafting。
- dimension registry ID。
- world 名称。
- hashed seed。
- game mode。
- previous game mode。
- debug 和 flat 标志。
- last death position。
- portal cooldown。
- sea level。
- online mode。
- secure chat enforcement。

解码证据见 `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/JoinGamePacket.java:323-372`。

编码证据见同文件 `:478-528`。

事件发布时，该 Packet 仍由 `TransitionSessionHandler.handle` 的异步 continuation 捕获。

因此，不需要从连接状态反向查找这些字段。

Patch 可以把同一次 Decoder 转换得到的 MCProtocolLib `ClientboundLoginPacket` 绑定到该 `JoinGamePacket` 实例。

`TransitionSessionHandler` 随后把该对象放入 `ServerConnectedEvent`。

该状态只跟随当前 Packet 实例存活。

它不是全局缓存。

它不会增加第二个生命周期所有者。

### 最小通用 API

建议把 `ServerConnectedEvent` 的构造器改为：

```java
ServerConnectedEvent(
    Player player,
    ServerConnection serverConnection,
    @Nullable RegisteredServer previousServer,
    ClientboundLoginPacket loginPacket)
```

事件提供以下方法：

```java
Player getPlayer()
ServerConnection getServerConnection()
RegisteredServer getServer()
Optional<RegisteredServer> getPreviousServer()
ClientboundLoginPacket getLoginPacket()
```

`getServer()` 从 `serverConnection.getServer()` 返回原有结果。

该接口没有 Shadow 语义。

它描述一次已经收到 Login Packet，但尚未恢复后端读取的服务端连接生命周期。

其他 Plugin 也可以使用该接口初始化协议状态。

`ServerConnection` 仍不需要增加登录状态 getter。

该对象只表示连接。

登录 Packet 只属于本次事件。

### 为什么不使用普通 S2C Packet Event 完成初始化

Decoder 可以在 `ServerConnectedEvent` 之前发布 `S2CPacketEvent<ClientboundLoginPacket>`。

但是，已决定的 `AutomationService` 尚未注册。

Plugin 若在该 Packet Event 中创建状态，就会形成第二注册点。

Plugin 若只暂存 Packet，就需要临时全局 Map。

普通 Packet Event 也不提供准确的 `ServerConnection`。

因此，它不能替代增强后的生命周期事件。

`ClientboundLoginPacket` 仍可以发布普通 `S2CPacketEvent`。

该 Event 适合 Packet 观察和改写。

`ServerConnectedEvent` 适合连接注册和初始状态移交。

两者职责不同。

### 为什么不延后到 ServerPostConnectEvent

`ServerPostConnectEvent` 在 `handleBackendJoinGame`、后端 play handler 安装、`connectedServer` 写入和自动读取恢复之后发布。见 `TransitionSessionHandler.java:124-157`。

改用该 Event 会改变已定注册时点。

它还会失去“后端暂停读取”的初始化屏障。

因此，不应改用 `ServerPostConnectEvent`。

### MCProtocolLib build 15 不兼容 26.2 Login Packet

项目当前固定 `org.geysermc.mcprotocollib:protocol:26.2-20260709.110151-15`。见 `plugin/build.gradle.kts:12`。

该构建的 `ClientboundLoginPacket` 在 `PlayerSpawnInfo` 后直接读取 `enforcesSecureChat`。

它缺少 26.2 的 `onlineMode` 字段。

本地固定源码见 `build/tmp/mcprotocollib-sources.jar!/org/geysermc/mcprotocollib/protocol/packet/ingame/clientbound/ClientboundLoginPacket.java:17-44`。

Velocity 26.2 在线格式在 `PlayerSpawnInfo` 后依次编码 `onlineMode` 和 `enforcesSecureChat`。见 `JoinGamePacket.java:522-527`。

因此，build 15 会把 `onlineMode` 误读为 `enforcesSecureChat`，并留下一个未消费字节。

该缺口不只影响 `ServerConnectedEvent`。

它会影响通用 `S2CPacketEvent<ClientboundLoginPacket>` 的转换正确性。

MCProtocolLib `26.2-20260809.160751-16` 已增加 `onlineMode` 字段，并按正确顺序读写两个布尔值。

版本证据来自 OpenCollab snapshot metadata，最后更新时间为 `2026-08-09T16:07:51Z`。

源码证据来自该仓库的 build 16 sources JAR。

实现前必须把共享 MCProtocolLib 版本升级到 build 16 或更新版本。

不建议在 Patch 中为 build 15 增加 Login Packet 特例。

版本升级可以让通用 Packet 转换继续使用 MCProtocolLib codec。

### Plugin 初始化形状

Plugin listener 可以保持一个注册点：

```java
@Subscribe
public void onServerConnected(ServerConnectedEvent event) {
    AutomationService service = new AutomationService(
        event.getPlayer(),
        event.getServerConnection(),
        event.getLoginPacket());
    automationManager.register(event.getPlayer(), service);
}
```

`AutomationService` 从 `ClientboundLoginPacket` 初始化玩家状态。

它不再等待第二次 Login Packet 分发。

后续 `S2CPacketEvent` 只应用增量状态。

## Files Found

- `plugin/patch/velocity-base.properties:1-2`：固定 Velocity 仓库和 commit。
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/TransitionSessionHandler.java:91-168`：Join Game、事件、handler 安装和恢复读取的顺序。
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ClientPlaySessionHandler.java:627-650`：事件完成后才保存 `entityId`。
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ConnectedPlayer.java:292-307`：`getCurrentServer()` 只读取已提交连接。
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/VelocityServerConnection.java:65-75`：后端连接只保存可空 `entityId`。
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/JoinGamePacket.java:31-206`：Velocity 登录 Packet 字段和 getter 范围。
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/JoinGamePacket.java:323-372`：26.2 Login Packet 解码。
- `plugin/build/server/source/api/src/main/java/com/velocitypowered/api/event/player/ServerConnectedEvent.java:28-57`：原生事件当前只提供 Player 和 RegisteredServer。
- `build/tmp/mcprotocollib-sources.jar`：当前固定 build 15 的 `ClientboundLoginPacket` 和 `PlayerSpawnInfo`。
- `plugin/build.gradle.kts:12-34`：当前 MCProtocolLib 版本和共享依赖配置。

## External References

- [MCProtocolLib snapshot metadata](https://repo.opencollab.dev/maven-snapshots/org/geysermc/mcprotocollib/protocol/26.2-SNAPSHOT/maven-metadata.xml)：build 16 的坐标和更新时间。
- [MCProtocolLib master ClientboundLoginPacket](https://raw.githubusercontent.com/GeyserMC/MCProtocolLib/19783c29ece24bc3f07f8ff08628549527e3de20/protocol/src/main/java/org/geysermc/mcprotocollib/protocol/packet/ingame/clientbound/ClientboundLoginPacket.java)：当前上游已包含 `onlineMode`。

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`
- `.trellis/spec/language/java.md`
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/velocity-plugin-interface.md`
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/mcprotocollib-world-packets.md`

## Caveats / Not Found

- 本报告只设计登录状态移交。它不定义后续世界、实体或玩家物理模型。
- `JoinGamePacket` 当前没有公开全部字段 getter。实现可以让 Decoder 直接保留已转换的 `ClientboundLoginPacket`。不要通过一组新 getter 重建同一个 Packet。
- `ServerConnectedEvent` 是原生 API 类。改变构造器会影响直接构造该 Event 的第三方代码。当前任务已经接受 patched Velocity API，因此本报告优先保持运行时职责清晰，没有保留兼容构造器。
- build 16 的 Netty 依赖仍需按项目现有排除规则核对。该问题不改变 Login Packet 字段结论。

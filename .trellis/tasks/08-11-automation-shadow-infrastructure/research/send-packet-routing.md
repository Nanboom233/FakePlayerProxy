# Research: sendPacket 路由语义

- Query: 用 `sendPacket(Packet, boolean bypass)` 取代 `receivePacket(Packet)`，并确定 Shadow 响应的路由
- Scope: internal
- Date: 2026-08-13

## Findings

### 结论

不需要增加 `receivePacket(Packet)`。

保留一个发送入口，并增加一个重载：

```java
void sendPacket(Packet packet)
void sendPacket(Packet packet, boolean bypass)
```

无参重载等同于 `sendPacket(packet, true)`。

两个重载都不发布 Packet Event。

两个重载都是 fire-and-forget 操作。

返回时只保证 Patch 已同步完成转换，并把 Packet 交给选定路径。

该方法不保证远端已经收到 Packet。

该方法也不保证 handler 启动的异步 Event 和后续写入已经完成。

`bypass` 只表示是否绕过 Velocity 当前的前端 `MinecraftSessionHandler`。

它不表示是否绕过 Packet Event。发送路径本来就不经过 Decoder。

### `bypass=true`

Plugin 对目标后端 `MinecraftConnection` 调用该方法。

Patch 按后端连接的当前协议状态和 serverbound 方向编码 MCProtocolLib Packet。

Patch 随后把 Packet 写入该后端 Channel。

该路径不调用前端 handler。

这是默认路径。普通玩家输入和不影响 Velocity 状态的协议响应使用该路径。

### `bypass=false`

Plugin 仍对目标后端 `MinecraftConnection` 调用该方法。

Patch 从后端连接的 `VelocityServerConnection` association 取得同一个 `Player`。

Patch 再取得该 `Player` 的当前前端 `MinecraftSessionHandler`。

Patch 按前端逻辑状态和 serverbound 方向转换 MCProtocolLib Packet。

Patch 直接调用 Packet 对应的 `handle(handler)`。

当返回值为 false 时，Patch 调用同一 handler 的 `handleGeneric(packet)`。

Patch 不调用前端 `MinecraftConnection.channelRead()`。

`channelRead()` 会在前端关闭时返回。见 `MinecraftConnection.java:142-160`。

Patch 也不调用前端 `write()`、`delayedWrite()` 或 `flush()`。

因此，该入口不会检查关闭的前端 Channel，也不会向该 Channel 写 Packet。

当前 handler 负责更新 Velocity 状态，并在需要时写入原后端连接。

该调用必须在后端 event loop 中执行。

Velocity 创建后端 Channel 时复用玩家连接的 event loop。见 `VelocityServerConnection.java:99-110`。

### 配置态的必要配套修改

`bypass=false` 本身不能修复配置态入口。

后端收到 Start Configuration 时，`BackendPlaySessionHandler` 会调用 `switchToConfigState()`。见 `BackendPlaySessionHandler.java:151-158`。

当前 `switchToConfigState()` 在前端 Channel 关闭时直接返回。见 `ConnectedPlayer.java:1355-1372`。

Shadow 分支必须完成以下逻辑操作：

1. 设置前端连接的 `pendingConfigurationSwitch`。
2. 不向关闭的前端发送 Start Configuration。
3. 不等待已注销玩家的前端配置事件。
4. 保留当前 Client Play handler，直到 Configuration Acknowledged 到达。

随后，`sendPacket(ConfigurationAcknowledged, false)` 复用现有 Client Play handler。

该 handler 切换前端和后端到 CONFIGURATION。见 `ClientPlaySessionHandler.java:445-466`。

后端发送 Finish Configuration 后，现有 Config handler 等待前端确认。见 `ConfigSessionHandler.java:235-257`。

`sendPacket(FinishConfiguration, false)` 复用 Client Config handler，并完成该等待。

### Shadow 响应路由表

| MCProtocolLib 响应 | bypass | 原因 |
| --- | --- | --- |
| `ServerboundKeepAlivePacket` | `false` | Velocity 必须删除 `pendingPings`、更新延迟并转发。见 `ConnectedPlayer.java:1324-1347`。 |
| `ServerboundConfigurationAcknowledgedPacket` | `false` | Velocity 必须完成 PLAY 到 CONFIGURATION 的 handler 切换。 |
| `ServerboundFinishConfigurationPacket` | `false` | Velocity 必须完成 CONFIGURATION 到 PLAY 的 handler 切换，并解除后端等待。 |
| `ServerboundSelectKnownPacks` | `false` | Client Config handler 在转发前发布并等待 `PlayerConfigurationEvent`。见 `ClientConfigSessionHandler.java:175-188`。 |
| `ServerboundAcceptTeleportationPacket` | `true` | Velocity 没有该 Packet 的专用状态处理。正常路径只做通用转发。 |
| `ServerboundMovePlayerPosPacket` | `true` | Velocity 没有该 Packet 的专用状态处理。 |
| `ServerboundMovePlayerPosRotPacket` | `true` | Velocity 没有该 Packet 的专用状态处理。 |
| `ServerboundMovePlayerRotPacket` | `true` | Velocity 没有该 Packet 的专用状态处理。 |
| `ServerboundMovePlayerStatusOnlyPacket` | `true` | Velocity 没有该 Packet 的专用状态处理。 |
| `ServerboundChunkBatchReceivedPacket` | `true` | Velocity 没有该 Packet 的专用状态处理。 |
| `ServerboundPlayerLoadedPacket` | `true` | Velocity 没有该 Packet 的专用状态处理。 |
| `ServerboundClientTickEndPacket` | `true` | Velocity 没有该 Packet 的专用状态处理。 |
| `ServerboundPongPacket` | `true` | Velocity 不保存该 PLAY/CONFIGURATION Pong 的状态。 |
| `ServerboundCookieResponsePacket` | `true` | 基础连接维持只需把响应发给后端。 |
| `ServerboundChatAckPacket` | `false` | Velocity 必须更新 `ChatQueue.ChatState`，并自行决定何时发送重写后的 Ack。 |

Cookie Response 可以使用 `false`，但该选择会额外发布 `CookieReceiveEvent`。

现有 Client Play 和 Client Config handler 都会处理该事件。见 `ClientPlaySessionHandler.java:478-497` 和 `ClientConfigSessionHandler.java:191-210`。

Shadow 基础状态机不要求该 Plugin 事件。因此，默认使用 `true`。

Pong 在 Velocity 中对应 `PingIdentifyPacket`。

Client Config handler 只对 `connectionInFlight` 提供专用转发。见 `ClientConfigSessionHandler.java:165-173`。

Shadow 使用已经建立的 `connectedServer`。因此，直接写后端更明确。

### Chat Ack

`ServerboundChatAckPacket` 必须使用 `bypass=false`。

MCProtocolLib Packet 的 `offset` 对应 Velocity `ChatAcknowledgementPacket.offset()`。

Client Play handler 不直接转发收到的 Ack。

它调用 `player.getChatQueue().handleAcknowledgement(offset)`，然后返回 true。见 `ClientPlaySessionHandler.java:469-475`。

`ChatQueue` 把客户端 Ack 数量累计到 `ChatState.delayedAckCount`。见 `ChatQueue.java:103-110`。

只有累计数量超过安全窗口时，`ChatQueue` 才创建一个新的 `ChatAcknowledgementPacket`。

新 Packet 的 offset 是 `ackCountToForward`，不一定等于原 Packet 的 offset。

`ChatQueue` 随后在后端 event loop 中自行写入该 Ack。见 `ChatQueue.java:113-121`。

因此，该响应同时需要更新 Velocity 状态和后端状态。

`bypass=false` 完成这两项工作。现有 handler 会在需要时发送对应 Ack。

Plugin 不能在 handler 返回后再次直接发送原 Ack。

否则，后端会收到重复或过量确认，Velocity 的延迟 Ack 状态也会失配。

Backend Play handler 没有 Chat Ack 的专用处理。

Chat Ack 是 serverbound Packet。其状态 owner 是前端 `ChatQueue`，不是后端 handler。

前端 handler 在 deactivated 时会替换并关闭旧 ChatQueue。见 `ClientPlaySessionHandler.java:189-197`。

Shadow 的 PLAY 到 CONFIGURATION 切换继续复用该行为。

### 配置切换前的 Chat Ack 顺序

Minecraft 26.2 客户端收到 Start Configuration 后，先调用 `sendChatAcknowledgement()`，再发送 Configuration Acknowledged。

固定客户端证据是 `ClientPacketListener.java:939-942` 和 `ClientPacketListener.java:2627-2631`。

但是，Plugin 不能连续调用以下两步：

1. `sendPacket(ChatAck, false)`。
2. `sendPacket(ConfigurationAcknowledged, false)`。

第一步只把 Chat Ack 加入 `ChatQueue.head`。

`ChatQueue` 再使用 `CompletableFuture.runAsync(..., smc.eventLoop())` 安排后端写入。见 `ChatQueue.java:103-121`。

第二步立即切换前端 handler。

该切换调用旧 Client Play handler 的 `deactivated()`，并关闭旧 ChatQueue。见 `MinecraftConnection.java:499-513` 和 `ClientPlaySessionHandler.java:189-197`。

排队的 Chat Ack 写任务随后检查 `closed`，并直接丢弃 Packet。

把两个调用拆成两个普通 event-loop task 也不可靠。

Chat Ack handler 会在第一个 task 内再追加一个写 task。

第二个外部 task 可能已经在该写 task 前进入队列。

直接用 `bypass=true` 发送剩余 Chat Ack 也没有作用。

Minecraft 26.2 服务端在 `switchToConfig()` 中先设置 `waitingForSwitchToConfig=true`，再发送 Start Configuration。

之后，旧 PLAY listener 只接受 Configuration Acknowledged。见 `ServerGamePacketListenerImpl.java:385-394` 和 `ServerGamePacketListenerImpl.java:1760-1765`。

服务端 `Connection.channelRead0()` 对 `shouldHandleMessage(packet)==false` 的 Packet 不调用 handler。

因此，Start Configuration 之后到达的 Chat Ack 会被服务端忽略。

服务端不需要该剩余 Ack 来完成配置切换。

Configuration Acknowledged 会用新的 `ServerConfigurationPacketListenerImpl` 替换旧 PLAY listener。见 `ServerGamePacketListenerImpl.java:2110-2119`。

返回 PLAY 时，`PlayerList.placeNewPlayer()` 创建新的 `ServerGamePacketListenerImpl`。见 `PlayerList.java:157-160`。

新的 PLAY listener 构造自己的 `LastSeenMessagesValidator(20)`。见 `ServerGamePacketListenerImpl.java:271-285`。

这证明旧 PLAY listener 的 LastSeen 状态不会跨越 CONFIGURATION。

Velocity 同样在 Configuration Acknowledged 时关闭旧 ChatQueue，并在返回 PLAY 时创建新的 Client Play handler。

因此，最小正确设计如下：

1. MCProtocolLib 状态机仍可生成该剩余 Chat Ack。
2. Plugin 在 Start Configuration 处理中丢弃该 Chat Ack。
3. Plugin 只发送 `ConfigurationAcknowledged`，并使用 `bypass=false`。
4. Plugin 不等待 ChatQueue，不增加 flush API，也不直接向后端补发 Ack。

普通 PLAY 状态中的 Chat Ack 仍使用 `bypass=false`。

该结论只适用于 Start Configuration 已经到达后的最后一个 Chat Ack。

### API 形状

布尔参数可以满足当前两个稳定路径。

仓库中没有对应的 Velocity 路由枚举或既有 bypass 约定。

枚举会增加一个仅有两个值的新类型，但不会增加行为。

保留用户建议的布尔参数更小。

Javadoc 必须明确说明 `bypass` 绕过的是前端 handler。

调用点应使用具名局部变量，避免裸 `false` 难以阅读：

```java
boolean bypass = false;
backend.sendPacket(packet, bypass);
```

### 返回契约

`ChannelFuture` 不适合作为统一返回值。

`bypass=true` 可以返回后端 Channel 写入的 `ChannelFuture`。

`bypass=false` 没有等价的写入 Future。

例如 Known Packs handler 先等待 `PlayerConfigurationEvent`，再异步写入后端。见 `ClientConfigSessionHandler.java:175-188`。

Cookie Response 也可能先等待 `CookieReceiveEvent`。见 `ClientPlaySessionHandler.java:478-497`。

通用分派入口无法取得这些 handler 内部创建的 Future。

让 `ChannelFuture` 在 `bypass=false` 时只表示本地分派完成，会产生两种完成语义。

`CompletionStage<Void>` 也有相同问题。

若它表示真实处理完成，Patch 必须改造每个异步 handler，并改变 Velocity 的内部 handler 合约。

若它只表示本地分派完成，它不比同步返回提供更多信息。

返回本地处理结果也不合适。

Velocity handler 的 boolean 只表示是否继续调用 `handleGeneric()`。

该值不是发送成功、接受成功或远端写入成功。

因此，最小一致契约是 `void`。

这也符合 MCProtocolLib `Session.send(Packet)` 的 fire-and-forget 形式。

同步转换错误和无效路由可以直接抛出异常。

异步 handler 和 Channel 的失败继续由其现有 owner 处理。

### 最小实现路径

1. 保留 `void sendPacket(Packet)`，默认 `bypass=true`。
2. 增加 `void sendPacket(Packet, boolean bypass)`。
3. `true` 使用后端当前状态编码并写入后端 Channel。
4. `false` 使用前端逻辑状态转换，然后直接分派当前前端 handler。
5. 两条路径都不发布 Packet Event。
6. 调整取消登出后的 Start Configuration 分支，只更新逻辑状态。
7. 不增加 `receivePacket`、新 handler、第二套连接状态或 Shadow 专用 API。

## Files Found

- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/MinecraftConnection.java`：入站分派、关闭检查和出站写入。
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ConnectedPlayer.java`：KeepAlive 状态和配置态入口。
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ClientPlaySessionHandler.java`：KeepAlive、Configuration Acknowledged 和 Cookie Response。
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ClientConfigSessionHandler.java`：Finish Configuration、Known Packs、Pong 和 Cookie Response。
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/BackendPlaySessionHandler.java`：后端 KeepAlive 记录和 Start Configuration。
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/ConfigSessionHandler.java`：后端配置态结束流程。
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/VelocityServerConnection.java`：后端连接与 Player association。
- `build/tmp/mcprotocollib-sources.jar`：MCProtocolLib 26.2 ClientListener 和 Packet 类型。

## External References

- Pinned Velocity commit: `843a47e2a38325309cd66133149fc9a984f76bb8`
- MCProtocolLib: `26.2-20260809.160751-16`
- MCProtocolLib `ClientListener` 自动处理 KeepAlive、配置态进入、配置态结束和 Known Packs。

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`
- `.trellis/spec/language/java.md`
- `.trellis/tasks/08-11-automation-shadow-infrastructure/prd.md`
- `.trellis/tasks/08-11-automation-shadow-infrastructure/design.md`

## Caveats / Not Found

当前设计文件已移除 `receivePacket(Packet)`，并采用本报告的两个 `sendPacket` 重载。

当前固定 Velocity 源码没有 MCProtocolLib Packet 转换层。转换细节仍属于 `0002-automation-extension.patch` 的设计工作。

如果 `bypass=false` 支持任意 Packet，某些前端 handler 可能尝试写入关闭的前端 Channel。

初版应只对路由表中的五个 `false` Packet 使用该路径。

接口实现不能宣称任意 Packet 都适合 `bypass=false`。

# Research: Minecraft 26.2 空闲客户端最低状态

## 当前任务决策

初版不自动处理 Resource Pack、Code of Conduct 或 Respawn。

初版维护生命、死亡、实体、世界和无输入玩家物理。

- Query: 真实前端关闭后，已进入服务器的玩家连接需要哪些状态和响应才能继续存活
- Scope: mixed
- Date: 2026-08-13

## Findings

### 检查范围

- Minecraft 26.2 服务端与客户端类来自 `E:/Gradle/caches/fabric-loom/26.2/minecraft-merged.jar`。
- MCProtocolLib 26.2 Packet 源码来自 `build/tmp/mcprotocollib-sources.jar`。
- 当前项目协议目标见 `plugin/build.gradle.kts`。版本为 `26.2-20260709.110151-15`。

### 最低状态

Plugin 必须为每个 shadow 后端保存当前协议状态。状态是 `COMMON`、`CONFIGURATION` 或 `GAME`。

Plugin 还必须保存以下少量数据：

- 最近一次 `ClientboundKeepAlivePacket` 的 ID。响应必须原样返回该 ID。
- 配置流程是否正在等待已知数据包选择、资源包结果、行为准则确认或配置完成。
- GAME 中最新传送 ID、服务端位置、旋转、落地状态和水平碰撞状态。
- Cookie 键到内容的映射。没有内容时也要返回同一键和空值。
- 是否正在等待 `ServerboundPlayerLoadedPacket`。

不需要完整世界、实体、背包或渲染状态来保持空闲连接。

### 必需响应

#### COMMON

- `ClientboundKeepAlivePacket` 必须回复 `ServerboundKeepAlivePacket`。服务端每 15 秒检查一次。未回复下一次检查时会断开。错误 ID 也会断开。依据：`ServerCommonPacketListenerImpl.keepConnectionAlive()` 和 `handleKeepAlive()`。

#### CONFIGURATION

- `ClientboundSelectKnownPacks` 必须回复 `ServerboundSelectKnownPacks`。否则 `SynchronizeRegistriesTask` 不会结束。配置流程停滞。空列表是安全的最低响应。依据：`SynchronizeRegistriesTask.start()`、`handleResponse()` 和 `ServerConfigurationPacketListenerImpl.handleSelectKnownPacks()`。
- `ClientboundFinishConfigurationPacket` 必须回复 `ServerboundFinishConfigurationPacket`。否则连接不能进入 GAME。依据：`JoinWorldTask.start()` 和 `ServerConfigurationPacketListenerImpl.handleConfigurationFinished()`。

#### GAME

- `ClientboundStartConfigurationPacket` 必须回复 `ServerboundConfigurationAcknowledgedPacket`，并把本地状态切换到 CONFIGURATION。服务端要求 `waitingForSwitchToConfig` 为真。依据：`ClientPacketListener` 的重配置处理和 `ServerGamePacketListenerImpl.handleConfigurationAcknowledged()`。
- `ClientboundPlayerPositionPacket` 必须回复同 ID 的 `ServerboundAcceptTeleportationPacket`。还应立即发送一次对应的 `ServerboundMovePlayerPacket.PosRot`。Vanilla 客户端同时发送这两个包。前者解除服务端 `awaitingPositionFromClient`，后者同步最终位置和旋转。依据：`ClientPacketListener.handleMovePlayer()` 和 `ServerGamePacketListenerImpl.handleAcceptTeleportPacket()`。
- `ClientboundRespawnPacket` 后必须在加载就绪时发送 `ServerboundPlayerLoadedPacket`。服务端把该包用于结束客户端加载门控。依据：`ClientPacketListener.notifyPlayerLoaded()`、`ServerGamePacketListenerImpl.handleAcceptPlayerLoad()`、`hasClientLoaded()`。

### 条件必需响应

- `ClientboundPingPacket` 应回复同 ID 的 `ServerboundPongPacket`。Vanilla 客户端总会回复。原版服务端 `handlePong()` 为空，所以它不是原版超时条件。代理或插件可能依赖该响应。
- `ClientboundCookieRequestPacket` 必须回复同键的 `ServerboundCookieResponsePacket`。COMMON 默认 handler 将无请求的 Cookie 响应视为异常并断开。收到请求后不响应可能使请求方流程停滞。依据：`ClientCommonPacketListenerImpl.handleRequestCookie()` 和 `ServerCommonPacketListenerImpl.handleCookieResponse()`。
- 配置阶段收到 `ClientboundResourcePackPushPacket` 时，必须返回该 ID 的终态 `ServerboundResourcePackPacket`。终态响应才会结束 `ServerResourcePackConfigurationTask`。如果资源包为必需，`DECLINED` 会被服务端主动断开。初版可选择成功语义，但不能完全忽略。依据：`ServerConfigurationPacketListenerImpl.handleResourcePackResponse()` 和 `ServerCommonPacketListenerImpl.handleResourcePackResponse()`。
- 配置阶段收到 `ClientboundCodeOfConductPacket` 时，必须发送 `ServerboundAcceptCodeOfConductPacket`。否则该配置任务不会结束。依据：`ServerCodeOfConductConfigurationTask.start()` 和 `ServerConfigurationPacketListenerImpl.handleAcceptCodeOfConduct()`。
- `ClientboundChunkBatchFinishedPacket` 后应回复 `ServerboundChunkBatchReceivedPacket`。该包更新 `PlayerChunkSender` 的发送速率。缺少响应主要会阻塞或限制后续区块发送，不是连接保活超时。依据：`ClientPacketListener.handleChunkBatchFinished()` 和 `ServerGamePacketListenerImpl.handleChunkBatchReceived()`。
- 玩家死亡后，自动继续游戏需要发送 `ServerboundClientCommandPacket(PERFORM_RESPAWN)`。它不是连接保活要求。若不发送，连接会停留在死亡状态。
- 玩家正在载具中时，服务端可能需要载具移动同步。普通站立空闲不需要周期 `ServerboundMoveVehiclePacket`。

### 可选包

- `ServerboundClientTickEndPacket` 不是保活包。服务端没有缺包计时器。它只在本 tick 没有移动包时把 `ServerPlayer.knownMovement` 设为零，并清除 `receivedMovementThisTick`。依据：`ServerGamePacketListenerImpl.handleClientTickEnd()`。
- 原版客户端每个未暂停 GAME tick 发送 `ServerboundClientTickEndPacket`。这是客户端物理状态边界，不是服务端连接存活条件。依据：`Minecraft.tick()`。
- 周期移动包不是连接存活要求。服务端没有 movement silence 超时。静止客户端只在位置、旋转、落地或碰撞状态变化时发送移动包。依据：`LocalPlayer.sendPosition()` 和 `ServerGamePacketListenerImpl.tick()`。
- 周期发送非零移动会重置 `lastActionTime`。这会绕过服务端 `playerIdleTimeout`，不符合 shadow 表示 AFK 的语义。`ClientTickEnd` 和零移动不会重置该计时器。
- Chat session 不需要周期更新。只有 Plugin 主动发送聊天或签名命令时才需要维护签名链和 last-seen 状态。空闲连接可以不发送聊天包。
- 原版 GAME 和 COMMON 的未知 `CustomPayload` 没有强制响应。原版服务端的 serverbound `handleCustomPayload()` 为空。第三方服务端插件可定义自己的请求响应协议，不能由原版最低状态覆盖。
- `ClientboundPlayerRotationPacket` 只更新客户端旋转。原版客户端不会因此单独发送确认。后续需要发送移动时，应使用已更新旋转。

### 对当前提案的校验

| 提案项目 | 结论 |
| --- | --- |
| KeepAlive | 必需 |
| Ping/Pong | 建议处理，原版服务端不强制 |
| ConfigurationAcknowledged | 进入重配置时必需 |
| SelectKnownPacks | 配置流程必需 |
| FinishConfiguration | 配置流程必需 |
| 传送响应 | 必需，包含 AcceptTeleportation 和一次 PosRot |
| rotation response | 不存在独立确认包，只更新本地旋转 |
| ChunkBatchReceived | 条件必需，防止区块发送停滞 |
| CookieResponse | 收到请求时必需 |
| PlayerLoaded | 初次加载或 Respawn 后条件必需 |
| ClientTickEnd | 可选，不应作为周期保活包 |

当前提案遗漏了两个配置任务响应：资源包终态响应和行为准则确认。它还遗漏了死亡后的可选自动 Respawn。

### 实现边界建议

- 初版只对明确需要响应的 clientbound Packet 建立 handler。
- 不运行固定 20 TPS 的移动或 `ClientTickEnd` 发送器。
- Packet 响应使用 MCProtocolLib 现有类型。上述 Packet 在 `build/tmp/mcprotocollib-sources.jar` 中均有对应类型。
- 第三方 Custom Payload 协议不属于原版最低状态。后续按具体后端需求增加独立 handler。

## Files Found

- `plugin/build.gradle.kts`: MCProtocolLib 26.2 版本和运行依赖。
- `build/tmp/mcprotocollib-sources.jar`: 计划所列 Packet 的 MCProtocolLib 类型。
- `E:/Gradle/caches/fabric-loom/26.2/minecraft-merged.jar`: Minecraft 26.2 官方命名客户端和服务端实现。
- `.trellis/spec/backend/velocity-plugin.md`: 当前 shadow、Plugin 边界和协议版本约束。

## External References

- 未使用网络资料。结论来自工作区缓存中的 Minecraft 26.2 字节码和 MCProtocolLib 源码。

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`
- `.trellis/spec/language/java.md`

## Caveats / Not Found

- 原版服务端结论不能覆盖 Paper、Fabric、Forge 或后端 Plugin 自定义的 Custom Payload 协议。
- `playerIdleTimeout` 是服务器配置。shadow 若代表 AFK，不应伪造活动来绕过该配置。
- 本研究只判断连接存活和协议推进。它不保证 gameplay automation 的世界状态正确。

## 补充：签名聊天确认

### 结论

不发送聊天的 shadow 客户端仍需确认收到的签名玩家聊天。

该确认没有时间限制。服务端不会因几秒或几分钟没有确认而断开。服务端也不会等待确认后才发送下一条消息。

服务端会保存每个尚未确认的签名。保存数量超过 `4096` 时，服务端以 `multiplayer.disconnect.too_many_pending_chats` 断开客户端。

Validator 初始包含 `20` 个窗口槽位。第 `4077` 个连续未确认且不同的签名会把列表长度增加到 `4097`，并触发断开。

因此，低聊天量服务器可能长时间不触发问题。高聊天量服务器最终一定会触发问题。

### 服务端路径

- `ServerGamePacketListenerImpl` 用 `LastSeenMessagesValidator(20)` 保存每个连接的确认窗口。
- `sendPlayerChatMessage()` 发送 `ClientboundPlayerChatPacket`。
- Packet 有非空签名时，该方法调用 `LastSeenMessagesValidator.addPending()`。
- 该方法随后读取 `trackedMessagesCount()`。数量大于 `4096` 时断开。
- `handleChatAck()` 处理 `ServerboundChatAckPacket`。它把 Packet 的 `offset` 交给 `LastSeenMessagesValidator.applyOffset()`。
- `applyOffset()` 从队首删除已处理条目。合法 offset 范围是 `0..trackedMessagesCount-20`。
- 负 offset 或超出范围会抛出 `ValidationException`。`handleChatAck()` 随后以 `multiplayer.disconnect.chat_validation_failed` 断开。
- `ServerboundChatPacket` 和 `ServerboundChatCommandSignedPacket` 也携带 `LastSeenMessages.Update`。`unpackAndApplyLastSeen()` 会应用其中的 offset 和确认位。
- Update 的 offset、位图长度、确认内容或 checksum 无效时，服务端同样断开。

该路径没有计时器。它只使用累计条目数和每次确认的结构校验。

### Vanilla 客户端路径

- `ClientPacketListener.markMessageAsProcessed()` 把不同的签名交给 `LastSeenMessagesTracker.addPending()`。
- Tracker 的 offset 大于 `64` 时，客户端调用 `sendChatAcknowledgement()`。
- `sendChatAcknowledgement()` 发送 `ServerboundChatAckPacket(offset)`，并把本地 offset 清零。
- 客户端进入 CONFIGURATION 前也会调用 `sendChatAcknowledgement()`。
- Tracker 和 Validator 都忽略与上一条相同的连续签名。Plugin 计数时也必须执行相同去重。

Vanilla 的独立确认批次通常包含 `65` 个不同签名。Plugin 可以沿用这个阈值。它不需要等待 4096 条。

### 哪些消息计入

- 有非空签名的 `ClientboundPlayerChatPacket` 计入。
- 无签名的玩家聊天不计入。服务端发送后会在 `sendPlayerChatMessage()` 提前返回。
- `ClientboundSystemChatPacket` 不计入。它没有玩家签名，也不经过 `sendPlayerChatMessage()` 的 `addPending()`。
- `ClientboundDisguisedChatPacket` 不计入。`sendDisguisedChatMessage()` 只发送 Packet。
- 客户端隐藏、屏蔽或完全过滤签名玩家聊天时，仍会推进 offset。确认位可以表示该消息没有显示。

独立的 `ServerboundChatAckPacket` 只有 offset。它表示客户端已处理这些条目，不声明这些消息被显示。

### 对 shadow 最低状态的影响

`ServerboundChatAckPacket` 应加入 GAME 的条件必需响应。

最低实现只需保存：

- 当前未确认的不同签名数量。
- 上一个签名，用于连续去重。

每收到 `65` 个不同的有签名 `ClientboundPlayerChatPacket`，发送一次 `ServerboundChatAckPacket(65)`。

Vanilla 在进入 CONFIGURATION 前尝试发送剩余 offset。

Shadow 不发送这个剩余 Ack。

Start Configuration 之后，26.2 服务端只处理 Configuration Acknowledged。

返回 GAME 时，服务端创建新的 `LastSeenMessagesValidator`。

若 Plugin 以后需要发送聊天或带可签名参数的命令，还需保存完整的 20 条 last-seen 窗口、确认位、checksum 和签名链。本次只保持空闲连接，不需要这套完整状态。

MCProtocolLib 已提供：

- `ClientboundPlayerChatPacket`
- `ClientboundSystemChatPacket`
- `ClientboundDisguisedChatPacket`
- `ServerboundChatAckPacket`

### 证据位置

- `net.minecraft.server.network.ServerGamePacketListenerImpl.<init>()`
- `net.minecraft.server.network.ServerGamePacketListenerImpl.sendPlayerChatMessage()`
- `net.minecraft.server.network.ServerGamePacketListenerImpl.handleChatAck()`
- `net.minecraft.server.network.ServerGamePacketListenerImpl.unpackAndApplyLastSeen()`
- `net.minecraft.network.chat.LastSeenMessagesValidator.addPending()`
- `net.minecraft.network.chat.LastSeenMessagesValidator.applyOffset()`
- `net.minecraft.network.chat.LastSeenMessagesValidator.applyUpdate()`
- `net.minecraft.client.multiplayer.ClientPacketListener.markMessageAsProcessed()`
- `net.minecraft.client.multiplayer.ClientPacketListener.sendChatAcknowledgement()`
- `net.minecraft.client.multiplayer.ClientPacketListener.handleConfigurationStart()`
- `net.minecraft.network.chat.LastSeenMessagesTracker.addPending()`

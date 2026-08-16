# Research: 旧协议客户端的跨包状态模型

- Query: 旧版 MCProtocolLib 和长期运行 Bot 维护了哪些跨包状态，这些状态如何转换，哪些概念仍适用于 Minecraft 26.2
- Scope: mixed
- Date: 2026-08-13

## Findings

### 调查对象

本研究保留旧协议工具作为状态机参考。协议版本过旧只限制 Packet 名称和阶段完整性，不会抹去跨包状态的设计价值。

主要来源如下：

- [MCProtocolLib 1.12.2 `ClientListener`](https://github.com/GeyserMC/MCProtocolLib/blob/1.12.2-2/src/main/java/com/github/steveice10/mc/protocol/ClientListener.java)：登录、加密、压缩、协议阶段和 KeepAlive。
- [McFisherBot `SessionListener`](https://github.com/TurboKoT1/McFisherBot/blob/4eaba93ecc02a5573c7038defe2e19968156f1ac/src/main/java/me/turbokot/fisherbot/bot/listeners/SessionListener.java)：Join Game、位置和 automation readiness。
- [McFisherBot `Living`](https://github.com/TurboKoT1/McFisherBot/blob/4eaba93ecc02a5573c7038defe2e19968156f1ac/src/main/java/me/turbokot/fisherbot/bot/listeners/Living.java)：生命值到 Respawn 的转换。
- [McFisherBot `EntityListener`](https://github.com/TurboKoT1/McFisherBot/blob/4eaba93ecc02a5573c7038defe2e19968156f1ac/src/main/java/me/turbokot/fisherbot/bot/listeners/EntityListener.java)：玩家实体、鱼钩实体、时间和背包联合状态。
- [McFisherBot `InventoryListener`](https://github.com/TurboKoT1/McFisherBot/blob/4eaba93ecc02a5573c7038defe2e19968156f1ac/src/main/java/me/turbokot/fisherbot/bot/listeners/InventoryListener.java)：窗口和槽位增量状态。
- [MinecraftBOT `MCBOTSessionAdapter`](https://github.com/alwyn974/MinecraftBOT/blob/6828d2626aead969a38ebd1e0427b1c4e8b20a92/src/main/java/re/alwyn974/minecraft/bot/entity/MCBOTSessionAdapter.java)：位置、生命、玩家列表和重连。
- [MCFishingBot `MinecraftClient`](https://github.com/MiraCrypto/MCFishingBot/blob/90fbc78de267ed2b2890c7ca1ae2271b87e51c3d/src/main/java/io/github/miracrypto/client/MinecraftClient.kt)：实体 ID、生命、饥饿、经验和安全退出。
- [MCProtocolLib 26.2 `ClientListener`](https://github.com/GeyserMC/MCProtocolLib/blob/19783c29ece24bc3f07f8ff08628549527e3de20/protocol/src/main/java/org/geysermc/mcprotocollib/protocol/ClientListener.java)：现代协议阶段和基础自动响应。

### 旧版 MCProtocolLib 的连接状态机

MCProtocolLib 1.12.2 维护的核心状态不是一个 Packet 列表。它是按 `SubProtocol` 选择 decoder、encoder 和响应逻辑的状态机。

| 当前状态 | 入站 Packet | 状态修改 | 出站 Packet 或结果 |
| --- | --- | --- | --- |
| 初始 LOGIN | `ConnectedEvent` | 临时切换 HANDSHAKE，再切换 LOGIN | `HandshakePacket(LOGIN)`，随后 `LoginStartPacket` |
| LOGIN | `EncryptionRequestPacket` | 生成 AES key，完成会话认证，启用加密 | `EncryptionResponsePacket` |
| LOGIN | `LoginSetCompressionPacket` | 保存 compression threshold | 无 |
| LOGIN | `LoginSuccessPacket` | 保存 `GameProfile`，状态切换 GAME | 无 |
| GAME | `ServerKeepAlivePacket(id)` | 不需要长期保存 ID | 立即返回同 ID 的 `ClientKeepAlivePacket` |
| GAME | `ServerSetCompressionPacket` | 更新 compression threshold | 无 |
| LOGIN 或 GAME | Disconnect Packet | Session 进入关闭状态 | 断开并保存原因或异常 |

证据见 `ClientListener.java:39-78`、`ClientListener.java:97-104` 和 `ClientListener.java:108-122`。

这些旧连接状态在 Shadow 中不需要重建。真实客户端已经完成登录、加密和压缩。Velocity 保留的后端连接继续拥有 cipher、compression 和协议 codec。

旧模型仍证明两个原则：

1. Packet 必须按当前协议状态解释。Packet 类型不能脱离连接阶段。
2. 状态修改必须和响应顺序绑定。不能只保存一个字段，再从任意线程补发响应。

Minecraft 26.2 把单一 GAME 前流程扩展为 LOGIN、CONFIGURATION 和 GAME。现代 `ClientListener` 仍使用同一模型，并增加独立入站和出站状态。

| 入站 Packet | 现代状态转换 | 出站 Packet | 旧概念 |
| --- | --- | --- | --- |
| `ClientboundLoginFinishedPacket` | inbound LOGIN -> CONFIGURATION | `ServerboundLoginAcknowledgedPacket`，再切 outbound | Login Success 后改变 codec 状态 |
| `ClientboundStartConfigurationPacket` | inbound GAME -> CONFIGURATION | `ServerboundConfigurationAcknowledgedPacket`，再切 outbound | 服务端驱动协议阶段变化 |
| `ClientboundFinishConfigurationPacket` | inbound CONFIGURATION -> GAME | `ServerboundFinishConfigurationPacket`，再切 outbound | 服务端驱动协议阶段变化 |
| `ClientboundKeepAlivePacket` | 状态不变 | 同 ID KeepAlive | 旧版 KeepAlive 请求和响应 |

因此，旧版 `SubProtocol` 概念仍有效。26.2 的实现必须换成独立 inbound 和 outbound `ProtocolState`，并增加 CONFIGURATION 转换。

### 长期运行 Bot 的玩家状态机

旧 Bot 把玩家状态放在 Session listener 之外。多个 listener 通过同一个 Bot 或 Player 对象共享状态。

这与 Shadow 的每玩家 `AutomationService` 模型一致。它也说明只创建无状态 Packet handler 不够。

#### 进入可操作状态

McFisherBot 的转换如下：

1. 收到 `ServerJoinGamePacket`。
2. 保存玩家 `entityId`。
3. 把 `connected` 设为 true。
4. 发送 Client Settings。
5. 发送 `minecraft:brand`。
6. 后续业务 listener 才能用 `entityId` 识别玩家拥有的实体。

证据见 `SessionListener.java:31-56`。

这不是单纯的连接存活。它是 automation readiness。

Shadow 复用已经进入 GAME 的连接。它不应重复 Client Information 或 brand。它应继承在线阶段已经观察到的 readiness 状态。

26.2 增加 `ServerboundPlayerLoadedPacket`。该 Packet 是现代 readiness 门控。它对应旧工具的“Join Game 后可操作”概念，但条件更明确。

#### 位置状态

McFisherBot 收到 Spawn Position 或服务端位置修正后，覆盖保存位置。见 `SessionListener.java:57-62`。

MinecraftBOT 收到 `ClientboundPlayerPositionPacket` 后，创建或覆盖位置。见 `MCBOTSessionAdapter.java:95-101`。

旧工具中存在一个不可靠实现。MinecraftBOT 把其他实体的相对移动包也应用到 Bot 自身位置。见 `MCBOTSessionAdapter.java:103-108`。该逻辑不能照搬。

有效概念如下：

- 玩家绝对位置跨 Packet 保存。
- 服务端位置修正覆盖本地位置。
- 相对坐标必须只应用于明确匹配的实体。
- 自动操作发送 Packet 时使用最新位置。

26.2 仍需要保存位置、旋转和服务端 teleport ID。收到 `ClientboundPlayerPositionPacket` 后，状态机先合并相对分量，再发送 teleport 确认和最终 PosRot。

#### 生命和死亡状态

McFisherBot 的转换如下：

1. 收到 `ServerPlayerHealthPacket`。
2. 保存 health。
3. tick 读取已保存 health。
4. health 小于或等于零时发送 Respawn。

证据见 `Living.java:20-35`。

MinecraftBOT 使用直接转换。收到 `ClientboundSetHealthPacket` 后保存 health 和 food。health 小于或等于零时立即发送 Respawn。见 `MCBOTSessionAdapter.java:110-118`。

MCFishingBot 保存 health、food、saturation。低生命值时主动断开。见 `MinecraftClient.kt:81-94`。

这些实现说明死亡处理是策略，不是连接保活：

- AFK 工具可以停留在死亡界面。
- 常驻 Bot 可以自动 Respawn。
- 安全型 Bot 可以主动退出。

26.2 保留该概念。若初版 Shadow 只保证连接继续，则不需要 health。若需要死亡后继续 automation，则必须加入 `ALIVE`、`DEAD`、`RESPAWN_PENDING` 和 `PLAYER_LOADED_PENDING` 转换。

#### 实体状态

McFisherBot 的钓鱼状态机保存以下跨包数据：

- 本玩家 `entityId`
- 鱼钩 `entityId`
- 最近抛竿时间
- 鱼钩位置变化

转换如下：

1. 收到鱼钩 Spawn Object。
2. 检查 owner ID 是否等于本玩家 entity ID。
3. 保存鱼钩 ID 和抛竿时间。
4. 收到该鱼钩的移动包。
5. 检查鱼钩 ID、时间和垂直速度。
6. 条件满足后收杆并执行背包动作。

证据见 `EntityListener.java:38-63`。

该模型不属于 Shadow 基础连接状态。它属于具体 automation 的实体模型。

它仍是重要的后续参考。实现 attack、use、钓鱼或实体目标时，必须按 entity ID 关联 Spawn、Move、Metadata 和 Remove Packet。

#### 背包和窗口状态

McFisherBot 保存 `currentWindowId` 和 `slot -> ItemStack`。

转换如下：

1. `ServerWindowItemsPacket` 建立完整槽位快照。
2. `ServerSetSlotPacket` 增量更新一个槽位，并更新 window ID。
3. 自动出售或丢弃读取当前快照。
4. 操作结束后工具清空本地槽位，等待后续服务端 Packet 重建。

证据见 `InventoryListener.java:23-47` 和 `EntityListener.java:65-109`。

26.2 仍需要窗口 ID、state ID、槽位和 carried item 的关联状态。旧工具没有现代 container state ID，因此它的点击 Packet 构造不能照搬。

背包状态不属于 Shadow 基础连接状态。它属于 drop、swap、use 和 container automation readiness。

#### 玩家列表、难度和经验

MinecraftBOT 保存 difficulty 和在线玩家列表。见 `MCBOTSessionAdapter.java:121-132`。

MCFishingBot 保存 entity ID、health、food、saturation、experience、level 和 total experience。见 `MinecraftClient.kt:76-105` 及其 `Player.kt`。

这些字段用于显示、安全策略或具体行为。它们不参与连接正确性。

初版不应因为旧工具维护了这些字段，就把它们加入 Shadow 基础状态。

### 恢复状态机

旧工具通常把恢复定义为重新建连：

1. 收到 `DisconnectedEvent`。
2. 判断断开原因和 auto reconnect 配置。
3. 等待固定或随机延迟。
4. 新建 Session 并重新登录。
5. 新 Session 从空状态重新收集玩家状态。

MinecraftBOT 的实现见 `MCBOTSessionAdapter.java:51-63`。MCFishingBot 也使用断开原因决定是否重连。

Shadow 已决定不重连旧 Session。它保留原后端，之后由玩家 Fresh Login 创建新连接。

因此，旧工具的重连机制不能照搬。但以下恢复原则仍有效：

- 状态归属连接实例。
- 新连接不能复用旧连接的协议状态。
- 恢复必须清空 pending response、teleport、loading、entity 和 container 状态。
- 旧连接回调不能删除新连接注册。

### 四类状态的边界

| 类别 | 旧工具维护内容 | 26.2 Shadow 初版结论 |
| --- | --- | --- |
| 连接正确性 | 协议阶段、加密、压缩、KeepAlive、关闭原因 | 保留阶段与响应顺序。加密和压缩继续由现有 Velocity 后端连接持有。 |
| 玩家正确性 | 位置、旋转、teleport、生命、加载状态 | 保留位置、旋转、teleport 和 PlayerLoaded。生命仅在批准自动 Respawn 后加入。 |
| Automation readiness | entity ID、目标实体、窗口、槽位、经验、玩家列表 | 基础 Shadow 不加入。具体 action 按需求增加独立状态。 |
| 恢复 | 断线分类、延迟、重新登录、状态重建 | 不重连旧后端。Fresh Login 创建新状态。旧实例完成时使用条件删除。 |

### 对当前 Shadow 状态机的修正

旧工具研究支持保留以下初版状态：

- MCProtocolLib inbound 和 outbound `ProtocolState`
- 最新玩家位置、旋转、onGround 和 horizontalCollision
- 最新 teleport ID 和 teleport response pending
- PlayerLoaded pending
- Cookie map
- response queue 和 drain scheduled 标志
- Shadow 是否启用

旧工具研究不支持把以下内容直接加入初版：

- health、food 和 automatic Respawn
- entity tracker
- inventory tracker
- player list
- experience
- auto reconnect
- 周期移动或 ClientTickEnd

但是，这些项目不能从设计中删除。它们应记录为后续 automation action 的状态依赖。

### Packet 到状态的最小转换

| 事件 | 旧状态 | 新状态 | Shadow 时的动作 |
| --- | --- | --- | --- |
| 在线 C2S Move Player | 任意 GAME 位置 | 合并真实客户端最终位置 | 无 |
| S2C Player Position | GAME | 合并位置和旋转，保存 teleport ID，标记 teleport pending | Accept Teleportation，再发送 PosRot |
| C2S Accept Teleportation | teleport pending | 清除匹配 ID 的 pending | 在线观察，不生成响应 |
| S2C Respawn | GAME ready | 清空旧加载状态，标记 PlayerLoaded pending | 等待后续位置同步 |
| C2S PlayerLoaded | PlayerLoaded pending | ready | 在线观察，不生成响应 |
| Shadow 中完成 Respawn 位置同步 | PlayerLoaded pending | ready | 发送 PlayerLoaded |
| S2C Start Configuration | GAME | inbound 进入 CONFIGURATION，outbound 等 ACK | 发送 Configuration Acknowledged |
| S2C Select Known Packs | CONFIGURATION | 等待结果 | 发送选定列表 |
| S2C Finish Configuration | CONFIGURATION | inbound 进入 GAME，outbound 等确认 | 发送 Finish Configuration |
| S2C Store Cookie | 任意 common 状态 | 更新 key 到 payload | 无 |
| S2C Cookie Request | 任意 common 状态 | 状态不变 | 返回该 key 的值或空值 |
| 周期 tick 发现后端关闭 | 任意 | CLOSED | 清空 pending 状态并条件删除 AutomationService |

## Files Found

- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/mcprotocollib-afk-tools.md`：已有工具清单和保活结论。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/vanilla-idle-state.md`：Minecraft 26.2 原版客户端和服务端要求。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/shadow-player-state-machine.md`：当前 Shadow 状态机草案。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/send-packet-routing.md`：当前 Packet 发送路由设计。
- `.trellis/spec/backend/velocity-plugin.md`：Patch、Plugin、Automation 和 Shadow 边界。
- `.trellis/spec/language/java.md`：Java 代码约束。

## External References

- MCProtocolLib 1.12.2 tag `1.12.2-2`。
- MCProtocolLib 26.2 reference commit `19783c29ece24bc3f07f8ff08628549527e3de20`。
- McFisherBot commit `4eaba93ecc02a5573c7038defe2e19968156f1ac`。
- MinecraftBOT commit `6828d2626aead969a38ebd1e0427b1c4e8b20a92`。
- MCFishingBot commit `90fbc78de267ed2b2890c7ca1ae2271b87e51c3d`。

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`
- `.trellis/spec/language/java.md`

## Caveats / Not Found

- 旧工具不是 Minecraft 26.2 的完整 Packet 依据。新 Packet 和新阶段必须以 26.2 客户端、服务端和固定 MCProtocolLib 版本为准。
- McFisherBot 的 `KeepAlivePingListener` 使用了看似反向的 Packet 类型。它可能是无效或重复实现。保活结论应以 MCProtocolLib `ClientListener` 为准。
- MinecraftBOT 把普通实体相对移动应用到 Bot 位置。该实现缺少 entity ID 检查，不能照搬。
- 旧工具多数缺少 teleport ID 确认、container state ID、CONFIGURATION、Chunk Batch、Cookie 和 PlayerLoaded。
- 本研究区分基础状态和业务状态。它不主张删除业务状态，只限制本次 Shadow 基础设施的初版范围。

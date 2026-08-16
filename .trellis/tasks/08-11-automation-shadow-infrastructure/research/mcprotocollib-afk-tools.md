# Research: MCProtocolLib AFK 工具的连接状态维护

- Query: 调查使用 MCProtocolLib 的 AFK、无头客户端和常驻 Bot，核对 Shadow 后半段需要维护的协议状态。
- Scope: mixed
- Date: 2026-08-13

## Findings

### 调查结论

没有找到面向 Minecraft 26.2，并且专门实现“真实客户端退出后继续保留原 GAME 连接”的开源 MCProtocolLib 工具。

找到的真实 MCProtocolLib 常驻工具如下：

| 项目 | MCProtocolLib 版本 | 可验证的常驻逻辑 | 证据限制 |
| --- | --- | --- | --- |
| [TurboKoT1/McFisherBot](https://github.com/TurboKoT1/McFisherBot/tree/4eaba93ecc02a5573c7038defe2e19968156f1ac) | `1.12.2-2` | 回复 KeepAlive，发送 Client Settings 和 `minecraft:brand`，保存位置和生命值，死亡后发送 Respawn | 协议过旧，没有 CONFIGURATION、Chunk Batch、PlayerLoaded 或 ClientTickEnd |
| [MiraCrypto/MCFishingBot](https://github.com/MiraCrypto/MCFishingBot/tree/90fbc78de267ed2b2890c7ca1ae2271b87e51c3d) | 1.19.3 commit `1ff7bdf` | 保存实体 ID、生命值、饥饿值、经验和背包状态；低生命值主动退出；异常断线后延迟 15 到 60 秒重连 | 依赖 MCProtocolLib 内置保活；没有 1.20.2 之后的 CONFIGURATION 流程 |
| [alwyn974/MinecraftBOT](https://github.com/alwyn974/MinecraftBOT/tree/6828d2626aead969a38ebd1e0427b1c4e8b20a92) | `1.18.2-1` | 保存位置、生命值、饥饿值和在线玩家；死亡后发送 Respawn；断线后按配置重连 | 通用 Bot，不是 AFK 专用工具；协议过旧 |
| [chipmunkmc/chipmunkbot](https://github.com/chipmunkmc/chipmunkbot/tree/dbf3c23b21a9d2ad2739f4a44ed5cb22bd3d5802) | 旧 `com.github.steveice10` 版本 | 使用普通 `TcpClientSession` 常驻，并按插件需要跟踪玩家列表 | 没有额外的基础保活实现，主要依赖 MCProtocolLib |

这些项目不能证明 26.2 的完整 Packet 集。它们只能证明旧版 Bot 通常依赖 MCProtocolLib 保活，并按业务需要补充玩家状态。

### TurboKoT1/McFisherBot 完整状态

该项目同时运行多个 Bot。`Main` 为每个 Bot 安排一个 50 毫秒周期任务。参见 [`Main.java:27-44`](https://github.com/TurboKoT1/McFisherBot/blob/4eaba93ecc02a5573c7038defe2e19968156f1ac/src/main/java/me/turbokot/fisherbot/Main.java#L27-L44)。

| 分类 | 保存状态 | 入站来源 | 用途 | MCProtocolLib 内置 |
| --- | --- | --- | --- | --- |
| 连接协议 | host、port、`MinecraftProtocol`、Session、connected、3 秒连接超时 | Join Game 设置 connected | 建立会话并控制周期任务 | Session 和协议对象由库提供；connected 是工具状态 |
| 玩家身份 | username 对应的 protocol、玩家 entity ID | `ServerJoinGamePacket` | 识别自己生成的鱼钩 | entity ID 跟踪不是库内置 |
| 位置运动 | `Vector3D pos` | Spawn Position、Player Position Rotation | 为丢弃物品动作提供方块位置 | 不是库内置 |
| 生命死亡 | health、`sleepticks=50` | Player Health | 50 tick 延迟后持续请求 Respawn | 不是库内置 |
| 饥饿经验 | 无 | 无 | 无 | 无 |
| 背包窗口 | current window ID、slot 到 ItemStack 的 Map | Set Slot、Window Items | 选择热栏物品、出售、点击窗口、丢弃物品 | 不是库内置 |
| 实体世界 | fish hook entity ID、last cast time | Spawn Object、Entity Position Rotation | 识别自己的浮标，并以运动量判断咬钩 | 不是库内置 |
| 玩家列表 | 无 | 无 | 无 | 无 |
| 聊天插件消息 | 不保存聊天；监听聊天关键字 | Server Chat | 收到 `startfish` 后使用物品；Join 后发送 Client Settings 和 brand | 不是库内置 |
| 任务专用状态 | Living、EntityListener、InventoryListener；fishHookId、lastCastTime | 上述 Packet | 完整钓鱼、出售和丢弃流程 | 不是库内置 |

`Bot` 保存连接、entity ID、位置和生命值，并组合三个业务 listener。参见 [`Bot.java:18-67`](https://github.com/TurboKoT1/McFisherBot/blob/4eaba93ecc02a5573c7038defe2e19968156f1ac/src/main/java/me/turbokot/fisherbot/bot/Bot.java#L18-L67)。

`InventoryListener` 保存当前窗口和全部 slot。Set Slot 做增量更新。Window Items 做全量更新。参见 [`InventoryListener.java:13-52`](https://github.com/TurboKoT1/McFisherBot/blob/4eaba93ecc02a5573c7038defe2e19968156f1ac/src/main/java/me/turbokot/fisherbot/bot/listeners/InventoryListener.java#L13-L52)。

`EntityListener` 保存鱼钩 ID 和抛竿时间。它读取浮标位移，并发送 Use Item、Swing、Held Item、Chat、Window Action 和 Drop Item Stack。参见 [`EntityListener.java:28-112`](https://github.com/TurboKoT1/McFisherBot/blob/4eaba93ecc02a5573c7038defe2e19968156f1ac/src/main/java/me/turbokot/fisherbot/bot/listeners/EntityListener.java#L28-L112)。

`Living.tick()` 在前 50 次 tick 不处理死亡。之后只要 health 小于等于零，每个 tick 都发送 Respawn。该实现没有一次性保护。参见 [`Living.java:11-36`](https://github.com/TurboKoT1/McFisherBot/blob/4eaba93ecc02a5573c7038defe2e19968156f1ac/src/main/java/me/turbokot/fisherbot/bot/listeners/Living.java#L11-L36)。

该工具显式增加 `KeepAlivePingListener`。它在 GAME 状态回复 KeepAlive，并包含一个状态 Ping/Pong 分支。参见 [`KeepAlivePingListener.java:12-22`](https://github.com/TurboKoT1/McFisherBot/blob/4eaba93ecc02a5573c7038defe2e19968156f1ac/src/main/java/me/turbokot/fisherbot/bot/listeners/KeepAlivePingListener.java#L12-L22)。因此该版本的保活不能归因于工具未展示的库行为。

断线策略仅打印服务端原因。它不重连，也不清理业务状态。参见 [`SessionListener.java:72-75`](https://github.com/TurboKoT1/McFisherBot/blob/4eaba93ecc02a5573c7038defe2e19968156f1ac/src/main/java/me/turbokot/fisherbot/bot/listeners/SessionListener.java#L72-L75)。

### MiraCrypto/MCFishingBot 完整状态

该项目把每个账户的会话状态与每次连接的玩家状态分开。`MinecraftState` 保存 config、当前 Session 和重连任务。参见 [`MinecraftState.kt:7-27`](https://github.com/MiraCrypto/MCFishingBot/blob/90fbc78de267ed2b2890c7ca1ae2271b87e51c3d/src/main/java/io/github/miracrypto/bot/MinecraftState.kt#L7-L27)。

| 分类 | 保存状态 | 入站来源 | 用途 | MCProtocolLib 内置 |
| --- | --- | --- | --- | --- |
| 连接协议 | Session、连接状态、重连 Future、账户到 SessionService 的映射 | Connected、Disconnected | 多账户连接、异常断线重连、状态显示 | Session 由库提供；重连调度是工具逻辑 |
| 玩家身份 | entity ID | Login Packet | 匹配自己的 fishing bobber | 不是库内置 |
| 位置运动 | 无 | 无 | 钓鱼不需要玩家位置 | 无 |
| 生命死亡 | health、战斗死亡消息 | Set Health、Combat Kill | 低生命值退出、Discord 死亡通知、人工 Respawn | 不是库内置 |
| 饥饿经验 | food、saturation、experience、level、totalExperience | Set Health、Set Experience | 安全退出和状态面板 | 不是库内置 |
| 背包窗口 | dispatcher 支持 Container Set Slot 和 Set Content，但没有 listener 覆盖或持久字段 | Container Packet 被类型分发后丢弃 | 预留扩展点，没有实际状态 | 不是库内置，也未实现 |
| 实体世界 | fish hook ID、候选掉落物 ID、loot ItemStack 列表、seen entity UUID 集合 | Add Entity、Set Entity Data | 判断咬钩、记录掉落、稀有实体通知 | 不是库内置 |
| 玩家列表 | 无 | 无 | 无 | 无 |
| 聊天插件消息 | chat buffer、500 毫秒合并 Future、Discord channel 和 allowed users | System Chat、Player Chat | 批量桥接聊天；发送聊天、命令和 Respawn | 不是库内置 |
| 任务专用状态 | fishCaught 时间列表、loot、fishHookId、fishItemId、Discord 状态消息、10 秒空闲检查 Future | Login、Add Entity、Entity Data | 自动抛竿、收杆、收益统计、钓鱼卡死检测 | 不是库内置 |

`Player` 只保存 health、food、saturation、experience、level、totalExperience 和 entityId。参见 [`Player.kt:3-9`](https://github.com/MiraCrypto/MCFishingBot/blob/90fbc78de267ed2b2890c7ca1ae2271b87e51c3d/src/main/java/io/github/miracrypto/client/Player.kt#L3-L9)。

`MinecraftClient` 保存 listener 集合、Session、聊天缓冲和合并任务。它更新玩家状态，并发送 Use Item、Swing、Respawn、Chat 和 Command。参见 [`MinecraftClient.kt:33-159`](https://github.com/MiraCrypto/MCFishingBot/blob/90fbc78de267ed2b2890c7ca1ae2271b87e51c3d/src/main/java/io/github/miracrypto/client/MinecraftClient.kt#L33-L159)。

`FishingBot` 保存浮标、掉落物、捕获时间和 loot。它按实体 metadata 驱动收杆，并每 10 秒检查钓鱼空闲超时。参见 [`FishingBot.kt:27-166`](https://github.com/MiraCrypto/MCFishingBot/blob/90fbc78de267ed2b2890c7ca1ae2271b87e51c3d/src/main/java/io/github/miracrypto/bot/modules/FishingBot.kt#L27-L166)。

`MobNotify` 保存已经通知过的实体 UUID，防止重复通知。参见 [`MobNotify.kt:13-29`](https://github.com/MiraCrypto/MCFishingBot/blob/90fbc78de267ed2b2890c7ca1ae2271b87e51c3d/src/main/java/io/github/miracrypto/bot/modules/MobNotify.kt#L13-L29)。

该工具没有显式 KeepAlive listener。它使用普通 `TcpClientSession` 和 `MinecraftProtocol`。因此它依赖该 MCProtocolLib 版本安装的默认 ClientListener。源码只证明工具没有覆盖保活，不能证明旧库的精确实现。

断线时，`NotReconnectableException` 阻止重连。其他断线随机延迟 15 到 60 秒，并创建全新 Session。参见 [`Main.kt:71-89`](https://github.com/MiraCrypto/MCFishingBot/blob/90fbc78de267ed2b2890c7ca1ae2271b87e51c3d/src/main/java/io/github/miracrypto/bot/Main.kt#L71-L89)。连接关闭时还取消钓鱼检查任务和 Discord listener。

账户层保存认证服务、SessionService 和最后登录时间。超过 12 小时后刷新认证。参见 [`AccountManager.kt:16-29`](https://github.com/MiraCrypto/MCFishingBot/blob/90fbc78de267ed2b2890c7ca1ae2271b87e51c3d/src/main/java/io/github/miracrypto/AccountManager.kt#L16-L29) 和 [`AccountManager.kt:72-90`](https://github.com/MiraCrypto/MCFishingBot/blob/90fbc78de267ed2b2890c7ca1ae2271b87e51c3d/src/main/java/io/github/miracrypto/AccountManager.kt#L72-L90)。

### alwyn974/MinecraftBOT 完整状态

该项目把连接配置和运行状态保存在同一个 `EntityBOT`。参见 [`EntityBOT.java:37-56`](https://github.com/alwyn974/MinecraftBOT/blob/6828d2626aead969a38ebd1e0427b1c4e8b20a92/src/main/java/re/alwyn974/minecraft/bot/entity/EntityBOT.java#L37-L56)。

| 分类 | 保存状态 | 入站来源 | 用途 | MCProtocolLib 内置 |
| --- | --- | --- | --- | --- |
| 连接协议 | host、port、proxy、premium、debug、TcpClientSession | Connected、Disconnected | 建立离线或 Microsoft 认证会话 | Session 和协议由库提供；配置是工具状态 |
| 玩家身份 | username、认证 profile 和 access token 只用于新建 protocol | 认证服务 | 登录服务器 | 认证协议由库提供；认证重试由工具实现 |
| 位置运动 | x、y、z、yaw、pitch | Player Position、Move Entity Position Rotation | 显示 Bot 位置 | 不是库内置 |
| 生命死亡 | health | Set Health | 显示状态；health 小于等于零时立即 Respawn | 不是库内置 |
| 饥饿经验 | food；没有经验 | Set Health | 显示状态 | 不是库内置 |
| 背包窗口 | 无 | 无 | 无 | 无 |
| 实体世界 | difficulty | Change Difficulty | 显示世界难度 | 不是库内置 |
| 玩家列表 | `List<PlayerListEntry>` | Player Info | 查询在线玩家 | 不是库内置 |
| 聊天插件消息 | 不保存聊天；保存启动 command 和 command delay | Chat Packet、Connected | 输出翻译聊天；连接后延迟发送一次命令 | 不是库内置 |
| 任务专用状态 | langFile、headless、autoReconnect、reconnectDelay、command | 连接生命周期 | CLI/GUI 行为和自动重连 | 不是库内置 |

`EntityPos` 保存五个位置和视角值。参见 [`EntityPos.java:10-33`](https://github.com/alwyn974/MinecraftBOT/blob/6828d2626aead969a38ebd1e0427b1c4e8b20a92/src/main/java/re/alwyn974/minecraft/bot/entity/EntityPos.java#L10-L33)。

监听器处理 Chat、Player Position、Move Entity Position Rotation、Set Health、Change Difficulty 和 Player Info。它发送 Respawn 和一次延迟聊天命令。参见 [`MCBOTSessionAdapter.java:66-133`](https://github.com/alwyn974/MinecraftBOT/blob/6828d2626aead969a38ebd1e0427b1c4e8b20a92/src/main/java/re/alwyn974/minecraft/bot/entity/MCBOTSessionAdapter.java#L66-L133)。

这里监听 `ClientboundMoveEntityPosRotPacket` 后更新 Bot 自身位置，但没有校验 entity ID。该状态可能被其他实体 Packet 污染。它是工具实现事实，不应作为正确协议模式复用。

该工具没有周期游戏 tick。它只有一次性 command Timer、Microsoft 认证的 5 秒轮询，以及断线后的阻塞延迟。

断线后，如果启用 autoReconnect 且 reason 不是字面量 `Disconnected`，线程等待配置时长并重新认证和连接。参见 [`MCBOTSessionAdapter.java:46-63`](https://github.com/alwyn974/MinecraftBOT/blob/6828d2626aead969a38ebd1e0427b1c4e8b20a92/src/main/java/re/alwyn974/minecraft/bot/entity/MCBOTSessionAdapter.java#L46-L63)。

该工具没有显式 KeepAlive listener。它依赖普通 `MinecraftProtocol` 和 `TcpClientSession` 的默认 listener。工具源码没有给出旧库的精确保活 Packet。

### chipmunkmc/chipmunkbot 完整状态

该项目是最薄的可插拔无头客户端。`Client` 只保存 Session 和 plugin ID 到 plugin 的 Map。参见 [`Client.java:14-44`](https://github.com/chipmunkmc/chipmunkbot/blob/dbf3c23b21a9d2ad2739f4a44ed5cb22bd3d5802/src/main/java/land/chipmunk/chipmunkbot/Client.java#L14-L44)。

| 分类 | 保存状态 | 入站来源 | 用途 | MCProtocolLib 内置 |
| --- | --- | --- | --- | --- |
| 连接协议 | host、port、MinecraftProtocol、proxy、Session | 无自定义生命周期监听 | 建立连接 | Session 和默认协议 listener 由库提供 |
| 玩家身份 | username 只用于构造 protocol | 配置 | 离线身份登录 | 协议 profile 由库管理 |
| 位置运动 | 无 | 无 | 无 | 无 |
| 生命死亡 | 无 | 无 | 无 | 无 |
| 饥饿经验 | 无 | 无 | 无 | 无 |
| 背包窗口 | 无 | 无 | 无 | 无 |
| 实体世界 | 无 | 无 | 无 | 无 |
| 玩家列表 | profile、gamemode、latency、displayName、key expiry、public key、signature | Player Info 的 add、update 和 remove 动作 | 持续维护可查询玩家列表 | Packet 类型由库提供；列表归并不是库内置 |
| 聊天插件消息 | ChatPlugin 只保存 Client 引用 | 无入站聊天处理 | 主动发送 chat 和 command | 不是库内置 |
| 任务专用状态 | plugin Map、Brigadier dispatcher | plugin 注入 | 扩展功能和命令注册 | 不是库内置 |

`PlayerListPlugin` 按 UUID 合并 Player Info 更新。它处理新增、模式、延迟、显示名和移除。参见 [`PlayerListPlugin.java:18-105`](https://github.com/chipmunkmc/chipmunkbot/blob/dbf3c23b21a9d2ad2739f4a44ed5cb22bd3d5802/src/main/java/land/chipmunk/chipmunkbot/plugins/PlayerListPlugin.java#L18-L105)。

每个列表项还保存 secure-chat 公钥过期时间、公钥和签名。参见 [`MutablePlayerListEntry.java:12-25`](https://github.com/chipmunkmc/chipmunkbot/blob/dbf3c23b21a9d2ad2739f4a44ed5cb22bd3d5802/src/main/java/land/chipmunk/chipmunkbot/data/MutablePlayerListEntry.java#L12-L25)。

`ChatPlugin` 主动构造带时间戳的 Chat 和 Command Packet。它不监听入站聊天。参见 [`ChatPlugin.java:18-35`](https://github.com/chipmunkmc/chipmunkbot/blob/dbf3c23b21a9d2ad2739f4a44ed5cb22bd3d5802/src/main/java/land/chipmunk/chipmunkbot/plugins/ChatPlugin.java#L18-L35)。

该工具没有周期任务，也没有自定义断线策略。它没有显式 KeepAlive listener，因此依赖 MCProtocolLib 默认 listener。

### 四个工具的状态覆盖

| 分类 | McFisherBot | MCFishingBot | MinecraftBOT | chipmunkbot |
| --- | --- | --- | --- | --- |
| 连接协议 | 完整 Session 和 connected | Session、重连 Future、认证刷新 | Session、认证和重连配置 | Session 和 plugin Map |
| 玩家身份 | entity ID | entity ID、账户映射 | username 和认证 profile | username、玩家 profile 列表 |
| 位置运动 | 位置 | 无 | 位置和视角 | 无 |
| 生命死亡 | health、自动 Respawn | health、低血退出、人工 Respawn | health、自动 Respawn | 无 |
| 饥饿经验 | 无 | food、saturation、经验 | food | 无 |
| 背包窗口 | window ID 和 slots | 只有未使用的类型分发点 | 无 | 无 |
| 实体世界 | 浮标实体和位移 | 浮标、掉落物、实体 UUID | difficulty | 无 |
| 玩家列表 | 无 | 无 | 完整列表快照 | 增量玩家列表和 secure-chat key |
| 聊天插件消息 | 入站关键字、brand、settings | 双向聊天桥和合并缓冲 | 入站日志和一次命令 | 主动 chat/command |
| 任务专用状态 | 抛竿、出售、丢弃 | 捕获时间、loot、空闲检测 | CLI/GUI 和一次命令 | plugin 和 dispatcher |

该比较说明 MCProtocolLib 只负责协议、Session 和默认登录保活。玩家模型、世界模型、业务状态和重连策略都由工具自行选择。

### MCProtocolLib 内置行为

当前 MCProtocolLib `ClientListener` 在 GAME 状态自动回复 KeepAlive。它在 CONFIGURATION 状态也执行相同回复。参见 [`ClientListener.java:135-143`](https://github.com/GeyserMC/MCProtocolLib/blob/19783c29ece24bc3f07f8ff08628549527e3de20/protocol/src/main/java/org/geysermc/mcprotocollib/protocol/ClientListener.java#L135-L143) 和 [`ClientListener.java:159-169`](https://github.com/GeyserMC/MCProtocolLib/blob/19783c29ece24bc3f07f8ff08628549527e3de20/protocol/src/main/java/org/geysermc/mcprotocollib/protocol/ClientListener.java#L159-L169)。

内置行为还包括：

| 入站 Packet | 出站 Packet 或动作 |
| --- | --- |
| `ClientboundStartConfigurationPacket` | 切换入站状态，发送 `ServerboundConfigurationAcknowledgedPacket`，切换出站状态 |
| `ClientboundFinishConfigurationPacket` | 切换入站状态，发送 `ServerboundFinishConfigurationPacket`，切换出站状态 |
| `ClientboundSelectKnownPacks` | 默认发送空的 `ServerboundSelectKnownPacks` |
| `ClientboundDisconnectPacket` | 关闭 Session |
| `ClientboundTransferPacket` | 默认创建新连接并跟随 Transfer |

Shadow 不能跟随 Transfer。无 Channel Session 必须把 `FOLLOW_TRANSFERS` 设为 `false`，或者等价地禁止该分支。内置行为见 [`ClientListener.java:144-157`](https://github.com/GeyserMC/MCProtocolLib/blob/19783c29ece24bc3f07f8ff08628549527e3de20/protocol/src/main/java/org/geysermc/mcprotocollib/protocol/ClientListener.java#L144-L157)。

`ClientListener` 不处理 GAME 或 CONFIGURATION 的 `ClientboundPingPacket`。Plugin 必须回复同 ID 的 `ServerboundPongPacket`。

`ClientListener` 也不处理位置确认、Chunk Batch、PlayerLoaded、ClientTickEnd、Cookie、Resource Pack 或 Code of Conduct。这些行为不能归入 MCProtocolLib 内置保活。

### 与当前 26.2 清单的比较

| 当前项目 | 结论 |
| --- | --- |
| KeepAlive | 必需。MCProtocolLib 内置处理 GAME 和 CONFIGURATION。 |
| Ping/Pong | 必需。MCProtocolLib 不内置处理，需要 Plugin 补充。 |
| PLAY/CONFIGURATION 转换 | 必需。MCProtocolLib 内置状态切换和响应。无 Channel Session 必须覆盖切换方法。 |
| PlayerPosition、PlayerRotation、Teleport 确认 | 保留。旧 Bot 会保存位置。26.2 的确认 Packet 需要按当前协议实现。 |
| ChunkBatchReceived | 保留。该 Packet 是新协议的流量控制响应。旧工具版本无法提供证据。 |
| Respawn 后 PlayerLoaded | 保留。它属于 26.2 的加载完成流程。旧工具版本无法提供证据。 |
| ClientTickEnd 每 50 毫秒 | 不保留。26.2 原版服务端研究确认该 Packet 不是保活要求。 |
| CookieResponse | 不能只实现固定空响应。Plugin 应观察 `ClientboundStoreCookiePacket`，按 key 保存值，并对 `ClientboundCookieRequestPacket` 返回保存值或空值。 |

Cookie 是当前清单的第二个缺口。只处理 Request 会丢失真实客户端已经接收的 Store Cookie 状态。该问题不会影响所有服务器，但会破坏使用 Cookie 的后端流程。

Resource Pack 和 Code of Conduct 仍是条件性阻塞流程。它们需要用户策略，不是无条件保活 Packet。初版可以保持延后，但必须接受要求这些流程的服务器会断开 Shadow。

### 建议的最小状态

`AutomationService` 只需保存以下协议状态：

- MCProtocolLib 入站和出站 `ProtocolState`
- 当前位置、旋转、地面状态和移动标志
- 当前 teleport ID 和 PlayerLoaded 等待状态
- Cookie 的 key 到 payload 映射
- Shadow 启用状态和待发送响应队列

当前任务维护生命、死亡、实体和世界状态，但不自动 Respawn。

玩家列表、经验、饥饿值和背包不属于初版范围。

状态机不需要周期任务。KeepAlive、Ping、配置切换、位置确认和 Chunk Batch 都由入站 Packet 驱动。

### 项目内文件

- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/shadow-player-state-machine.md`：当前直接协议状态设计。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/shadow-player-state-machine.md:36`：当前 Packet 清单和状态记录。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/design.md:231`：当前 Patch 与 Plugin 的 Shadow 边界。
- `.trellis/spec/backend/velocity-plugin.md:184`：现有运行时约束和固定 MCProtocolLib 目标。

### Related Specs

- `.trellis/spec/backend/velocity-plugin.md`
- `.trellis/spec/language/java.md`

## Caveats / Not Found

- GitHub 仓库搜索只找到旧版 MCProtocolLib Bot。没有找到与 26.2 Shadow 语义直接匹配的实现。
- GitHub 匿名 Code Search 要求认证。grep.app 在调查时返回限流。跨仓库源码搜索因此不完整。
- 第三方项目多数依赖 MCProtocolLib 自带 `ClientListener`。它们没有重新列出全部隐式协议响应。
- MCProtocolLib `master` 的引用 commit 是 `19783c29ece24bc3f07f8ff08628549527e3de20`。项目固定的 26.2 snapshot 仍需在实现前核对相同类。
- ChunkBatchReceived、PlayerLoaded 和 ClientTickEnd 是新协议行为。旧 Bot 不能验证其完整条件。
- “服务器通常不会因死亡界面断开”不是本次第三方源码证明的保证。初版不自动 Respawn。

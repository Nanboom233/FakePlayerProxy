# Research: 成熟无头客户端维护的登录后状态

- Query: Mineflayer、node-minecraft-protocol 和 Minecraft Console Client 在登录后维护哪些协议状态与游戏状态，哪些经验可用于 Shadow。
- Scope: external / mixed
- Date: 2026-08-13

## Findings

### 调查对象

| 项目 | 检查版本 | 定位 |
| --- | --- | --- |
| [PrismarineJS/node-minecraft-protocol](https://github.com/PrismarineJS/node-minecraft-protocol/tree/aa23a03964bf84e2f7fe813818a4ec5b7b2a1270) | `aa23a03` | Mineflayer 下层协议客户端。适合识别不依赖世界模型的协议核心。 |
| [PrismarineJS/mineflayer](https://github.com/PrismarineJS/mineflayer/tree/a89e76b7a45e790247be77b5c18e155efd89315d) | `a89e76b` | 完整 Bot。默认加载大量内部插件，但每个插件都可关闭。 |
| [MCCTeam/Minecraft-Console-Client](https://github.com/MCCTeam/Minecraft-Console-Client/tree/d50e90d8600f28ad8a66f713317aed05c1fc885a) | `d50e90d` | 长期运行的控制台客户端。协议核心与可选地形、背包、实体模块分离。 |

这些项目支持的协议范围不同。旧协议实现仍能说明状态职责。它们不能单独证明 Minecraft 26.2 的 Packet 条件。

### 三层状态

#### 协议核心状态

成熟客户端都在协议层维护连接阶段，并自动处理阶段推进。

- node-minecraft-protocol 保存 `LOGIN`、`CONFIGURATION`、`PLAY`。它处理 `configuration_acknowledged`、Client Information、Known Packs、Code of Conduct 和 Finish Configuration。参见 [`src/client/play.js:34-85`](https://github.com/PrismarineJS/node-minecraft-protocol/blob/aa23a03964bf84e2f7fe813818a4ec5b7b2a1270/src/client/play.js#L34-L85)。
- node-minecraft-protocol 独立处理 KeepAlive 回复和客户端超时。参见 [`src/client/keepalive.js:3-25`](https://github.com/PrismarineJS/node-minecraft-protocol/blob/aa23a03964bf84e2f7fe813818a4ec5b7b2a1270/src/client/keepalive.js#L3-L25)。
- MCC 在 CONFIGURATION 中回复 KeepAlive、Ping、Cookie Request、Known Packs 和 Finish Configuration。它也保存 Store Cookie。参见 [`Protocol18.cs:535-668`](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Protocol/Handlers/Protocol18.cs#L535-L668)。
- MCC 在 PLAY 中回复 KeepAlive、Chunk Batch，并处理重新进入 CONFIGURATION。参见 [`Protocol18.cs:808-810`](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Protocol/Handlers/Protocol18.cs#L808-L810) 和 [`Protocol18.cs:1293-1314`](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Protocol/Handlers/Protocol18.cs#L1293-L1314)。

这部分直接适用于 Shadow。它属于连接状态机，不属于具体 automation 功能。

#### 客户端模型状态

Mineflayer 默认加载以下内部插件：game、physics、blocks、entities、health、inventory、resource pack、chat 等。加载器允许逐项关闭。参见 [`lib/loader.js:4-46`](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/loader.js#L4-L46) 和 [`lib/loader.js:92-104`](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/loader.js#L92-L104)。

MCC 提供了更清晰的反例。它默认关闭 `TerrainAndMovements`、`InventoryHandling` 和 `EntityHandling`，同时仍可作为常驻客户端。它也默认关闭 AutoRespawn。参见 [`Settings.cs:919-936`](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Settings.cs#L919-L936) 和 [`Settings.cs:969-972`](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Settings.cs#L969-L972)。

因此，成熟客户端维护完整模型是为了支持操作。完整模型不是连接存活的证据。

#### 任务状态

钓鱼、寻路、挖掘、战斗、窗口操作和自动重连保存各自状态。它们不应进入 Shadow 基础状态机。

MCC 的 AutoRelog 是 ChatBot。Mineflayer 只向调用者发布 `end`。Shadow 保留原后端连接。Fresh login 创建新服务。这两种重连模型都不适用。

### 状态逐项比较

| 状态 | 成熟客户端的处理 | Shadow 结论 |
| --- | --- | --- |
| 协议阶段 | NMP 和 MCC 显式维护 LOGIN、CONFIGURATION、PLAY。 | 必需。Shadow 接管后继续维护 GAME 与 CONFIGURATION。 |
| KeepAlive 与 Ping | NMP 自动回复 KeepAlive。MCC 在 PLAY 和 CONFIGURATION 回复 KeepAlive，并回复配置 Ping。 | 必需或兼容性必需。保持当前清单。 |
| 位置与传送 | Mineflayer 保存位置、速度、旋转、落地状态和相对坐标标志。收到位置包后确认 teleport，并回发 PosRot。参见 [`physics.js:372-449`](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/plugins/physics.js#L372-L449)。MCC 也确认 teleport 并发送位置更新。 | 最小保存服务端位置、旋转、标志和 teleport ID。完整物理循环不自动进入初版。 |
| 物理 | Mineflayer 运行 20 TPS 物理循环。MCC 的地形与移动模块默认关闭。 | 不是基础保活。只有未来 automation 需要移动时再增加。 |
| 生命与死亡 | Mineflayer 保存 health、food 和 saturation。默认死亡后 Respawn。参见 [`health.js:3-40`](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/plugins/health.js#L3-L40)。MCC 保存 health，但 AutoRespawn 默认关闭。 | 保存 health 只在需要识别死亡时有用。自动 Respawn 是产品策略，不是连接保活。 |
| 背包与窗口 | Mineflayer 保存玩家背包、当前窗口、state ID 和 cursor。MCC 的 InventoryHandling 默认关闭。 | 初版不需要。未来发出窗口操作前，必须增加完整 state ID 和 slot 同步。 |
| 实体 | Mineflayer 保存实体表、玩家实体、载具、metadata 和 effects。MCC 的 EntityHandling 默认关闭。 | 初版不需要。载具中的 Shadow 是例外，需要另行定义策略。 |
| 世界与区块 | Mineflayer 使用 prismarine-world 和 prismarine-chunk。MCC 的 TerrainAndMovements 默认关闭。 | 不保存区块。仍回复 Chunk Batch，因为该回复是协议流量控制。 |
| 玩家列表 | Mineflayer 保存 `players`。MCC 保存 `onlinePlayers`，用于聊天验证和脚本。 | 基础 AFK 不需要。若要验证签名聊天或按玩家自动化，再增加。 |
| Abilities | 两者都会解析玩家能力，用于飞行和物理。 | 基础连接不需要。未来移动 automation 需要。 |
| Effects | 两者保存实体或玩家效果，用于物理、挖掘和行为判断。 | 基础连接不需要。 |
| Recipes | MCC 保存已解锁配方。Mineflayer 的 craft 插件消费配方。 | 基础连接不需要。 |
| Tags 与 Registry | NMP 负责阶段推进。Mineflayer 加载 dimension codec。MCC 解析 chat type、dimension、attribute、enchantment 和 dialog registry。参见 [`Protocol18.cs:560-633`](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Protocol/Handlers/Protocol18.cs#L560-L633)。 | 阶段 Packet 必须消费。只有 Packet 解码或响应依赖的 registry 才保存。不要复制完整游戏 registry。 |
| Resource Pack | Mineflayer 有独立插件。MCC 处理 Push、Remove 和翻译资源。 | 它是条件性配置流程。需要明确接受或拒绝策略，但不需要下载和解析资源。 |
| Cookies | MCC 保存 key 到 payload，并在 LOGIN、CONFIGURATION、PLAY 回复请求。 | 必须保存 Store Cookie，再按 key 回复。与当前结论一致。 |
| Chat Session | NMP 和 MCC 保存签名链及 last-seen 窗口。NMP 在待确认消息超过 64 后发送 acknowledgement。参见 [`src/client/chat.js:195-231`](https://github.com/PrismarineJS/node-minecraft-protocol/blob/aa23a03964bf84e2f7fe813818a4ec5b7b2a1270/src/client/chat.js#L195-L231)。MCC 有相同的 64 条阈值。参见 [`Protocol18.cs:4988-5001`](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Protocol/Handlers/Protocol18.cs#L4988-L5001)。 | 这是当前设计的待验证缺口。必须检查 26.2 服务端是否会因长期不确认入站签名聊天而断开。未验证前不直接加入范围。 |
| Plugin Message | NMP 只为已注册 channel 分发 Custom Payload。Mineflayer 的 brand 和功能插件按需使用。 | 初始化 brand 已由真实客户端完成。后续自定义 channel 是后端特定功能，不属于基础 Shadow。 |
| Reconnect | MCC AutoRelog 建立新连接。Mineflayer 把重连交给调用者。 | 不采用。Shadow 只处理原后端连接。Fresh login 创建新服务。 |

### 对 Shadow 状态机的修正

旧协议工具的研究不应被舍弃。它提供两类有效证据。

第一类是跨版本稳定的协议行为：KeepAlive、传送确认、位置同步、死亡状态和聊天确认窗口。

第二类是职责边界：完整世界、实体、背包和物理服务于可操作 Bot，不服务最低连接。

当前 Shadow 最小状态仍应包含：

- 当前协议阶段。
- 当前位置、旋转、落地与碰撞标志。
- 待确认 teleport。
- Cookie Map。
- 配置流程的待响应状态。
- 待发送响应队列。

新增待研究项只有签名聊天确认。该项需要以 Minecraft 26.2 服务端源码决定是否进入初版。

以下状态不因本次研究自动加码：

- 完整物理和周期移动。
- 世界、区块和实体表。
- 背包、窗口和配方。
- abilities 和 effects。
- 玩家列表。
- 自动 Respawn。
- Resource Pack 下载。
- Plugin Message 业务协议。
- 后端重连。

### Files Found

- `mineflayer/lib/loader.js`：默认内部插件和可关闭的插件装载边界。
- `mineflayer/lib/plugins/physics.js`：位置、物理、teleport 确认和位置回复。
- `mineflayer/lib/plugins/health.js`：生命状态和默认自动 Respawn。
- `mineflayer/lib/plugins/inventory.js`：窗口、slot 与 state ID 状态。
- `mineflayer/lib/plugins/entities.js`：实体表、玩家表、效果和载具状态。
- `mineflayer/lib/plugins/blocks.js`：世界、区块和 Chunk Batch 回复。
- `node-minecraft-protocol/src/client/play.js`：CONFIGURATION 与 PLAY 状态推进。
- `node-minecraft-protocol/src/client/keepalive.js`：KeepAlive 回复和超时。
- `node-minecraft-protocol/src/client/chat.js`：签名链、last-seen 窗口和消息确认。
- `MinecraftClient/Protocol/Handlers/Protocol18.cs`：MCC 的现代协议处理器。
- `MinecraftClient/McClient.cs`：MCC 的玩家、Cookie、生命、背包、实体和世界状态存储。
- `MinecraftClient/Settings.cs`：MCC 可选状态模块的默认值。

### Related Specs

- `.trellis/spec/backend/velocity-plugin.md`
- `.trellis/spec/language/java.md`
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/mcprotocollib-afk-tools.md`
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/vanilla-idle-state.md`

## Caveats / Not Found

- Mineflayer 的“内部插件”不是 node-minecraft-protocol 的连接核心。默认启用不能证明它是协议必需状态。
- MCC 当前分支已经支持到 26.2 Packet Palette，但不能代替本项目固定 MCProtocolLib snapshot 的类型核对。
- MCC 会处理 Transfer 并建立新连接。Shadow 已禁止 transfer 设计，因此不采用该行为。
- Resource Pack、Code of Conduct 和签名聊天仍需要用 Minecraft 26.2 服务端路径确定精确失败条件。
- GitHub API 匿名限流。本次 commit 通过 `git ls-remote` 固定，源码通过官方仓库归档读取。

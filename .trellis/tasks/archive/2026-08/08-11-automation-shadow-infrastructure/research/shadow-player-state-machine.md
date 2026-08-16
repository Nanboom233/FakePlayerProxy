# Shadow 玩家状态机

## 范围

状态机复用 Minecraft 26.2 的现有后端连接。

它维护基础协议、玩家、实体、世界和无输入玩家物理。

它不创建第二条 TCP 连接。

## 注册时点

Plugin 在原生 `PostLoginEvent` 中创建服务。

该 listener 返回原生 `EventTask`。

EventTask 把注册提交到前端 EventLoop，并等待注册完成。

Mod relay 在该 Event 前已经创建并暂停原后端。

Vanilla 短登录在该 Event 中没有后端。

Plugin 使用现有后端 helper 区分两者。

没有后端时，Plugin 不创建服务。

有后端时，Plugin 创建一个服务。

后续动态 Registry Packet 直接更新该服务。

`ClientboundLoginPacket` 初始化玩家和世界身份，并进入 GAME。

真实前端在线时，服务只跟踪状态。

进入 `shadow` 后，服务开始输出响应和物理结果。

## MCProtocolLib 边界

`ClientNetworkSession` 会创建第二条 TCP 连接，所以不能使用。

Plugin 不创建 MCProtocolLib Session 适配器。

Plugin 直接保存所需的协议状态。

Plugin 使用 Patch 的 `sendPacket` 方法发送输出。

## 基础协议响应

Plugin 按 MCProtocolLib Packet 类型处理以下响应：

| S2C Packet | C2S Packet |
| --- | --- |
| KeepAlive | KeepAlive |
| StartConfiguration | ConfigurationAcknowledged |
| SelectKnownPacks | SelectKnownPacks |
| FinishConfiguration | FinishConfiguration |

Plugin 也处理以下响应：

| S2C Packet | C2S Packet | 状态 |
| --- | --- | --- |
| Ping | Pong | 无 |
| PlayerPosition | AcceptTeleportation 和 MovePlayerPosRot | 位置和旋转 |
| ChunkBatchFinished | ChunkBatchReceived | 无 |
| StoreCookie | 无 | Cookie Map |
| CookieRequest | CookieResponse | 无 |
| PlayerChat | 每 65 个不同签名发送 ChatAck | 签名和计数 |

五个 Velocity 状态 Packet 使用 `bypass=false`。

其他响应使用默认发送。

真实前端发送 `ServerboundPlayerLoadedPacket` 时，服务记录已加载状态。

Login 和 Respawn 把该状态重置为 false。

Shadow 只在玩家位置、Load Start 和玩家所在 Chunk 都可用时发送 Player Loaded。

该门槛不需要渲染状态或固定等待时间。

## 玩家状态

玩家状态保存实体 ID、位置、速度、旋转和碰撞标志。

它也保存 Pose、生命、死亡和移动相关属性。

`ClientboundSetHealthPacket` 是生命状态的权威来源。

`ClientboundSetEntityMotionPacket` 为自身设置外部速度。

`ClientboundExplodePacket` 把击退向量加到当前速度。

两个 Packet 到达时直接修改当前速度。

服务不保存待应用的外力队列。

`ClientboundPlayerPositionPacket` 覆盖或相对更新本地状态。

真实前端在线时，C2S Move Player Packet 更新最终位置。

## 实体状态

实体 Map 只保存当前维度中服务端已发送的实体。

每个实体保存 ID、UUID、类型、位置、速度和 Pose。

实体 Map 也保存载具和乘客关系。

Remove Packet、维度切换和 Respawn 会删除旧实体。

实体不运行 AI 或本地物理。

初版玩家物理不计算本地实体 AABB 推挤。

## 世界状态

动态 Registry 提供维度类型。

Login 和 Respawn Packet 选择当前维度。

Chunk Packet 提供 Block State Palette。

Block 和 Section Update 修改现有 Chunk。

Forget Packet 删除 Chunk。

世界状态直接保存 MCProtocolLib `ChunkSection[]`。

世界状态不创建第二套 Block State 数组。

世界状态不保存渲染数据。

`ChunkSection` 自带 Biome Palette。

Plugin 不读取或复制该 Palette。

Light、Heightmap 和 Block Entity NBT 不进入世界状态。

未知 Chunk 不能按空气处理。

## 玩家物理

`shadow` 在 GAME、存活和 Chunk 完整时运行 20 TPS 物理。

每个 tick 应用零输入、流体扫描、Swimming 标志和速度阈值。

它随后用当前 Pose 和当前速度执行方块 AABB 碰撞和台阶计算。

最后应用碰撞结果、重力、阻力、方块速度因子和下一 tick 的 Pose。

状态机更新位置、速度和碰撞标志。

状态机按变化类型选择 Move Player Packet。

状态机至少每 20 tick 发送一次位置。

每个 Shadow GAME tick 最后发送 `ServerboundClientTickEndPacket`。

死亡、未加载或未知 Chunk 不停止该 Packet。

服务端位置纠正始终覆盖本地结果。

## 死亡

生命小于或等于零时，状态机进入死亡状态。

死亡状态停止普通物理和 Move Player Packet。

初版不发送 Respawn Packet。

死亡状态继续发送 Client Tick End。

## 顺序和并发

每个服务归属一个后端 connection event loop。

S2C 状态在该 event loop 更新。

C2S 状态也在该 event loop 更新。

Velocity 的前端和后端 Channel 使用同一个 EventLoop。

命令提交到该 event loop。

每个服务的周期 task 直接在该 event loop 运行。

不同玩家可以并行处理。

## Fresh Login

Fresh login 创建新的 `Player` 和新的服务。

注册时，Manager 关闭同 UUID 的旧服务和后端连接。

新服务不接收旧协议、玩家、实体、世界或物理状态。

旧周期 tick 使用 `remove(player, service)`，所以不能删除新服务。

## 已知限制

Resource Pack 和 Code of Conduct 不在初版范围。

本地实体推挤、移动活塞和载具物理不在初版范围。

完整 Vanilla 浮点等价不在初版范围。

MCProtocolLib build 15 不能正确解码 26.2 Login Packet。

物理数据分发决定已完成：本项目采用 `minecraft-data-generator` 根 `LICENSE`
的 MIT 条款，并保留来源提交和 MIT 声明；根 `package.json` 的 ISC 元数据冲突
已在 `physics-data-source.md` 和生成资源元数据中记录。

## 证据

- `research/vanilla-damage-physics.md`
- `research/mcprotocollib-world-packets.md`
- `research/bot-physics-world.md`
- `research/normal-physics-acceptance.md`
- `research/physics-data-source.md`
- `research/login-state-capture.md`

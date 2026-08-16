# Research: Shadow 受击、物理、世界与实体状态

- Query: 初版 Shadow 如何维护生命、死亡、实体和世界，使玩家受击、击退、坠落、流体运动和碰撞后的表现接近正常客户端；Mineflayer、node-minecraft-protocol、Minecraft Console Client 和轻量 AFK 工具分别维护哪些状态；是否存在可直接配合 MCProtocolLib 26.2 的 Java 库。
- Scope: mixed
- Date: 2026-08-13

## Findings

### 结论

“受到攻击后表现正常”不能只处理 `ClientboundSetEntityMotionPacket`。

服务端发送的击退速度只是物理输入。客户端还必须按 20 TPS 执行重力、阻力、方块碰撞、落地和流体计算，再把结果位置发回服务端。

这个闭环至少需要：

1. 玩家物理状态。
2. 玩家附近的方块世界。
3. 外部速度输入。
4. 20 TPS 物理循环。
5. 原版式移动 Packet 选择。
6. 生命、死亡和 Respawn 状态。

完整实体表不能替代方块世界。普通近战击退通过玩家自己的 motion Packet 表达。附近实体位置主要服务于 automation、载具和实体交互。

Mineflayer 提供最完整的可参考闭环。MCC 提供较小但仍真实运行的实现。已有 MCProtocolLib AFK 工具只保证连接存活。它们不处理击退后的持续运动。

没有找到成熟的 Java 库，可以直接消费 MCProtocolLib 26.2 Packet，并提供原版玩家物理和世界模型。

### 原版 26.2 受击路径

普通攻击造成的玩家击退最终通过 `ClientboundSetEntityMotionPacket` 到达客户端。26.2 `ClientPacketListener.handleSetEntityMotion()` 根据 entity ID 找到实体，并调用 `Entity.lerpMotion()`。

爆炸使用 `ClientboundExplodePacket.playerKnockback`。26.2 `ClientPacketListener.handleExplosion()` 把该向量加到本地玩家速度。

这两条路径都只改变速度。后续位移由客户端每 tick 的玩家物理产生。

客户端物理需要完成：

- 外部速度应用。
- 重力和垂直阻力。
- 地面摩擦和空气惯性。
- AABB 方块碰撞。
- 台阶抬升。
- 落地和水平碰撞标志。
- 坠落距离。
- 水、熔岩和水流。
- 梯子、藤蔓和可攀爬方块。
- 药水效果和移动属性。
- 蜘蛛网、灵魂沙、蜂蜜、冰和气泡柱等方块效果。

原版 `LocalPlayer.sendPosition()` 按变化选择 Pos、Rot、PosRot 或 StatusOnly。位置还会定期重发。Packet 包含 `onGround` 和 `horizontalCollision`。

因此，只把击退速度直接加到坐标一次，不是正常客户端行为。它会忽略碰墙、落地、滑动和流体。

### Mineflayer 的完整模型

Mineflayer 的 `physics` Plugin 每 50 毫秒执行一次。它在玩家所在 Chunk 未加载时停止物理。

入口见 [Mineflayer physics.js](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/plugins/physics.js#L66-L88)。

它保存以下玩家状态：

- position 和 velocity。
- yaw 和 pitch。
- onGround。
- horizontal 和 vertical collision。
- water、lava 和 web 标志。
- jump 状态和冷却。
- sprint、sneak 和 movement input。
- attributes。
- jump boost、speed、slowness、slow falling、levitation 和 dolphins grace。
- depth strider。
- elytra 和 firework 状态。

`prismarine-physics` 从 `world.getBlock()` 读取碰撞方块。它处理 AABB 碰撞、台阶、重力、摩擦、水、熔岩、水流、攀爬、蜘蛛网、气泡柱和部分特殊方块。

实现见 [prismarine-physics index.js](https://github.com/PrismarineJS/prismarine-physics/blob/a5353a922f1dee075aa797cb53be31919f9e1f46/index.js#L157-L358) 和 [流体处理](https://github.com/PrismarineJS/prismarine-physics/blob/a5353a922f1dee075aa797cb53be31919f9e1f46/index.js#L466-L702)。

Mineflayer 对 `entity_velocity` 更新对应实体速度。玩家自身也在同一实体表中。

实现见 [entities.js](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/plugins/entities.js#L281-L286)。

Mineflayer 对爆炸的玩家击退做向量累加。

实现见 [physics.js](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/plugins/physics.js#L312-L326)。

Mineflayer 的世界模型由 `blocks` Plugin 维护。它处理：

- Chunk 加载。
- Chunk 卸载。
- 单方块更新。
- Section 多方块更新。
- 光照数据。
- 爆炸方块更新。
- Dimension 或 World 切换时的清空。
- Chunk Batch 确认。

实现见 [blocks.js](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/plugins/blocks.js#L88-L150)、[Chunk 与 Block 更新](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/plugins/blocks.js#L537-L711) 和 [World 切换](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/plugins/blocks.js#L729-L796)。

Mineflayer 每 tick 计算位置，但只在位置、视角或地面状态需要同步时发包。它还每秒重发位置。

实现见 [physics.js updatePosition](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/plugins/physics.js#L156-L205)。

#### Mineflayer 的限制

Mineflayer 不是原版客户端的逐行移植。

- `prismarine-physics` 主要计算方块碰撞。它没有完整的玩家与实体碰撞推挤模型。
- 某些特殊方块、姿态、载具和新版本机制依赖 feature flags。
- Mineflayer 当前参考提交不能证明支持 Minecraft 26.2。
- `physics.js` 和 `prismarine-physics` 是 JavaScript。
- 它依赖 `minecraft-data`、`prismarine-block`、`prismarine-world`、`prismarine-chunk`、`vec3` 和 NBT 生态。

`prismarine-physics` 使用 MIT License。算法可作为移植参考，但不能作为 Java 依赖直接使用。

### node-minecraft-protocol 的职责

node-minecraft-protocol 只负责 Packet 编解码、协议阶段、KeepAlive 和基础自动响应。

它不提供世界、实体或玩家物理。因此，MCProtocolLib 加 node-minecraft-protocol 式状态机仍不能处理击退后的运动。

这与当前架构边界一致。MCProtocolLib 负责 Packet 类型。Plugin 必须另行持有 gameplay 状态。

### MCC 的可选物理模型

MCC 默认关闭 `TerrainAndMovements`、`EntityHandling` 和 `AutoRespawn`。

默认配置见 [Settings.cs](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Settings.cs#L919-L972)。

关闭这些模块时，MCC 可以长期在线，但不会模拟正常击退运动。这是“保活模式”，不是本次要求的“正常受击模式”。

开启 `TerrainAndMovements` 后，MCC 保存：

- World 和 Chunk。
- location、yaw 和 pitch。
- `PlayerPhysics`。
- movement input。
- path 和 path target。
- onGround 和 horizontal collision。

MCC 每个 tick 更新环境，应用输入，运行 `PlayerPhysics.Tick(world)`，再发送位置。

调用链见 [McClient.cs](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/McClient.cs#L676-L716)。

MCC 的 `PlayerPhysics` 实现：

- DeltaMovement。
- 重力和阻力。
- 方块摩擦。
- AABB 碰撞。
- 台阶。
- 坠落距离。
- 水和熔岩。
- 攀爬。
- 部分药水效果。
- 特殊方块速度因子。

实现见 [PlayerPhysics.cs](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Physics/PlayerPhysics.cs) 和 [CollisionDetector.cs](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Physics/CollisionDetector.cs)。

MCC 的 Chunk 解码和方块存储位于 [Protocol18Terrain.cs](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Protocol/Handlers/Protocol18Terrain.cs)。

#### MCC 的重要缺口

当前 MCC 收到 `EntityVelocity` 时，只在 `EntityHandling` 启用后调用 `OnEntityVelocity()`。

`OnEntityVelocity()` 只更新实体表并分发 Bot Event。它没有把玩家自身速度写入 `playerPhysics.DeltaMovement`。

因此，MCC 已有物理模块不能直接证明它正确处理玩家受击。采用其设计时必须补上“自身 entity motion 到玩家物理”的连接。

MCC 物理源码自述对应 vanilla 1.21.11。代码中仍有简化项，例如 slime bounce 的 TODO。它不是 Minecraft 26.2 的完整权威实现。

MCC 使用 C# 和自有 Packet、World、Material、Chunk 系统。它采用 CDDL 1.0。把代码移植到 Java 的成本高于只参考算法。

### 轻量 AFK 工具

已有研究中的 McFisherBot、MinecraftBOT、McFisherBot 1.12.2 和 chipmunkbot 维护 KeepAlive、位置、生命或业务实体。

它们没有形成以下闭环：

```text
自身 motion 或爆炸击退
-> 玩家速度
-> 20 TPS 重力与碰撞
-> 新位置和碰撞标志
-> serverbound movement
```

部分工具只保存 entity velocity。部分工具忽略它。部分工具把实体相对移动错误地应用到 Bot 位置。

这些方案能继续在线，但玩家被攻击后可能保持原坐标、被服务端纠正、悬空或出现不自然移动。

没有找到一个更小的 MCProtocolLib AFK 工具，同时满足以下条件：

- 长期在线。
- 玩家自身击退。
- 重力和落地。
- 方块碰撞。
- 流体。
- 正常移动回报。

因此，不能从这些工具推出“只处理 velocity Packet 即可”。

### 实体状态的实际边界

初版已决定维护实体和世界。建议区分两个目的。

#### 玩家物理必需实体状态

- 自身 entity ID。
- 自身 position、velocity、rotation 和 pose。
- 自身 attributes。
- 自身 active effects。
- 自身 vehicle 和 passenger 关系。

#### 完整实体表

对每个已加载实体保存：

- entity ID 和 UUID。
- entity type。
- position、rotation 和 velocity。
- metadata 和 pose。
- attributes 和 effects。
- equipment。
- vehicle 和 passengers。
- valid 或 removed 状态。

处理 Add、Remove、relative move、position sync、teleport、motion、metadata、attributes、effects、equipment 和 passengers Packet。

完整实体表支持后续 attack、use、mount 和观察逻辑。

但是，Mineflayer 的玩家物理没有用完整实体表做实体碰撞。原版式实体推挤仍是额外精度层。

#### 实体推挤

“实体推挤”有两类：

1. 服务端通过玩家 motion 或位置纠正表达的外力。
2. 客户端根据附近实体 AABB 计算的本地碰撞。

第一类必须在初版正确处理。

第二类需要实体尺寸、姿态、碰撞规则和每 tick 空间查询。Mineflayer 和 MCC 的参考物理都不完整覆盖这一层。

初版若要求与原版逐 tick 一致，就必须额外实现实体 AABB 推挤。若只要求受击后自然移动，可先依赖服务端 motion 和位置纠正，同时保留完整实体表。

### 世界状态的实际边界

玩家物理至少需要当前 Dimension 中、玩家附近已发送的所有 Chunk。

保存：

- Dimension key、minY 和 height。
- Chunk 坐标到 Chunk Section 的映射。
- 每个 Section 的 block state palette 和 packed data。
- block state ID 到碰撞形状、摩擦、速度因子、流体和 climbable 属性的映射。

处理：

- `ClientboundLevelChunkWithLightPacket`。
- `ClientboundForgetLevelChunkPacket`。
- `ClientboundBlockUpdatePacket`。
- `ClientboundSectionBlocksUpdatePacket`。
- Respawn 或 Dimension 切换。
- Chunk Batch。

光照、Biome 和 Block Entity 不参与普通碰撞。它们可以解码后丢弃，除非后续 action 需要。

Chunk 卸载后，物理不能把未知区域当空气继续计算。Mineflayer 在当前位置 Chunk 不存在时暂停物理。这是更安全的行为。

### 生命、死亡和 Respawn

保存：

- health、food 和 saturation。
- alive 或 dead。
- death tick 或 death epoch。
- respawn pending。
- PlayerLoaded pending。

收到 `ClientboundSetHealthPacket` 后更新生命。health 小于等于零时：

1. 标记 dead。
2. 停止输入和普通物理。
3. 停止普通 movement emission。
4. 按初版产品策略决定是否发送 `PERFORM_RESPAWN`。

收到 `ClientboundRespawnPacket` 后：

1. 按 Dimension 和保留标志清理或保留 World。
2. 清理旧实体表。
3. 重置玩家物理速度、坠落距离和碰撞状态。
4. 等待新的服务端位置。
5. 确认 teleport。
6. 恢复物理。
7. 发送 PlayerLoaded。

若初版只“维护生命和死亡”而不自动 Respawn，玩家死亡后会停留在死亡状态。这是正常的禁用 AutoRespawn 行为。

### 移动发包

推荐按原版语义发包，而不是 MCC 的无条件每 tick位置包。

保存 last sent：

- x、y 和 z。
- yaw 和 pitch。
- onGround。
- horizontalCollision。
- last position send tick。

每个物理 tick：

1. 计算玩家新状态。
2. 判断 position 是否变化或达到定期重发阈值。
3. 判断 rotation 是否变化。
4. 选择 Pos、Rot、PosRot 或 StatusOnly。
5. 发送 `ServerboundClientTickEndPacket`。

Shadow 已经处于 AFK 状态。物理产生的真实位移不是伪造活动。服务端攻击、重力或流体导致的移动应正常上报。

静止时不要生成非零移动，以免改变 AFK 语义。

### 最小模型与完整模型

| 能力 | 保活最小模型 | 受击自然模型 | 原版高精度模型 |
| --- | --- | --- | --- |
| KeepAlive 和阶段响应 | 必需 | 必需 | 必需 |
| 生命和死亡 | 可只保存 health | 保存并停止死亡物理 | 保存完整死亡、Respawn 和保留标志 |
| 玩家位置 | 保存服务端位置 | position、velocity、rotation、collision | 加 pose、attributes、effects、vehicle |
| 外部速度 | 可忽略 | self motion 和 explosion knockback | 加所有 impulse、effect 和载具路径 |
| 世界 | 不需要 | 玩家附近 Chunk、Block 更新和碰撞形状 | 加完整 registry、流体细节和特殊方块 |
| 实体 | 不需要 | 完整实体表可维护，但只要求 self ID | 加实体 AABB 推挤和精确姿态 |
| 物理 | 不需要 | 20 TPS 重力、阻力、方块碰撞和流体 | 逐版本原版物理 |
| 移动发送 | teleport 回复 | 变化触发并定期重发 | 完整 LocalPlayer 发包规则 |
| 被攻击表现 | 不正常 | 自然击退、落地和碰墙 | 与原版逐 tick 接近 |

本次用户要求对应“受击自然模型”。它明显大于旧的保活最小模型。

### Java 可复用库调查

#### MCProtocolLib

MCProtocolLib 26.2 提供本任务需要的 Packet 类型和协议 codec。

它不提供：

- Chunk World。
- block state 碰撞形状。
- 玩家物理。
- 实体 tracker。
- movement emission policy。

因此，继续共享 MCProtocolLib 是正确的，但它不能承担 gameplay 状态机。

#### Spectron

[breuerlukas/spectron](https://github.com/breuerlukas/spectron) 是 2025 年末创建的 Java headless client。

它有自有协议、Chunk 和 World。当前树中没有 physics 或 collision 模块。World 未加载 Chunk 时返回 Air。

它不依赖 MCProtocolLib，也不是可发布的物理库。项目只有少量使用者，License 为 GPL-3.0。

它不能作为本项目的成熟 Java 物理依赖。

#### Baritone 和原版客户端代码

Baritone 运行在真实 Minecraft Client 内。它复用游戏自己的 World 和 Entity，而不是提供独立 headless physics。

直接引用 Minecraft Client 类会引入映射、版本、Java 运行时、客户端依赖和分发许可问题。它不适合 Velocity Plugin。

#### 其他 Java MCProtocolLib Bot

本次和已有研究检查的 MinecraftBOT、McFisherBot、chipmunkbot 等项目没有通用 physics/world library。

它们的状态代码与具体旧协议和业务耦合。不能复用为 26.2 玩家物理。

#### 可移植来源比较

| 来源 | 语言 | 协议适配 | 完整度 | 直接复用 | 移植成本 |
| --- | --- | --- | --- | --- | --- |
| prismarine-physics + Mineflayer | JavaScript | PrismarineJS 数据生态 | 当前最完整参考 | 否 | 高。需移植物理，并替换 World、Block、Registry 和 feature flags |
| MCC PlayerPhysics | C# | MCC 自有 codec，约 1.21.11 | 较小，仍有简化 | 否 | 中高。需移植数学、碰撞、形状和 World，并补 self motion |
| Spectron | Java | 自有协议 | 无物理 | 否 | 不值得采用 |
| 原版 Minecraft Client | Java | 26.2 权威实现 | 最高 | 否 | 极高。依赖和许可边界不适合 Plugin |

推荐把 Mineflayer 和 MCC 当作双重行为参考，不把任何一方直接作为依赖。

如果实现规模必须压缩，MCC 的 `PlayerPhysics` 结构更适合 Java 重写。仍需以 26.2 原版字节码校验常数和发包规则。

### 初版所需模块

建议 Plugin 内的职责分成以下模块。名称只表达职责，不规定最终类名。

1. `PlayerState`：自身 ID、位置、速度、旋转、碰撞、生命、死亡、属性和效果。
2. `WorldState`：Dimension、Chunk、Section、Block 更新和卸载。
3. `EntityState`：完整实体表和 Packet 更新。
4. `PlayerPhysics`：20 TPS 运动、重力、碰撞、流体和特殊方块。
5. `MovementEmitter`：选择 MCProtocolLib 的 serverbound movement Packet。
6. `ShadowPacketState`：配置、KeepAlive、Teleport、Chat Ack、Cookie 和 PlayerLoaded。

所有模块仍属于 Plugin。Patch 只暴露 Packet Event、Packet 发送和可取消登出。

### 初版 Packet 输入

除已有最低协议 Packet 外，新增订阅至少包括：

- Login 或 Join Game 中的自身 entity ID 和 Dimension 信息。
- `ClientboundSetEntityMotionPacket`。
- `ClientboundExplodePacket`。
- `ClientboundSetHealthPacket`。
- `ClientboundRespawnPacket`。
- `ClientboundPlayerPositionPacket`。
- `ClientboundPlayerRotationPacket`。
- `ClientboundLevelChunkWithLightPacket`。
- `ClientboundForgetLevelChunkPacket`。
- `ClientboundBlockUpdatePacket`。
- `ClientboundSectionBlocksUpdatePacket`。
- Add、Remove、Move、Teleport、PositionSync 和 Motion Entity Packet。
- Entity Data、Attributes、Mob Effects、Equipment 和 Passengers Packet。

需要继续核对 26.2 Join Game Packet 在 MCProtocolLib 中的具体类型和字段。此项属于实现前的协议映射工作。

### 并发和顺序

所有 Packet 状态更新和 20 TPS 物理 tick 应在该玩家后端 connection event loop 上执行。

原因：

- Packet 顺序影响 Chunk、motion、teleport 和 death。
- 不需要为 World、Entity 或 Physics 加跨线程锁。
- 多个玩家仍在不同 event loop 上并行。

固定 tick 任务只安排下一次 event-loop 执行。关闭 Shadow 时取消任务并清空 World 和 Entity。

## Files Found

- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/headless-client-state.md`：Mineflayer、node-minecraft-protocol 和 MCC 的基础状态对比。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/mcprotocollib-afk-tools.md`：MCProtocolLib AFK 工具和保活状态。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/old-protocol-state-model.md`：旧协议 Bot 的玩家和业务状态。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/vanilla-idle-state.md`：Minecraft 26.2 最低协议响应。
- `build/tmp/mcprotocollib-sources.jar`：MCProtocolLib 26.2 Packet 类型。
- `E:/Gradle/caches/fabric-loom/26.2/minecraft-merged.jar`：Minecraft 26.2 客户端和服务端实现。

## External References

- [Mineflayer physics Plugin](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/plugins/physics.js)
- [Mineflayer blocks Plugin](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/plugins/blocks.js)
- [Mineflayer entities Plugin](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/plugins/entities.js)
- [prismarine-physics](https://github.com/PrismarineJS/prismarine-physics/tree/a5353a922f1dee075aa797cb53be31919f9e1f46)
- [MCC PlayerPhysics](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Physics/PlayerPhysics.cs)
- [MCC CollisionDetector](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Physics/CollisionDetector.cs)
- [MCC Terrain decoder](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Protocol/Handlers/Protocol18Terrain.cs)
- [MCC runtime state](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/McClient.cs)
- [Spectron](https://github.com/breuerlukas/spectron/tree/deddd9d17b8b9e16bb92c364f38801ce22822e1f)

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`
- `.trellis/spec/language/java.md`

## Caveats / Not Found

- 没有找到成熟的 Java headless physics library，可以直接与 MCProtocolLib 26.2 组合。
- Mineflayer 和 prismarine-physics 的参考提交不能证明支持 Minecraft 26.2。
- MCC 的物理目标是 vanilla 1.21.11。它仍包含简化逻辑，并且没有把自身 `EntityVelocity` 接入玩家物理。
- Mineflayer 和 MCC 都不能证明完整处理客户端实体 AABB 推挤。
- Minecraft 26.2 的准确物理常数、block state collision shape 和 Packet 字段仍需在实现前逐项从固定版本验证。
- 本报告确定状态和模块边界。它不决定自动 Respawn 产品策略。

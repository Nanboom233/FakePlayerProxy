# Research: Minecraft 26.2 Shadow 伤害、外力与物理状态

- Query: Shadow 初版维护生命、死亡、实体和世界时，如何在受击、击退、爆炸、活塞、流体、坠落、窒息和火焰中表现为 Vanilla 玩家
- Scope: internal / local dependency source
- Date: 2026-08-13

## Findings

### 结论

服务端攻击造成的玩家击退主要通过 `ClientboundSetEntityMotionPacket` 交给客户端。
客户端必须把这个速度写入本地玩家，并在 20 TPS 物理循环中逐 tick 积分。
客户端随后通过 `ServerboundMovePlayerPacket` 把实际位置、落地状态和水平碰撞状态发回服务端。

服务端不会仅凭击退速度持续替玩家完成同一条轨迹。
`ServerGamePacketListenerImpl.tickPlayer()` 每 tick 运行 `player.doTick()`，然后把玩家恢复到本 tick 开始时的 `firstGood` 位置。
服务端保留客户端移动验证和环境伤害，但普通玩家移动仍由客户端报告的位置驱动。

因此，Shadow 若只记录速度但不运行物理，受击玩家会留在原位置。
它不会按 Vanilla 轨迹被打下平台、撞墙、落水或落入岩浆。
这不一定立即违反协议，但明显违反本次要求的正常物理表现。

### Vanilla 击退闭环

1. 服务端 `LivingEntity.hurtServer()` 结算伤害和无敌帧。
2. 普通伤害调用 `dealDefaultKnockback()`。
3. `knockback()` 按抗性、来源方向和当前速度计算新速度。
4. `markHurt()` 设置 `hurtMarked`。
5. `ServerEntity.sendChanges()` 向追踪者和玩家本人发送 `ClientboundSetEntityMotionPacket`。
6. 客户端 `ClientPacketListener.handleSetEntityMotion()` 调用 `entity.lerpMotion()`。
7. `LocalPlayer.tick()` 和 `LivingEntity.travel()` 每 tick 应用重力、阻力、流体和碰撞。
8. `LocalPlayer.sendPosition()` 在位置变化时发送 `ServerboundMovePlayerPacket.Pos` 或 `PosRot`。
9. 即使完全静止，`positionReminder >= 20` 也使客户端每 20 tick 发送一次位置。
10. 服务端 `handleMovePlayer()` 校验速度、碰撞、落地和悬空状态，再接受新位置。

证据：

- `LivingEntity.java:1166-1254`：伤害、无敌帧、伤害事件、`markHurt()`、默认击退和死亡。
- `LivingEntity.java:1280-1295`：投射物使用自身击退方向，其他来源使用伤害源位置。
- `LivingEntity.java:1628-1645`：击退抗性和速度公式。
- `ServerEntity.java:221-224`：`hurtMarked` 向玩家本人发送速度包。
- `ClientPacketListener.java:624-629`：速度包写入客户端实体速度。
- `LocalPlayer.java:228-246`：本地玩家 tick 后发送玩家或载具移动。
- `LocalPlayer.java:264-299`：移动阈值、20 tick 强制位置包和状态字段。
- `ServerGamePacketListenerImpl.java:313-320`：服务端实体 tick 后恢复 `firstGood` 位置。
- `ServerGamePacketListenerImpl.java:1050-1177`：服务端移动校验和位置接收。

### 攻击和投射物

近战攻击、箭、三叉戟和其他普通投射物都走服务端伤害结算。
投射物方向由 `Projectile.calculateHorizontalHurtKnockbackDirection()` 决定。
最终速度仍通过 `ClientboundSetEntityMotionPacket` 到达玩家客户端。

Shadow 必须维护：

- 玩家生命、吸收生命、饥饿和饱和度。
- `invulnerableTime`、`lastHurt`、伤害来源和死亡标志。
- 玩家位置、速度、旋转、落地状态和水平碰撞状态。
- 击退抗性和影响物理的 Attribute。
- 影响重力、速度、阻力和击退的 Mob Effect。
- 攻击者和直接投射物的实体 ID、位置、速度和类型。

服务端是生命和实际伤害的权威来源。
Shadow 不应重新计算服务器伤害值。
它应以 `ClientboundSetHealthPacket` 更新生命，并用伤害包维护来源和表现状态。

### 爆炸

爆炸使用 `ClientboundExplodePacket` 单独携带玩家击退向量。
客户端 `handleExplosion()` 直接把该向量加到本地玩家速度。
该路径不依赖玩家实体的 `ClientboundSetEntityMotionPacket`。

Shadow 必须：

- 读取 `playerKnockback`。
- 把向量加到当前速度，而不是覆盖速度。
- 应用后续重力、阻力和碰撞。
- 处理同批爆炸造成的方块变化，避免使用过期碰撞体。

证据：`ClientPacketListener.java:1353-1370`。

### 实体推挤

附近实体碰撞由 `LivingEntity.pushEntities()` 和 `Entity.push()` 计算。
客户端本地世界也 tick 实体，并能预测玩家与实体的碰撞。
服务端同时运行实体推挤，但玩家 tick 后的位置会恢复到 `firstGood`。

要表现正常，Shadow 至少需要附近可碰撞实体：

- ID、类型、位置、包围盒和 Pose。
- 相对移动、绝对同步和传送。
- `noPhysics`、可推挤、乘客和载具关系。
- 影响包围盒的 Entity Data。

不需要为所有远处实体运行 AI。
只需消费服务端实体更新，并在玩家物理查询范围内参与碰撞。

证据：

- `LivingEntity.java:3127-3213`：每 tick 实体推挤。
- `Entity.java:1863-1903`：实体间推力写入速度。
- `ClientPacketListener.java:568-790`：实体创建、移动、传送和删除。
- `ClientPacketListener.java:633-638`：Entity Data 更新。

### 活塞

活塞是特殊情况。
`PistonMovingBlockEntity.moveCollidedEntities()` 在服务端直接调用 `entity.move(MoverType.PISTON, delta)`。
`Entity.limitPistonMovement()` 限制单 tick 活塞位移。

客户端也通过移动活塞方块实体计算同一推挤。
服务端没有为每个活塞推动专门发送玩家位置包。
所以 Shadow 不能只等 `ClientboundPlayerPositionPacket`。

正常表现需要：

- 活塞基座、移动方块、方向、进度和伸缩状态。
- 玩家包围盒与移动活塞碰撞体。
- `MoverType.PISTON` 的位移限制。
- Block Event、Block Update 和 Block Entity Data 的顺序处理。

证据：

- `PistonMovingBlockEntity.java:120-195`：碰撞实体选择和活塞位移。
- `Entity.java:712-725`：活塞移动进入专用限制分支。
- `Entity.java:1098`：活塞位移累计限制入口。

### 流体

水和岩浆的正常移动依赖世界方块和流体状态。
客户端每 tick 查询玩家包围盒覆盖的流体，应用水流或岩浆流速度。
水和岩浆还使用不同阻力、重力和浅液体规则。

Shadow 必须维护：

- 玩家包围盒覆盖区域的 Block State 和 Fluid State。
- 水和岩浆高度、流向和维度的快速岩浆属性。
- 是否浸水、眼睛是否入水、是否在岩浆和是否受流体推动。
- 空气值、着火 tick 和冻结 tick。
- 水下呼吸、海豚恩惠、抗火等相关 Effect。

水流轨迹、浮沉、出水碰撞和落水重置坠落距离需要区块数据。
溺水、岩浆和火焰伤害最终由服务端结算。
但是没有本地位移时，玩家可能根本不会进入服务端对应方块。

证据：

- `Entity.java:511-556`：流体更新、火焰、岩浆和虚空检查。
- `Entity.java:1637-1660`：流体接触和流向加速度。
- `LivingEntity.java:436-455`：空气和溺水伤害。
- `LivingEntity.java:2474-2534`：水和岩浆移动公式。

### 火焰、岩浆、窒息和其他方块伤害

这些伤害由服务端实体 tick 权威执行。
Shadow 不需要自行向服务端报告“受到伤害”。
Shadow 仍需跟踪服务端发送的生命、死亡和实体状态。

但正常位移仍需要世界模型：

- 被击退穿过火焰时，客户端碰撞轨迹决定哪些位置会发给服务端。
- 被推入实心方块时，客户端使用碰撞体阻挡移动，并尝试 `moveTowardsClosestSpace()` 脱离窒息方块。
- 服务端 `isInWall()` 决定窒息伤害。
- 方块内部效果依赖每 tick 的 `checkInsideBlocks()`。

因此，火焰伤害值不依赖客户端模拟。
进入火焰或窒息方块的正常路径依赖区块碰撞。

证据：

- `LivingEntity.java:422-445`：窒息、边界和溺水伤害。
- `Entity.java:535-556`：燃烧和岩浆伤害。
- `Entity.java:951-971`：移动后的方块内部效果。
- `LocalPlayer.java:789-794`：客户端从窒息方块向可用空间移动。
- `LocalPlayer.java:476-480`：客户端窒息碰撞查询。

### 坠落和虚空

客户端物理负责重力和下落轨迹。
客户端发送位置和 `onGround`。
服务端在接受移动时调用 `doCheckFallDamage()`。

正常坠落需要：

- 当前速度和重力 Attribute。
- 当前维度最小高度和重力。
- 脚下方块碰撞体、摩擦和弹性。
- `fallDistance`、`onGround`、垂直碰撞和水平碰撞。
- 水、梯子、蛛网、粉雪和减免坠落方块状态。

虚空死亡本身不需要区块碰撞。
一旦位置低于 `level.getMinY() - 64`，服务端执行 below-world 逻辑。
但 Shadow 必须先通过本地重力和移动包把玩家位置推进到该高度。

若 Shadow 在被击落后不发移动，服务端玩家会停留在平台边缘。
它不会仅凭速度自动掉入虚空。

证据：

- `LivingEntity.java:2440-2465`：空气移动、重力、阻力和方块摩擦。
- `Entity.java:712-820`：碰撞、落地和坠落伤害入口。
- `Entity.java:580-583`：虚空高度检查。
- `ServerGamePacketListenerImpl.java:1154-1177`：接受移动后更新落地和坠落距离。

### 无移动包时的服务端行为

服务端没有“必须每 tick 收到移动包”的协议要求。
KeepAlive 仍可维持连接。
但是没有移动包时会出现以下结果：

| 情况 | 服务端结果 |
| --- | --- |
| 普通攻击击退 | 发送一次速度。玩家位置通常停在最后确认位置。 |
| 爆炸击退 | 爆炸包携带速度。玩家位置仍等待客户端报告。 |
| 重力和自然坠落 | 服务端玩家 tick 的临时位移被恢复。不会形成正常客户端坠落轨迹。 |
| 活塞 | 服务端可能直接移动玩家。客户端仍需世界状态才能复制和继续后续物理。 |
| 水流 | 服务端 tick 可计算流体速度，但普通玩家位置仍受最后确认位置约束。 |
| 实体碰撞 | 服务端可产生速度，位置仍需要客户端移动闭环。 |
| 环境伤害 | 当前服务端位置中的火、岩浆、窒息、溺水和边界伤害继续结算。 |
| 虚空 | 只有服务端确认位置进入阈值后才触发。没有客户端下落就不会自然进入。 |

`clientIsFloating` 主要在处理移动包时更新。
完全不发送移动包不会模拟坠落，也不是正确规避飞行检查的方法。

### 世界缓存的最低边界

“维护世界”不要求渲染，也不要求保存整个维度。
初版可维护服务端已发送、且仍在加载范围内的区块缓存。

最低世界状态包括：

- Dimension Key、Dimension Type、最低 Y、高度和重力环境值。
- Chunk 坐标到 Chunk Section 的映射。
- 每个 Section 的 Block State palette 和索引数据。
- Block Update 和 Section Blocks Update。
- Chunk load、Chunk unload 和区块批次边界。
- Block Entity Data，至少覆盖移动活塞。
- Block State 到碰撞形状、摩擦、弹性、流体和内部效果的静态定义。

Packet 只提供 Block State ID 和区块内容。
它不提供每个方块的完整碰撞算法。
实现还需要与 Minecraft 26.2 匹配的方块状态表和碰撞形状数据。

最低查询范围应覆盖玩家包围盒沿本 tick 速度扫过的区域。
若相关区块尚未加载，Shadow 不能声称该 tick 与 Vanilla 等价。
安全策略应暂停物理响应，等待区块，或接受服务端后续纠正。

### 实体缓存的最低边界

初版实体状态包括：

- 自身 Entity ID。
- 其他实体的 ID、UUID、类型、位置、旋转和速度。
- Entity Data、Attribute 和 Effect。
- 乘客列表和当前载具。
- 创建、相对移动、绝对同步、传送和删除。
- 玩家附近实体的碰撞包围盒。

实体 AI、寻路、动画和渲染不在范围内。
投射物轨迹只在需要预测玩家碰撞前保存。
实际命中和伤害仍以服务端 Packet 为准。

### 生命、死亡和重生

生命状态至少包括：

- `health`、`food`、`saturation` 和 `absorption`。
- `dead`、`deathTime`、`invulnerableTime` 和 `lastHurt`。
- 当前伤害来源和伤害实体引用。
- 着火、空气、冻结和相关 Effect。

服务端通过 `ClientboundSetHealthPacket` 同步生命、食物和饱和度。
死亡时发送 `ClientboundPlayerCombatKillPacket`，并把客户端标记为未加载。

若初版要求死亡后继续保持正常玩家状态，Shadow 必须自动发送：

```text
ServerboundClientCommandPacket(PERFORM_RESPAWN)
```

服务端随后发送 `ClientboundRespawnPacket`。
Shadow 必须重建该维度的世界边界和自身状态。
完成初始区块加载后，Shadow 发送 `ServerboundPlayerLoadedPacket`。

死亡与重生边界应清理：

- 旧维度 Chunk Cache。
- 旧维度实体缓存。
- 玩家速度、碰撞标志和坠落距离。
- 待确认传送和物理 tick 中间状态。
- 载具和乘客关系。

证据：

- `ServerPlayer.java:672-678`：生命、食物和饱和度同步。
- `ServerPlayer.java:869-929`：死亡包、掉落、状态清理和客户端未加载门控。
- `ClientPacketListener.java:1833-1841`：死亡界面或立即重生。
- `LocalPlayer.java:327-330`：重生请求。
- `ServerGamePacketListenerImpl.java:1850-1873`：服务端处理重生请求。
- `ClientPacketListener.java:1245-1331`：客户端重建玩家和维度。
- `ServerGamePacketListenerImpl.java:2181-2201`：重生后的 PlayerLoaded 门控。

### 20 TPS 物理循环

Shadow 需要一个按玩家串行执行的 20 TPS 物理循环。
循环应与该玩家后端连接使用同一 event loop，避免 Packet 和 tick 并发修改状态。

每 tick 最低顺序：

1. 应用已按网络顺序收到的玩家位置纠正、速度、爆炸和世界更新。
2. 更新 Effect、火焰、空气和死亡计时状态。
3. 读取零输入。Shadow 表示 AFK，不主动行走、跳跃或转向。
4. 根据载具状态选择玩家物理或载具分支。
5. 应用流体流向、重力、空气阻力和地面摩擦。
6. 对区块碰撞体执行 swept AABB 移动。
7. 对附近实体执行推挤。
8. 更新位置、速度、落地、水平碰撞和坠落距离。
9. 位置或状态变化时发送对应 `ServerboundMovePlayerPacket`。
10. 无变化时仍按 Vanilla 每 20 tick 发送一次位置。
11. 发送本 tick 的 `ServerboundClientTickEndPacket`，用于原版客户端 tick 边界。

`ServerboundClientTickEndPacket` 不是连接保活要求。
但是实现完整 20 TPS 客户端物理后，它属于 Vanilla tick 行为，不能再简单删除。

### Packet 与状态转换表

| 输入或事件 | Shadow 状态变化 | Vanilla 输出 | 是否需要世界/实体 |
| --- | --- | --- | --- |
| `ClientboundSetEntityMotionPacket(self)` | 覆盖自身速度 | 后续 tick 的 MovePlayer | 世界碰撞 |
| `ClientboundExplodePacket` | 累加玩家击退 | 后续 tick 的 MovePlayer | 世界碰撞和爆炸方块更新 |
| `ClientboundDamageEventPacket` | 记录来源和受伤状态 | 无直接响应 | 来源实体可选，正常表现需要 |
| `ClientboundSetHealthPacket` | 更新生命、食物、饱和度 | 无直接响应 | 否 |
| `ClientboundPlayerCombatKillPacket` | 进入死亡 | `PERFORM_RESPAWN` | 否 |
| `ClientboundRespawnPacket` | 重建自身和维度 | 加载后 PlayerLoaded | 世界重置 |
| `ClientboundPlayerPositionPacket` | 应用相对字段和速度 | AcceptTeleportation + PosRot | 否 |
| Chunk With Light | 建立区块和碰撞缓存 | ChunkBatchReceived 在批次末 | 是 |
| Block/Section Update | 更新碰撞、流体和危害方块 | 无直接响应 | 是 |
| Add/Move/Teleport Entity | 更新附近实体 | 无直接响应 | 实体推挤需要 |
| Remove Entities | 删除实体和无效引用 | 无直接响应 | 是 |
| Set Entity Data | 更新 Pose 和包围盒等状态 | 无直接响应 | 实体碰撞需要 |
| Set Passengers | 更新载具关系 | 载具分支发送 MoveVehicle | 载具需要 |
| 活塞 Block Event/Data | 推进移动活塞 | 后续玩家 MovePlayer | 是 |
| 20 TPS physics tick | 积分速度和碰撞 | MovePlayer，最后 ClientTickEnd | 正常物理需要 |

### 协议正确与正常表现

| 等级 | 必需内容 | 能保证什么 |
| --- | --- | --- |
| 连接存活 | KeepAlive、配置响应、传送确认、Cookie、Chunk Batch、Chat Ack | 后端连接通常不断开 |
| 协议正确 | 正确 Packet 顺序、死亡重生、PlayerLoaded、20 tick 位置包 | 状态机不停止，服务端可接受输入 |
| 正常物理 | 20 TPS 速度积分、区块碰撞、流体、实体推挤、活塞和 Effect | 受击后位置接近 Vanilla |
| 完全等价 | Minecraft 26.2 的完整方块、实体和物理实现 | 边缘情况与真实客户端一致 |

本次新增要求至少达到“正常物理”。
只实现 Packet 响应和生命状态不充分。

### 场景与区块依赖

| 场景 | 是否需要区块碰撞 | 原因 |
| --- | --- | --- |
| 站立时被普通攻击 | 是 | 击退轨迹、墙面、台阶和平台边缘依赖碰撞。 |
| 被投射物击中 | 是 | 伤害由服务端决定，但后续击退轨迹依赖碰撞。 |
| 被爆炸推动 | 是 | 爆炸速度需经过地形碰撞，爆炸还改变方块。 |
| 被其他实体挤动 | 世界可选，实体必需 | 空旷处只需实体包围盒，靠墙时还需方块。 |
| 被活塞推动 | 是 | 推动来自移动方块碰撞体。 |
| 被推入水或岩浆 | 是 | 入液边界、流向、阻力和出水碰撞依赖方块。 |
| 被推入火焰 | 是 | 服务端结算伤害，但进入方块的轨迹依赖碰撞和位置报告。 |
| 被推入实心方块 | 是 | 阻挡、脱困和窒息位置判断依赖碰撞形状。 |
| 从平台坠落 | 是 | 离开平台、落地、弹性和坠落距离依赖地形。 |
| 已处于虚空中 | 否 | 阈值只依赖 Y 和维度最低高度。 |
| 世界边界伤害 | 否 | 服务端按位置和边界参数结算。 |

## Files Found

- `E:/Gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2-sources.jar`: Minecraft 26.2 官方命名客户端和服务端源码。
- `build/tmp/mcprotocollib-sources.jar`: MCProtocolLib 26.2 Packet 类型。确认本报告列出的生命、伤害、实体、区块、速度和移动 Packet 均有对应类型。
- `.trellis/spec/backend/velocity-plugin.md`: 当前 Patch、Plugin 和 Shadow 边界。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/vanilla-idle-state.md`: 现有连接存活状态研究。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/headless-client-state.md`: 成熟无头客户端的状态分层研究。

## Code Patterns

- `net/minecraft/server/network/ServerGamePacketListenerImpl.java:313-320`: 服务端玩家 tick 后恢复到最后确认位置。
- `net/minecraft/server/level/ServerEntity.java:221-224`: 玩家受击速度通过 `ClientboundSetEntityMotionPacket` 发给本人。
- `net/minecraft/client/multiplayer/ClientPacketListener.java:624-629`: 客户端接收并应用实体速度。
- `net/minecraft/client/player/LocalPlayer.java:264-299`: Vanilla 位置包发送策略。
- `net/minecraft/world/entity/LivingEntity.java:2402-2534`: 空气、流体、重力和阻力物理。
- `net/minecraft/world/entity/Entity.java:712-820`: 碰撞解析、落地和速度修正。

## External References

- 未使用网络资料。
- 版本固定为 Minecraft Java 26.2，协议版本 776。
- MCProtocolLib 版本为 `26.2-20260709.110151-15`。

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`
- `.trellis/spec/language/java.md`

## Caveats / Not Found

- 当前规范仍写明 Shadow 对普通 clientbound Packet 只消费，不维护世界或物理。该条与用户新增的初版范围冲突，需要主会话更新规划和规范。
- MCProtocolLib 提供 Packet codec，不提供 Minecraft 26.2 完整客户端物理、方块碰撞形状和 Chunk 世界实现。
- 本研究没有选定物理库。实现前还需比较复用现有 Java Minecraft 物理库与移植 Vanilla 必需算法的成本。
- 服务端插件可能修改速度、伤害、重力、区块或反作弊规则。Shadow 应以收到的 Packet 和服务端纠正为权威。
- 载具只在玩家当前乘坐时影响本需求。完整载具物理仍需要按具体载具类型单独研究。

# Research: Minecraft 26.2 Vanilla 玩家物理所有者

- Query: Minecraft Java 26.2 Vanilla 中，谁负责玩家物理、伤害、击退、环境效果、移动校验和位置纠正。
- Scope: mixed
- Date: 2026-08-13

## Findings

### 结论

Vanilla 26.2 使用分权模型。服务端不是完整玩家运动模拟的唯一所有者。客户端也不是生命和伤害的权威。

| 状态或计算 | 权威所有者 | 客户端职责 | 服务端职责 |
| --- | --- | --- | --- |
| 生命、吸收、饥饿和死亡 | 服务端 | 接收并显示结果 | 计算伤害、减伤、无敌帧、生命变化和死亡 |
| 普通击退初始速度 | 服务端 | 接收速度并继续积分 | 计算击退抗性、方向和初始速度，并发送速度包 |
| 爆炸击退初始速度 | 服务端 | 把爆炸速度加入本地速度并继续积分 | 计算每个玩家的爆炸击退向量并发送爆炸包 |
| 每 tick 重力、阻力、流体和方块碰撞 | 双端执行，在线玩家最终位置由客户端提出 | 使用本地世界运行玩家运动并发送位置 | 运行实体状态 tick，但不保留该 tick 自行产生的玩家位置 |
| 移动接受、碰撞复核、坠落伤害和反作弊 | 服务端 | 报告位置、落地和水平碰撞 | 重放移动、检查速度和碰撞、计算坠落结果、接受或纠正位置 |
| 环境伤害 | 服务端 | 通过位置和落地报告提供运动结果 | 根据服务端实体与世界状态计算窒息、溺水、燃烧、岩浆、冰冻、世界边界和坠落伤害 |

因此，Shadow 不能只等待服务端推进玩家位置。它必须替代 Vanilla 客户端的运动积分和移动上报。它不应重新计算最终伤害值。

### 服务端玩家 tick 分成两条路径

`ServerLevel` 对全部实体运行世界 tick。玩家不会因为区块距离而跳过。`ServerLevel.tickNonPassenger()` 调用实体的 `tick()`。

- `ServerLevel.java:425-447` 遍历实体。第 431 行总是允许 `ServerPlayer` 进入实体 tick。第 442 行调用 `tickNonPassenger()`。
- `ServerLevel.java:819-826` 更新旧位置和 tick 计数，然后调用 `entity.tick()`。
- `ServerPlayer.java:573-609` 的 `tick()` 处理加载超时、游戏模式、容器、相机、统计和属性。该方法没有调用 `super.tick()`。

连接 tick 另行调用完整玩家实体 tick。

- `ServerGamePacketListenerImpl.java:293-310` 在每个连接 tick 中调用 `tickPlayer()`。
- `ServerGamePacketListenerImpl.java:313-320` 先保存位置，再调用 `ServerPlayer.doTick()`，最后把玩家恢复到保存的位置。
- `ServerPlayer.java:640-678` 的 `doTick()` 调用 `super.tick()`。它随后更新食物、统计和生命同步。
- `LivingEntity.java:2730-2771` 的 `tick()` 调用 `Entity.tick()`，然后调用 `aiStep()`。
- `LivingEntity.java:3003-3137` 的 `aiStep()` 处理输入、跳跃、`travel()`、方块效果、冰冻、实体推挤和水敏感伤害。

服务端确实运行共享的实体和环境逻辑。网络层随后有意撤销该 tick 产生的位置。其他状态不会被统一撤销。生命、速度、火焰、空气、效果、食物和死亡仍由服务端实体保留。

### `firstGood` 是本 tick 的已确认起点

`firstGood` 不是长期预测位置。它是连接在当前服务端 tick 开始时记录的玩家位置。

- `ServerGamePacketListenerImpl.java:375-381` 的 `resetPosition()` 同时设置 `firstGood` 和 `lastGood`。
- `ServerGamePacketListenerImpl.java:313-319` 在 `doTick()` 前调用 `resetPosition()`，并在 `doTick()` 后调用 `absSnapTo(firstGoodX, firstGoodY, firstGoodZ, ...)`。
- `ServerGamePacketListenerImpl.java:1077-1081` 用客户端目标位置减 `firstGood`，再与服务端当前速度比较。
- `ServerGamePacketListenerImpl.java:1106-1116` 用目标位置减 `lastGood`，再通过 `Entity.move(MoverType.PLAYER, ...)` 重放客户端移动。

该恢复动作建立了明确边界：服务端 tick 可以更新玩家状态和预期速度，但在线玩家的最终位移必须来自客户端移动包。否则服务端自身的重力、击退或推挤位移不会成为已确认位置。

### 客户端运动结果如何变成服务端位置

Vanilla 客户端在本地玩家 tick 中运行实体物理。它按变化选择位置和旋转移动包。

- `LocalPlayer.java:228-260` 的 `tick()` 运行本地玩家更新，并在连接可用时调用 `sendPosition()`。
- `LocalPlayer.java:264-286` 的 `sendPosition()` 根据位置、旋转、落地和水平碰撞变化发送 `ServerboundMovePlayerPacket.PosRot`、`Pos`、`Rot` 或 `StatusOnly`。
- `LivingEntity.java:2402-2534` 的 `travel()` 包含空气、重力、阻力、水和岩浆运动。
- `Entity.java:712-843` 的 `move()` 解析碰撞、落地、坠落、方块速度因子和反弹。

服务端收到移动包后执行以下步骤：

1. `handleMovePlayer()` 检查 NaN 和无穷值。见 `ServerGamePacketListenerImpl.java:1050-1054`。
2. 它处理待确认传送和客户端加载门控。见 `ServerGamePacketListenerImpl.java:1061-1069`。
3. 它用 `firstGood`、服务端速度和本 tick 包数检查移动过快。见 `ServerGamePacketListenerImpl.java:1077-1103`。
4. 它从 `lastGood` 计算增量，并调用服务端 `Entity.move()`。见 `ServerGamePacketListenerImpl.java:1106-1116`。
5. 它比较重放位置和客户端目标位置。它也检查新碰撞。见 `ServerGamePacketListenerImpl.java:1117-1140` 和 `1216-1227`。
6. 合法时，它接受目标位置，更新落地状态，计算坠落伤害，并更新 `lastGood`。见 `ServerGamePacketListenerImpl.java:1141-1173`。
7. 非法时，它传送玩家回移动前位置。见 `ServerGamePacketListenerImpl.java:1174-1177`。

`ServerboundClientTickEndPacket` 不推进玩家物理。它只在本 tick 没有移动包时把 `knownMovement` 设为零，并清除本 tick 标记。见 `ServerGamePacketListenerImpl.java:2153-2168`。

### 伤害和生命由服务端计算

玩家伤害链全部在服务端实体上运行。

- `ServerPlayer.java:971-982` 检查服务端玩家的无敌状态、PVP 和箭矢所有者，然后委托父类。
- `Player.java:678-707` 应用难度、玩家能力和死亡检查，然后委托 `LivingEntity`。
- `LivingEntity.java:1166-1277` 处理免疫、盾牌、头盔、无敌帧、伤害事件、默认击退、图腾和死亡。
- `LivingEntity.java:1938-1955` 处理护甲、魔法减伤、吸收和生命扣除。
- `Player.java:747-768` 为玩家处理同类减伤、吸收、饥饿消耗、生命扣除和统计。
- `ServerPlayer.java:672-678` 在生命、食物或饱和度变化后发送 `ClientboundSetHealthPacket`。

Shadow 应把 `ClientboundSetHealthPacket` 和死亡相关包当成服务端结果。它不需要用本地护甲、附魔和伤害公式重新判定生命值。

### 普通击退由服务端产生初始速度

普通受击路径如下：

1. `LivingEntity.hurtServer()` 接受一次有效伤害。见 `LivingEntity.java:1166-1221`。
2. 它调用 `markHurt()`，然后调用 `dealDefaultKnockback()`。见 `LivingEntity.java:1233-1239`。
3. `dealDefaultKnockback()` 从投射物或伤害源位置求方向。见 `LivingEntity.java:1280-1295`。
4. `LivingEntity.knockback()` 应用击退抗性和当前速度。它设置新的 `deltaMovement`。见 `LivingEntity.java:1628-1645`。
5. `ServerEntity.sendChanges()` 在 `hurtMarked` 时向跟踪者和玩家本人发送 `ClientboundSetEntityMotionPacket`。见 `ServerEntity.java:220-224`。
6. 客户端 `ClientPacketListener.handleSetEntityMotion()` 查找实体并调用 `lerpMotion(packet.movement())`。见 `ClientPacketListener.java:624-629`。

服务端拥有击退初始速度。客户端拥有该速度之后每 tick 的重力、阻力、碰撞和位置上报。服务端再验证这些位置。

### 爆炸击退也采用服务端向量和客户端积分

- `ServerLevel.java:1204-1235` 创建并执行服务端爆炸。它为每个玩家读取 `ServerExplosion.getHitPlayers()` 的击退向量，并发送 `ClientboundExplodePacket`。
- `ClientPacketListener.java:1353-1370` 处理爆炸结果，并把 `playerKnockback` 加到本地玩家速度。

因此，Shadow 应加上爆炸包中的玩家击退向量。它不应从爆炸半径和方块遮挡重新计算该向量。

### 环境效果和碰撞的所有权

服务端运行环境伤害。位置相关的触发仍依赖已接受的玩家位置和客户端落地报告。

- `LivingEntity.java:409-455` 处理窒息、世界边界和溺水伤害。
- `Entity.java:511-620` 处理火焰和岩浆伤害。
- `LivingEntity.java:3111-3137` 处理冰冻、实体推挤和水敏感伤害。
- `ServerGamePacketListenerImpl.java:1153-1156` 用已接受的客户端移动更新落地状态，并调用 `doCheckFallDamage()`。
- `Entity.java:1540-1557` 根据移动增量和落地状态运行坠落检查。

碰撞查询由世界接口和实体运动共同完成。

- `CollisionGetter.java:47-70` 的 `noCollision()` 组合方块碰撞、世界边界和实体碰撞。
- `CollisionGetter.java:82-105` 提供实体碰撞和方块碰撞集合。
- `Entity.java:712-843` 的 `move()` 使用这些碰撞形状解析实际增量。
- `ServerGamePacketListenerImpl.java:1216-1227` 额外拒绝只在目标位置出现的新碰撞。

服务端拥有合法性判定和环境伤害结果。客户端需要拥有足够的世界数据，才能提出与 Vanilla 相同的正常移动。缺少客户端物理不会让服务端替代客户端持续发送坠落或击退后的路径。

### 对 Shadow 设计的直接影响

Shadow 的最小职责应按服务端边界划分：

- 接受服务端生命、伤害、死亡、速度和爆炸结果。
- 不重算伤害、护甲、无敌帧或爆炸遮挡。
- 对服务端给出的速度运行本地 20 TPS 玩家运动。
- 使用本地 Chunk、方块形状、流体和必要实体状态处理重力、阻力和碰撞。
- 发送 Vanilla 形状的移动包，包括 `onGround` 和 `horizontalCollision`。
- 接受服务端传送纠正，并从纠正位置重置本地运动基线。
- 发送 `ServerboundClientTickEndPacket` 只用于结束客户端 tick。不要把它当成物理输入或保活替代。

只维护生命包和速度包无法得到正常表现。服务端会保留击退速度和伤害结果，但不会替 Shadow 生成后续每 tick 的客户端位置。

## Files Found

- `E:/Gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2-sources.jar` - Minecraft 26.2 官方命名客户端和服务端源码。
- `net/minecraft/server/network/ServerGamePacketListenerImpl.java` - 在线玩家 tick、移动接收、移动校验、位置纠正和客户端 tick 结束处理。
- `net/minecraft/server/level/ServerPlayer.java` - 服务端玩家状态 tick、生命同步、PVP 门控和食物状态。
- `net/minecraft/server/level/ServerLevel.java` - 世界实体 tick、伤害事件和爆炸结果发送。
- `net/minecraft/server/level/ServerEntity.java` - 实体速度和运动同步。
- `net/minecraft/world/entity/LivingEntity.java` - 伤害、击退、环境效果和共享运动实现。
- `net/minecraft/world/entity/player/Player.java` - 玩家伤害、护甲、吸收、食物和玩家运动特例。
- `net/minecraft/world/entity/Entity.java` - 碰撞解析、落地、坠落和方块运动效果。
- `net/minecraft/world/level/CollisionGetter.java` - 方块、实体和世界边界碰撞查询。
- `net/minecraft/client/player/LocalPlayer.java` - 本地玩家 tick 和移动包选择。
- `net/minecraft/client/multiplayer/ClientPacketListener.java` - 速度包、爆炸包和服务端位置结果处理。

## External References

- 未使用网络资料。
- 版本固定为 Minecraft Java 26.2。
- 结论来自本地 Fabric Loom 生成的官方命名 26.2 源码包。

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md` - 固定 Minecraft 26.2 和 Plugin、Patch 边界。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/vanilla-damage-physics.md` - 伤害、外力和物理状态的前序研究。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/vanilla-idle-state.md` - 最低协议响应和移动包行为。

## Caveats / Not Found

- 该结论适用于 Minecraft Java 26.2。旧版本的常数、字段和移动校验细节可能不同。
- 服务端和客户端共享大量实体物理代码，但运行条件不同。不能只因类名相同就认定两端拥有相同的最终位置权威。
- 本报告没有证明所有边缘运动模式。鞘翅、游泳姿态、载具、活塞、粘液块、梯子和效果组合仍需在对应功能进入初版范围时逐项核对。
- `firstGood` 的设计意图是根据调用关系推断。源码没有单独注释解释恢复原因。调用顺序和 `handleMovePlayer()` 的接受流程直接证明了实际行为。

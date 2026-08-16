# Research: Vanilla Java 26.2 玩家物理责任边界

- Query: Vanilla Java 26.2 中，客户端和服务端分别判断哪些玩家物理
- Scope: internal
- Date: 2026-08-13

## Findings

### 核心结论

Vanilla 不把玩家物理完整交给一端。
客户端和服务端都调用共享的 `Entity`、`LivingEntity` 和 `Player` 物理代码。

客户端负责本地玩家的逐 tick 移动结果。
客户端把位置、旋转、`onGround` 和 `horizontalCollision` 发给服务端。

服务端负责伤害、外力来源、环境规则和最终状态。
服务端重新执行碰撞，并校验客户端报告的移动。

服务端每 tick 也运行 `ServerPlayer.doTick()`。
但是 `ServerGamePacketListenerImpl.tickPlayer()` 随后把普通玩家恢复到 `firstGood` 位置。
因此，服务端 tick 不会代替客户端持续完成普通玩家的移动轨迹。

### 权威模型

`Player.isClientAuthoritative()` 固定返回 `true`。
服务端玩家和本地客户端玩家都能运行共享移动代码。
远端客户端玩家不运行自主移动模拟。

`LocalPlayer.tick()` 先运行共享玩家 tick。
它随后调用 `sendPosition()` 发送移动结果。
静止时，客户端仍每 20 tick 发送一次位置。

服务端收到 `ServerboundMovePlayerPacket` 后重新执行 `player.move()`。
服务端检查速度上限、新碰撞、错误移动、悬空和落地状态。
服务端接受结果，或用 Teleport 纠正客户端。

证据：

- `Player.java:1256-1277`：玩家的客户端权威标志和移动模拟条件。
- `LocalPlayer.java:228-299`：客户端玩家 tick 和移动 Packet 发送规则。
- `ServerGamePacketListenerImpl.java:313-320`：服务端 tick 后恢复 `firstGood` 位置。
- `ServerGamePacketListenerImpl.java:1050-1177`：服务端移动检查、碰撞复算和结果接收。

### 场景责任矩阵

| 场景 | 客户端责任 | 服务端责任 | 最终权威 |
| --- | --- | --- | --- |
| 普通击退 | 接收速度，逐 tick 应用重力、阻力和碰撞，发送位置 | 结算伤害、抗性和击退向量，发送速度，校验位置 | 服务端决定外力和接受位置 |
| 爆炸 | 把 `playerKnockback` 加到当前速度，继续移动模拟 | 计算爆炸、伤害、方块变化和玩家击退向量 | 服务端决定爆炸结果 |
| 重力 | 逐 tick 计算本地玩家下落，并发送位置 | 运行同一公式作状态处理和移动检查，但回滚普通 tick 位移 | 客户端提出轨迹，服务端接受 |
| 落地 | 计算碰撞和 `onGround`，发送状态 | 复算碰撞，累计坠落距离，结算摔落伤害 | 服务端决定伤害和有效位置 |
| 墙面碰撞 | 用本地区块碰撞体裁剪位移 | 用服务端世界复算碰撞，并拒绝新增穿墙 | 服务端决定有效位置 |
| 流体运动 | 计算水流、岩浆流、阻力、浮沉和出水运动 | 运行流体状态，检查位置，并结算环境伤害 | 客户端提出轨迹，服务端决定状态 |
| 实体推挤 | 用已同步实体预测推力，并继续位置积分 | 对实体执行推挤，产生速度变化，并检查玩家位置 | 服务端决定实体状态和有效位置 |
| 活塞 | 客户端世界也移动碰撞实体，保持画面和输入轨迹一致 | 移动活塞方块实体可直接调用 `entity.move(PISTON, ...)` | 服务端可直接改变位置 |
| 摔落伤害 | 跟踪坠落和落地，用于移动表现 | 在接受移动后调用 `doCheckFallDamage()` 并扣血 | 服务端 |
| 火和岩浆 | 跟踪接触、燃烧表现和后续移动 | 在 `ServerLevel` tick 中结算燃烧和岩浆伤害 | 服务端 |
| 溺水 | 跟踪入水和眼睛位置，用于移动和显示 | 维护空气值并结算溺水伤害 | 服务端 |
| 死亡 | 接收生命和死亡 Packet，显示死亡状态，发重生请求 | 判定死亡、掉落、统计、广播死亡并进入未加载状态 | 服务端 |
| 移动反作弊 | 不负责裁决 | 检查过快移动、错误移动、碰撞、悬空和非法数值 | 服务端 |

### 普通击退

服务端在 `LivingEntity.hurtServer()` 中结算伤害。
服务端随后调用 `dealDefaultKnockback()` 和 `knockback()`。
该计算包含击退抗性、伤害来源方向和原速度。

服务端设置 `hurtMarked`。
`ServerEntity.sendChanges()` 向玩家本人发送 `ClientboundSetEntityMotionPacket`。

客户端调用 `handleSetEntityMotion()` 写入速度。
本地玩家随后用共享物理代码完成轨迹。

所以服务端决定“给多少速度”。
客户端决定“这份速度在每个 tick 后到达哪里”。
服务端再检查这些位置是否有效。

证据：

- `LivingEntity.java:1166-1254`：伤害、击退入口和死亡判断。
- `LivingEntity.java:1628-1645`：击退速度公式。
- `ServerEntity.java:221-224`：向玩家本人同步受击速度。
- `ClientPacketListener.java:624-629`：客户端应用实体速度。

### 爆炸

服务端计算爆炸和玩家击退。
`ClientboundExplodePacket` 单独携带 `playerKnockback`。

客户端 `handleExplosion()` 把该向量加到本地玩家速度。
客户端再执行重力、阻力、流体和碰撞。

爆炸不是客户端重新计算的结果。
客户端只消费服务端给出的玩家击退向量。

证据：`ClientPacketListener.java:1353-1370`。

### 重力、墙面和落地

`LivingEntity.travel()` 选择空气、流体或鞘翅移动。
`travelInAir()` 计算重力、方块摩擦和空气阻力。
`Entity.move()` 用碰撞形状裁剪位移。

这些方法属于共享代码。
本地客户端玩家和服务端玩家都会调用它们。

对普通玩家，客户端持续提交计算后的位置。
服务端在 `handleMovePlayer()` 中按服务端世界复算本次位移。
服务端还检查 `onGround`、`horizontalCollision` 和新增碰撞。

摔落伤害由服务端结算。
服务端只在接受移动时使用客户端本次位移更新坠落状态。

证据：

- `LivingEntity.java:2402-2534`：空气、流体、重力和阻力。
- `Entity.java:712-820`：移动碰撞、落地标志和碰撞后速度。
- `Entity.java:1540-1562`：坠落距离和落地处理。
- `ServerGamePacketListenerImpl.java:1154-1177`：服务端落地和摔落检查。

### 流体

客户端和服务端都更新流体接触状态。
共享代码计算水流、岩浆流、阻力和重力调整。

客户端用该结果推进本地玩家位置。
服务端用自己的世界状态检查收到的位置。

服务端负责岩浆和溺水伤害。
这些伤害只在 `ServerLevel` 分支中执行。

证据：

- `Entity.java:511-556`：流体接触、燃烧、岩浆和虚空状态。
- `Entity.java:1637-1660`：水流和岩浆流加速度。
- `LivingEntity.java:422-445`：窒息和溺水伤害。
- `LivingEntity.java:2474-2534`：水和岩浆移动公式。

### 实体推挤

`LivingEntity.pushEntities()` 在两端的实体 tick 中执行。
`Entity.push()` 把推力写入双方速度。

服务端拥有真实实体集合和游戏规则。
服务端也负责实体挤压伤害。

客户端只能使用已同步的实体状态预测本地玩家移动。
客户端仍需把推挤后的玩家位置发给服务端。

证据：

- `LivingEntity.java:3127-3213`：实体推挤和服务端挤压伤害。
- `Entity.java:1863-1908`：实体间推力和速度同步标志。

### 活塞

移动活塞方块实体在两端都运行碰撞逻辑。
`moveCollidedEntities()` 直接调用 `entity.move(MoverType.PISTON, ...)`。

所以活塞不是纯客户端预测。
服务端可以直接移动玩家。

客户端仍需运行相同活塞碰撞。
否则客户端后续移动会从错误位置开始，并收到服务端纠正。

证据：

- `PistonMovingBlockEntity.java:120-195`：活塞选择实体并直接移动实体。
- `Entity.java:712-725`：`MoverType.PISTON` 的专用移动限制。

### 火、岩浆、溺水和死亡

客户端不决定实际生命值。
服务端在 `ServerLevel` tick 中计算环境伤害。

服务端通过 `ClientboundSetHealthPacket` 同步生命、食物和饱和度。
服务端死亡逻辑发送 `ClientboundPlayerCombatKillPacket`。

客户端只更新显示状态和死亡界面。
重生需要客户端发送重生请求，但服务端决定重生结果。

证据：

- `Entity.java:511-556`：服务端燃烧和岩浆伤害。
- `LivingEntity.java:422-455`：服务端窒息和溺水伤害。
- `ServerPlayer.java:672-678`：服务端生命同步。
- `ServerPlayer.java:869-929`：服务端死亡处理。

### 移动校验

服务端是移动校验的唯一裁决端。
`handleMovePlayer()` 拒绝非有限值。

服务端比较报告位移和当前速度。
服务端限制每个服务端 tick 的 Packet 数量。
服务端重新执行碰撞，并检查新增实体碰撞。

服务端还计算 `clientIsFloating`。
持续悬空可触发 Flying Kick。

该逻辑是 Vanilla 的基础移动校验。
第三方反作弊可以增加其他规则。

### 客户端不发送 MovePlayer 时

连接不会仅因缺少 MovePlayer 立即关闭。
KeepAlive 仍可维持网络连接。

服务端每 tick 仍调用 `player.doTick()`。
该 tick 可以处理饥饿、效果、火焰、溺水和当前方块伤害。

但是 `tickPlayer()` 随后把玩家恢复到 `firstGood` 位置。
所以普通击退、重力、流体和实体推力不会形成连续位置轨迹。

玩家通常停在最后确认位置。
服务端仍可通过 Teleport 或直接服务端移动改变该位置。
活塞属于可能直接移动玩家的特殊路径。

若之前的移动 Packet 已把 `clientIsFloating` 设为 `true`，服务端会继续累计悬空 tick。
这时停止发送 MovePlayer 不能避免 Flying Kick。

若 `clientIsFloating` 为 `false`，没有新 MovePlayer 就不会建立新的正常坠落轨迹。
玩家不会仅因服务端持有向下速度而自然掉下平台。

### 对 Shadow 的直接含义

Shadow 若只要求连接存活，不需要完整客户端物理。
它只需处理协议要求的响应。

Shadow 若要求受击后表现为正常玩家，就必须接替本地客户端责任。
它必须运行零输入的逐 tick 玩家物理。

这至少包括速度、重力、区块碰撞、流体和移动 Packet。
实体推挤需要附近实体状态。
活塞需要移动方块状态。

Shadow 不应重新计算伤害值、死亡结果或反作弊裁决。
这些结果继续以服务端 Packet 和服务端纠正为准。

## Files Found

- `E:/Gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2-sources.jar`：Minecraft Java 26.2 合并源码。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/vanilla-damage-physics.md`：已有伤害与物理研究，供本次独立复核。
- `.trellis/spec/backend/velocity-plugin.md`：当前 Plugin 和 Shadow 边界。
- `.trellis/spec/language/java.md`：项目 Java 规范。

## Code Patterns

- `net/minecraft/world/entity/player/Player.java:1256-1277`：玩家的客户端权威标志。
- `net/minecraft/client/player/LocalPlayer.java:228-299`：客户端移动 tick 和 Packet 发送。
- `net/minecraft/server/network/ServerGamePacketListenerImpl.java:313-320`：服务端普通 tick 位移回滚。
- `net/minecraft/server/network/ServerGamePacketListenerImpl.java:1050-1177`：服务端移动接收和校验。
- `net/minecraft/world/entity/LivingEntity.java:2402-2534`：共享玩家移动公式。
- `net/minecraft/world/entity/Entity.java:712-820`：共享碰撞处理。

## External References

- 未使用网络资料。
- 版本固定为 Minecraft Java 26.2。
- 协议版本为 776。

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`
- `.trellis/spec/language/java.md`

## Caveats / Not Found

- 本报告只描述 Vanilla Java 26.2。
- Paper、插件和反作弊可以修改伤害、速度、移动检查和位置纠正。
- 服务端直接移动可在普通客户端移动闭环之外改变玩家位置。
- 载具使用独立的控制权和 `ServerboundMoveVehiclePacket` 路径。
- 本报告没有扩大到载具、鞘翅或完整方块特殊行为。

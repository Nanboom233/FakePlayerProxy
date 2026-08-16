# Research: Carpet Fake Player 的 Player 计算

- Query: Carpet 26.2 Fake Player 如何创建、替换真实玩家并运行服务端 Player 计算。
- Scope: mixed
- Date: 2026-08-14

## Findings

### 调查基线

本报告检查 Fabric Carpet `v26.2`。

- Commit: `dbedd4c91d4956c38874fe9509c48265f0afacf5`
- Minecraft: `26.2`
- Carpet: `26.2`
- License: MIT

只读 checkout 的 `.git/HEAD:1` 直接记录目标 Commit。

`gradle.properties:8` 固定 `minecraft_version=26.2`。

`gradle.properties:13` 固定 `mod_version = 26.2`。

Vanilla 证据来自该构建使用的官方命名源码 JAR。

源码 JAR 路径如下。

```text
E:/Gradle/caches/fabric-loom/minecraftMaven/net/minecraft/
minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2-sources.jar
```

下文用 `minecraft-26.2-sources.jar!/` 表示该路径。

### 核心结论

Carpet Fake Player 不是无头 Minecraft 客户端。

它也没有独立的 Player 物理引擎。

Carpet 在后端服务器中创建一个 `ServerPlayer` 子类。

它让服务端成为该 Fake Player 的本地权威端。

它直接运行 Minecraft 服务端已有的 Player 计算。

因此，Carpet 不需要保存一份代理侧 World。

它直接读取真实的 `ServerLevel`、Chunk、Block、Fluid 和 Entity。

该方案依赖后端服务器 Mixin。

它不能用于只修改 Velocity 和 Plugin 的当前边界。

### 创建普通 Fake Player

`EntityPlayerMPFake` 直接继承 `ServerPlayer`。

证据是 `src/main/java/carpet/patches/EntityPlayerMPFake.java:50`。

`createFake()` 先解析 UUID 和 `GameProfile`。

它异步补全 Profile。

证据是 `EntityPlayerMPFake.java:58-123`。

完成回调创建 `EntityPlayerMPFake`。

它使用默认 `ClientInformation`。

证据是 `EntityPlayerMPFake.java:101`。

它随后执行以下调用。

```java
server.getPlayerList().placeNewPlayer(
    new FakeClientConnection(PacketFlow.SERVERBOUND),
    instance,
    new CommonListenerCookie(current, 0, instance.clientInformation(), false));
```

证据是 `EntityPlayerMPFake.java:103`。

Vanilla `PlayerList.placeNewPlayer()` 把 Player 加入列表、UUID 索引和 `ServerLevel`。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/server/players/PlayerList.java:145-216`。

Carpet 随后加载玩家存档。

它加载 Player 数据、末影珍珠和父载具。

证据是 `EntityPlayerMPFake.java:125-135`。

普通创建路径立即调用 `stopRiding()`。

它不会保留存档中的父载具关系。

证据是 `EntityPlayerMPFake.java:104-106`。

### `/player shadow` 替换真实玩家

`/player <name> shadow` 拒绝 Fake Player。

它也拒绝单人服务器所有者。

证据是 `src/main/java/carpet/commands/PlayerCommand.java:344-359`。

`createShadow()` 先断开真实玩家。

断开原因是 `multiplayer.disconnect.duplicate_login`。

证据是 `EntityPlayerMPFake.java:138-140`。

Vanilla 断开路径安排 `Connection.handleDisconnection()`。

原连接关闭后，该调用进入 `PlayerList.remove()`。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/server/network/ServerCommonPacketListenerImpl.java:177-185`。

原连接关闭后，Vanilla Listener 从世界和 Player 列表移除真实玩家。

源码不保证该移除在新 Fake Player 注册前完成。

所以旧对象和新对象可能在关闭回调前短暂重叠。

Carpet 随后创建新的 `EntityPlayerMPFake`。

新对象使用原玩家的 `GameProfile` 和 `ClientInformation`。

证据是 `EntityPlayerMPFake.java:141-145`。

`placeNewPlayer()` 使用新的 `FakeClientConnection` 注册新对象。

证据是 `EntityPlayerMPFake.java:145`。

Carpet 从后端玩家存档加载数据。

它再复制下列运行时状态。

- Chat Session
- 生命
- 位置和旋转
- Game Mode
- `EntityPlayerActionPack`
- Model Customization
- Flying 状态

证据是 `EntityPlayerMPFake.java:144-160`。

`createShadow()` 没有显式复制当前 `deltaMovement`。

存档加载可能恢复存档中的 Motion。

源码不能证明新对象保留断开瞬间的 `deltaMovement`。

Carpet 没有保留原 `ServerPlayer`。

它也没有保留原网络连接。

这与 FakePlayerProxy 保留同一后端连接的设计不同。

### Connection 和 Listener 的精确构造

Carpet 使用 `FakeClientConnection`。

该类继承 Minecraft `Connection`。

构造函数先调用 `Connection(PacketFlow.SERVERBOUND)`。

它再通过 Mixin 接口安装 Netty `EmbeddedChannel`。

证据是 `src/main/java/carpet/patches/FakeClientConnection.java:14-22`。

`EmbeddedChannel` 让 `Connection.isConnected()` 返回 `true`。

Vanilla 判断位于 `minecraft-26.2-sources.jar!/net/minecraft/network/Connection.java:519-520`。

`FakeClientConnection.send()` 是空操作。

`handleDisconnection()` 是空操作。

`setListenerForServerboundHandshake()` 是空操作。

`setupInboundProtocol()` 也是空操作。

证据是 `FakeClientConnection.java:24-47`。

Vanilla `setupInboundProtocol()` 会保存 `packetListener`。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/network/Connection.java:196-203`。

`PlayerList.placeNewPlayer()` 通常创建 `ServerGamePacketListenerImpl`。

它随后调用 `Connection.setupInboundProtocol()`。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/server/players/PlayerList.java:157-160`。

Carpet 在该构造点执行 Redirect。

Fake Player 改用 `NetHandlerPlayServerFake`。

真实 Player 仍使用 Vanilla Listener。

证据是 `src/main/java/carpet/mixins/PlayerList_fakePlayersMixin.java:45-55`。

`NetHandlerPlayServerFake` 构造函数调用父类构造函数。

父类构造函数把自身写入 `player.connection`。

证据是 `src/main/java/carpet/patches/NetHandlerPlayServerFake.java:14-19`。

父类写入点位于 `ServerGamePacketListenerImpl.java:279-290`。

Fake Connection 的 `setupInboundProtocol()` 不保存该 Listener。

因此，`Connection.packetListener` 不通过正常路径得到该 Listener。

Player 仍可通过 `player.connection` 直接调用 Fake Listener。

### Fake Connection 不进入网络 tick 列表

`ServerConnectionListener.connections` 是服务器网络 tick 列表。

Vanilla 在三个 Channel 初始化路径中加入 `Connection`。

这些路径是 TCP、内存 Channel 和 `acceptChannel()`。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/server/network/ServerConnectionListener.java:47-48`。

加入点位于同文件 `74`、`90` 和 `117` 行。

`ServerConnectionListener.tick()` 只遍历该列表。

它对每个连接调用 `Connection.tick()`。

证据是同文件 `154-187` 行。

Carpet 的两个 Fake Connection 创建点只调用 `PlayerList.placeNewPlayer()`。

证据是 `EntityPlayerMPFake.java:103` 和 `145`。

固定 checkout 的完整引用搜索只找到这两个创建点。

Carpet 没有调用 `ServerConnectionListener.getConnections().add()`。

`PlayerList.placeNewPlayer()` 也不加入网络 tick 列表。

所以 `FakeClientConnection` 不进入服务器网络 tick 列表。

这项结论现在有直接构造路径和列表写入点证据。

它不再只是引用缺失推断。

### Entity tick 和 `doTick()` 路径

正常运行的 `ServerLevel` 遍历 `entityTickList`。

`ServerPlayer` 不受实体 Chunk 距离条件限制。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/server/level/ServerLevel.java:425-443`。

`ServerLevel.tickNonPassenger()` 调用实体的 `tick()`。

证据是同文件 `819-830` 行。

Fake Player 因此进入 `EntityPlayerMPFake.tick()`。

该方法每 10 个服务器 tick 重置连接位置基准。

它也更新 Chunk 跟踪位置。

证据是 `EntityPlayerMPFake.java:205-212`。

该方法随后依次调用 `super.tick()` 和 `this.doTick()`。

证据是 `EntityPlayerMPFake.java:213-217`。

精确主路径如下。

```text
ServerLevel.tick()
-> ServerLevel.tickNonPassenger()
-> EntityPlayerMPFake.tick()
-> ServerPlayer.tick()
-> EntityPlayerMPFake.doTick()
-> ServerPlayer.doTick()
-> Player.tick()
-> LivingEntity.tick()
-> Player.aiStep()
-> LivingEntity.aiStep()
-> Player.travel()
-> LivingEntity.travel()
-> Entity.move()
```

`ServerPlayer.tick()` 处理连接超时、Game Mode、菜单和服务端状态。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/server/level/ServerPlayer.java:573-609`。

`ServerPlayer.doTick()` 在条件允许时调用 `super.tick()`。

证据是同文件 `640-663` 行。

`Player.tick()` 调用 `LivingEntity.tick()`。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/world/entity/player/Player.java:232-259`。

`LivingEntity.tick()` 调用动态分派的 `Player.aiStep()`。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/world/entity/LivingEntity.java:2730-2771`。

`Player.aiStep()` 调用 `LivingEntity.aiStep()`。

证据是 `Player.java:441-452`。

`LivingEntity.aiStep()` 读取输入并执行 `travel()`。

它随后调用 `pushEntities()`。

证据是 `LivingEntity.java:3043-3134`。

### 为什么服务端移动不会被 `firstGood` 回滚

普通在线玩家有两条 tick 路径。

`ServerLevel` 调用 `ServerPlayer.tick()`。

网络列表还调用 `Connection.tick()`。

该调用进入 `ServerGamePacketListenerImpl.tick()`。

证据是 `Connection.java:344-348` 和 `ServerGamePacketListenerImpl.java:292-310`。

普通 Listener 的 `tickPlayer()` 先执行下列步骤。

```text
resetPosition()
player.doTick()
player.absSnapTo(firstGoodX, firstGoodY, firstGoodZ, ...)
```

证据是 `ServerGamePacketListenerImpl.java:313-320`。

该回写保留客户端对普通在线 Player 位置的控制。

Fake Connection 不进入网络 tick 列表。

所以 Fake Listener 不执行 `tickPlayer()`。

Fake Player 只通过 `EntityPlayerMPFake.tick()` 额外执行 `doTick()`。

该调用之后没有 `firstGood` 回写。

因此，服务端 `travel()` 和 `move()` 产生的位置会保留。

### 移动权威

Vanilla `Player.isClientAuthoritative()` 固定返回 `true`。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/world/entity/player/Player.java:1255-1258`。

普通服务端 Player 的 `isLocalInstanceAuthoritative()` 因此返回 `false`。

Vanilla 公式位于 `Entity.java:3562-3574`。

Carpet 在 `isLocalInstanceAuthoritative()` 方法头注入结果。

服务器中的 `EntityPlayerMPFake` 返回 `true`。

由 Fake Player 控制的载具也返回 `true`。

证据是 `src/main/java/carpet/mixins/EntityMixin.java:38-43`。

该改写直接影响 `Entity.move()` 中的本地权威分支。

它在零垂直位移时也更新垂直碰撞和 `onGround`。

它也允许服务端执行 Fall Damage 检查。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/world/entity/Entity.java:760-780`。

旧报告把 Movement Emission 列为该 Mixin 的影响。

该表述不准确。

服务端本来就执行 Movement Emission。

证据是 `Entity.java:789-793` 中的 `!level.isClientSide()` 分支。

Vanilla `Player.canSimulateMovement()` 在服务端本来就返回 `true`。

`Player.isEffectiveAi()` 在服务端也返回 `true`。

证据是 `Player.java:1269-1276`。

所以 Carpet Mixin 不负责开启 Fake Player 的基础 `travel()`。

它主要补全本地权威状态和载具权威。

### 已知移动和已知速度

`ServerPlayer.getKnownMovement()` 默认返回 `lastKnownClientMovement`。

`ServerPlayer.getKnownSpeed()` 也默认返回该字段。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/server/level/ServerPlayer.java:2165-2178`。

Fake Player 没有真实客户端移动输入。

Carpet 替换两个方法中的字段读取结果。

它对 Fake Player 返回 `super.getKnownMovement()`。

证据是 `src/main/java/carpet/mixins/ServerPlayer_fakeLastMovementMixin.java:25-32`。

父类实现通常返回 `deltaMovement`。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/world/entity/Entity.java:3991-3996`。

因此，Fake Player 的两个查询通常都读取服务端实体移动。

该结果影响依赖已知移动的判定。

一个直接使用点是 Player 扫击判定。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/world/entity/player/Player.java:1035-1037`。

### Action 输入

Carpet 不为 Fake Player 生成 Move Player Packet。

`ServerPlayer` 构造完成后，Mixin 创建 `EntityPlayerActionPack`。

证据是 `src/main/java/carpet/mixins/ServerPlayer_actionPackMixin.java:27-31`。

该 Mixin 在 `ServerPlayer.tick()` 方法头调用 `actionPack.onUpdate()`。

证据是同文件 `33-37` 行。

所以动作更新发生在 Fake Player 的 `doTick()` 之前。

Action Pack 直接写服务端 Player 状态。

```text
forward -> player.zza
strafe -> player.xxa
sneak -> player.setShiftKeyDown()
sprint -> player.setSprinting()
jump -> player.jumpFromGround() 或 player.setJumping()
```

证据是 `src/main/java/carpet/helpers/EntityPlayerActionPack.java:86-111`。

移动字段写入位于同文件 `241-248` 行。

Fake Player 即使输入为零也会覆盖 `zza` 和 `xxa`。

真实 Player 只在非零输入时被 Action Pack 覆盖。

这防止 Action Pack 清除真实客户端输入。

攻击通过射线查询真实 `ServerLevel`。

实体攻击直接调用 `player.attack()`。

方块破坏直接调用 `ServerPlayerGameMode.handleBlockBreakAction()`。

证据是 `EntityPlayerActionPack.java:369-460`。

Use 直接调用服务端方块、实体和物品 API。

证据是同文件 `300-358` 行。

Drop 和 Swap Hands 也直接改服务端状态。

证据是同文件 `487-515` 行。

Carpet 不模拟这些动作的客户端 Packet 流程。

### 玩家攻击附加击退

旧报告把该 Mixin 描述为普通击退处理。

该范围过宽。

Mixin 只修改 `Player.causeExtraKnockback()`。

证据是 `src/main/java/carpet/mixins/Player_fakePlayersMixin.java:16-27`。

Vanilla 玩家攻击先保存目标的旧 `deltaMovement`。

攻击过程可应用伤害击退和附加击退。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/world/entity/player/Player.java:957-981`。

若目标是 `ServerPlayer` 且 `hurtMarked` 为真，Vanilla 执行三步。

它发送 `ClientboundSetEntityMotionPacket`。

它清除 `hurtMarked`。

它恢复旧 `deltaMovement`。

证据是 `Player.java:1136-1140`。

Carpet 对 Fake Player 让该条件返回 `false`。

所以该分支不发送、不清标记，也不恢复旧速度。

Fake Player 保留服务端攻击计算后的 `deltaMovement`。

后续 `doTick()` 使用该速度推进位置。

其他伤害来源的基础击退来自 Vanilla `LivingEntity.hurtServer()`。

它调用 `dealDefaultKnockback()` 和 `knockback()`。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/world/entity/LivingEntity.java:1166-1295`。

Carpet 没有为该基础路径增加另一个 Fake Player Mixin。

### 爆炸

`ServerExplosion` 计算伤害、暴露度和击退抗性。

它对命中实体调用 `entity.push(knockback)`。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/world/level/ServerExplosion.java:171-206`。

`Entity.push()` 把冲量加到 `deltaMovement`。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/world/entity/Entity.java:1894-1904`。

Vanilla 还为 Player 记录该击退。

`ServerLevel` 把记录放进 `ClientboundExplodePacket`。

证据是 `ServerLevel.java:1227-1235`。

Fake Connection 丢弃该 Packet。

Fake Player 已经保留服务端 `push()` 写入的速度。

后续 `doTick()` 使用该速度。

Carpet 没有 Fake Player 专用爆炸 Mixin。

这项行为来自 Vanilla `ServerExplosion` 和 Fake Player tick 架构。

### 世界、碰撞和实体推挤

Carpet 不建立第二份 World。

`ServerPlayer.doTick()` 直接运行在真实 `ServerLevel` 中。

`LivingEntity.travel()` 和 `Entity.move()` 使用该 World。

它们可读取下列服务器状态。

- 完整 Chunk
- Block State
- Voxel Shape
- Fluid State
- World Border
- Entity 集合
- Moving Piston
- Attribute 和 Effect

`Entity.move()` 执行碰撞、台阶、落地和方块速度处理。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/world/entity/Entity.java:712-798`。

`LivingEntity.aiStep()` 在移动后调用 `pushEntities()`。

证据是 `LivingEntity.java:3091-3134`。

所以 Fake Player 直接查询服务端实体集合。

它不需要代理侧 Entity Tracker 来计算实体推挤。

### 活塞和黏液块

Vanilla Moving Piston 直接调用 `Entity.move(MoverType.PISTON, ...)`。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:120-198`。

该路径本来就会移动 `ServerPlayer`。

Vanilla 黏液块弹射明确跳过全部 `ServerPlayer`。

证据是同文件 `129-163` 行。

Carpet Redirect 读取 `getPistonPushReaction()`。

若实体是 Fake Player 且移动方块是黏液块，它设置对应轴速度为方向步长。

证据是 `src/main/java/carpet/mixins/PistonMovingBlockEntity_playerHandlingMixin.java:36-58`。

该补丁只补黏液块速度。

普通活塞位移仍来自 Vanilla。

同一 Mixin 的 Creative No Clip 分支受独立 Carpet 规则控制。

它不是 Fake Player 核心行为。

### 乘客和载具

`ServerLevel` 不把乘客作为普通实体重复 tick。

它从载具递归调用 `tickPassenger()`。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/server/level/ServerLevel.java:425-447` 和 `828-848`。

乘客 Player 执行 `rideTick()`。

Vanilla `Entity.rideTick()` 清零乘客速度，再调用动态分派的 `tick()`。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/world/entity/Entity.java:2364-2369`。

所以 Fake Player 作为乘客时仍进入 `EntityPlayerMPFake.tick()`。

Carpet 的权威 Mixin 也检查 `getControllingPassenger()`。

由 Fake Player 控制的载具在服务端成为本地权威。

证据是 `src/main/java/carpet/mixins/EntityMixin.java:41-43`。

Vanilla 载具可据此运行服务端移动分支。

例如 Boat 在本地权威时执行浮力和移动。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/world/entity/vehicle/boat/AbstractBoat.java:227-243`。

但 Boat 的 `controlBoat()` 仍只在客户端分支调用。

Carpet 没有为 Fake Player 注入 Paddle Packet。

因此，源码不能证明完整的客户端 Boat 控制等价性。

Carpet 在 Fake Player 登上 Boat 时对齐身体和头部朝向。

证据是 `EntityPlayerMPFake.java:225-238`。

Action Pack 的 `mount()` 查询附近真实实体并直接调用 `startRiding()`。

`dismount()` 直接调用 `stopRiding()`。

证据是 `EntityPlayerActionPack.java:171-210`。

Fake Player 退出或死亡时调用 `shakeOff()`。

它只强制解除 Player 与 Player 的载乘关系。

证据是 `EntityPlayerMPFake.java:240-247`。

### 死亡和 Respawn

Fake Player 的伤害和生命由 Vanilla 服务端计算。

`EntityPlayerMPFake.die()` 先调用 `shakeOff()`。

它再调用 `ServerPlayer.die()`。

证据是 `EntityPlayerMPFake.java:249-256`。

Carpet 随后把生命设为 20。

它重建 `FoodData`。

它最后用死亡消息调用 `kill()`。

`kill()` 安排 Fake Listener 的 `onDisconnect()`。

证据是 `EntityPlayerMPFake.java:192-202` 和 `249-257`。

Vanilla Listener 的 `onDisconnect()` 从世界和 Player 列表移除 Player。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/server/network/ServerGamePacketListenerImpl.java:1427-1441`。

所以普通 Carpet Fake Player 死亡后退出。

它不会走普通客户端死亡界面的 Respawn 流程。

`PlayerList_fakePlayersMixin` 只修改已进入 Vanilla Respawn 的构造点。

它让新对象继续使用 `EntityPlayerMPFake` 类型。

证据是 `src/main/java/carpet/mixins/PlayerList_fakePlayersMixin.java:58-65`。

末地完成是该 Respawn 路径的明确调用点。

`EntityPlayerMPFake.teleport()` 在 `wonGame` 时构造 `PERFORM_RESPAWN` 命令。

它直接调用 Fake Listener 的 `handleClientCommand()`。

证据是 `EntityPlayerMPFake.java:270-284`。

Vanilla Listener 再调用 `PlayerList.respawn()`。

证据是 `minecraft-26.2-sources.jar!/net/minecraft/server/network/ServerGamePacketListenerImpl.java:1850-1869`。

这不是客户端死亡状态机。

它是后端服务器内的 Fake Player 生命周期。

### Carpet 自己实现的行为

| 行为 | Carpet 证据 |
| --- | --- |
| Fake Player 类型 | `EntityPlayerMPFake.java:50` |
| 普通创建和 Shadow 替换 | `EntityPlayerMPFake.java:58-161` |
| 空 Connection | `FakeClientConnection.java:14-47` |
| Fake Listener | `PlayerList_fakePlayersMixin.java:45-55` |
| 每实体 tick 调用 `doTick()` | `EntityPlayerMPFake.java:205-223` |
| 服务端本地权威 | `EntityMixin.java:38-43` |
| Action Pack | `ServerPlayer_actionPackMixin.java:27-37` |
| 已知移动改写 | `ServerPlayer_fakeLastMovementMixin.java:25-32` |
| 玩家攻击速度保留 | `Player_fakePlayersMixin.java:16-27` |
| 黏液活塞速度 | `PistonMovingBlockEntity_playerHandlingMixin.java:36-58` |
| Boat 上车朝向 | `EntityPlayerMPFake.java:225-238` |
| 死亡后退出 | `EntityPlayerMPFake.java:192-202,249-257` |
| Respawn 保留 Fake 类型 | `PlayerList_fakePlayersMixin.java:58-65` |
| 游戏冻结时把 Fake Player 当非真实 Player | `TickRateManager_fakePlayersMixin.java:21-37` |

### Carpet 从 Vanilla 得到的行为

| 行为 | Vanilla 所有者 |
| --- | --- |
| Player 注册和世界加入 | `PlayerList.placeNewPlayer()` |
| 服务端 Player 状态 tick | `ServerPlayer.tick()` |
| Player 实体计算 | `ServerPlayer.doTick()` |
| 输入到移动 | `LivingEntity.aiStep()` |
| 重力、流体、摩擦和飞行 | `Player.travel()` 和 `LivingEntity.travel()` |
| 方块碰撞和台阶 | `Entity.move()` |
| 实体推挤 | `LivingEntity.pushEntities()` |
| 基础伤害和击退 | `LivingEntity.hurtServer()` |
| 爆炸伤害和冲量 | `ServerExplosion` |
| 普通活塞位移 | `PistonMovingBlockEntity` |
| 乘客递归 tick | `ServerLevel.tickPassenger()` |
| Vanilla Respawn 过程 | `PlayerList.respawn()` |

Carpet 没有复制这些算法。

它改变调用条件和权威边界。

### 与 FakePlayerProxy 的固定边界

| 项目 | Carpet Fake Player | FakePlayerProxy Shadow |
| --- | --- | --- |
| 运行位置 | 后端服务器 JVM | Velocity Plugin JVM |
| Player 对象 | 新建 `EntityPlayerMPFake` | 保留原后端 `ServerPlayer` 对应连接 |
| 后端修改 | 必须安装 Carpet Mixin | 后端保持不变 |
| 网络连接 | 新建空 `FakeClientConnection` | 保留真实加密后端连接 |
| Player 计算 | 服务端 `ServerPlayer.doTick()` | Plugin 计算客户端移动 |
| World | 直接使用 `ServerLevel` | 保存后端已发送的 World Packet |
| 移动输出 | 直接修改服务端 Player | 发送 C2S Move Player Packet |
| 玩家攻击击退 | 保留服务端 `deltaMovement` | 接收 S2C Motion 后继续积分 |
| 爆炸 | 保留服务端 `push()` | 累加 S2C Explosion Knockback |
| 实体推挤 | 查询完整服务端 Entity | 依赖已同步 Entity 状态 |

相关固定边界见 `.trellis/spec/backend/velocity-plugin.md`。

该规范要求后端保持不变。

它也要求 Shadow 保留原后端连接。

### 可以迁移到 FakePlayerProxy 的内容

可以迁移行为目标。

- 每 20 TPS 更新动作，再计算移动。
- 零输入时仍运行完整 Player 计算。
- 把服务端 Motion 作为后续移动输入。
- 把 Explosion Knockback 加到当前速度。
- 同一次计算产出位置、速度、`onGround` 和碰撞标志。
- 死亡和伤害继续以服务端为权威。
- Continuous 和 Interval 动作使用稳定 tick 调度。

可以迁移 Packet 能表达的输入。

- Move Player
- Player Input
- Sneak 和 Sprint 命令
- Selected Slot
- Use Item
- Player Action
- Swing
- Teleport Acknowledgement

这些迁移不是复制 Carpet 实现。

Plugin 必须用现有后端连接发送 C2S Packet。

Plugin 还必须从 S2C Packet 重建可见状态。

### 不能迁移到 FakePlayerProxy 的内容

Plugin 不能创建或替换后端 `ServerPlayer`。

Plugin 不能安装 `EntityPlayerMPFake`。

Plugin 不能创建 Fake Connection 或 Fake Listener。

Plugin 不能把原后端连接移出网络 tick 列表。

Plugin 不能跳过 `firstGood` 回写。

Plugin 不能改 `isLocalInstanceAuthoritative()`。

Plugin 不能改 `getKnownMovement()` 或 `getKnownSpeed()`。

Plugin 不能改玩家攻击后的 `deltaMovement` 恢复。

Plugin 不能直接调用 `ServerPlayer.doTick()`。

Plugin 不能直接调用服务端攻击、使用或方块破坏 API。

Plugin 不能查询完整 `ServerLevel`。

Plugin 也看不到未发送的 Chunk 和 Entity 状态。

所以 Plugin 不能获得 Carpet 的精确碰撞和实体推挤结果。

它只能对已同步状态运行版本固定的近似客户端计算。

完整载具控制也不能从 Carpet 直接迁移。

该功能需要对应载具状态、输入 Packet 和载具物理。

### 旧报告推断审计

#### Fake Connection 不进入网络列表

旧报告把该结论标为完整引用搜索推断。

本次已确认该结论。

Vanilla 只有 Channel 初始化路径写入网络列表。

Carpet 只把 Fake Connection 传给 `PlayerList.placeNewPlayer()`。

#### Player tick 调用链

旧报告的主调用链已确认。

本次补充了每一级 Vanilla 方法证据。

#### Movement Emission

旧报告称权威 Mixin 影响服务端 Movement Emission。

该说法已纠正。

服务端本来就走 `!level.isClientSide()` 分支。

#### 普通击退

旧报告把 `Player_fakePlayersMixin` 描述为普通击退补丁。

该说法已收窄。

它只跳过玩家攻击附加击退路径中的发送和旧速度恢复。

基础伤害击退来自 Vanilla。

#### 爆炸

旧报告结论已确认。

Carpet 没有 Fake Player 爆炸补丁。

Vanilla `ServerExplosion.push()` 写入速度。

#### 载具

旧报告没有说明完整载具控制的证据限制。

权威 Mixin 确实覆盖 Fake Player 控制的载具。

但 Boat 控制方法仍只在客户端运行。

所以不能从源码推出完整客户端载具控制等价性。

## Files Found

- `src/main/java/carpet/patches/EntityPlayerMPFake.java:50`：Fake Player 类型和完整生命周期。
- `src/main/java/carpet/patches/FakeClientConnection.java:14`：空客户端连接。
- `src/main/java/carpet/patches/NetHandlerPlayServerFake.java:14`：Fake Player Listener。
- `src/main/java/carpet/commands/PlayerCommand.java:344`：`shadow` 命令入口。
- `src/main/java/carpet/mixins/PlayerList_fakePlayersMixin.java:36`：Listener 和 Respawn 构造替换。
- `src/main/java/carpet/mixins/EntityMixin.java:38`：服务端本地权威改写。
- `src/main/java/carpet/mixins/Player_fakePlayersMixin.java:16`：玩家攻击速度保留。
- `src/main/java/carpet/mixins/ServerPlayer_fakeLastMovementMixin.java:25`：已知移动改写。
- `src/main/java/carpet/mixins/ServerPlayer_actionPackMixin.java:27`：Action Pack 注入。
- `src/main/java/carpet/helpers/EntityPlayerActionPack.java:53`：输入和动作执行。
- `src/main/java/carpet/mixins/PistonMovingBlockEntity_playerHandlingMixin.java:36`：黏液活塞速度。
- `src/main/java/carpet/mixins/TickRateManager_fakePlayersMixin.java:21`：游戏冻结时的 Fake Player 判定。
- `src/main/resources/carpet.mixins.json:51`：相关 Mixin 注册。
- `minecraft-26.2-sources.jar!/net/minecraft/server/network/ServerConnectionListener.java:47`：网络 tick 列表。
- `minecraft-26.2-sources.jar!/net/minecraft/server/network/ServerGamePacketListenerImpl.java:293`：普通连接 tick 和回写。
- `minecraft-26.2-sources.jar!/net/minecraft/server/players/PlayerList.java:145`：Player 注册和 Respawn。
- `minecraft-26.2-sources.jar!/net/minecraft/server/level/ServerPlayer.java:573`：Player 服务端 tick。
- `minecraft-26.2-sources.jar!/net/minecraft/server/level/ServerLevel.java:425`：实体 tick 和爆炸 Packet。
- `minecraft-26.2-sources.jar!/net/minecraft/world/entity/Entity.java:712`：碰撞、权威和已知移动。
- `minecraft-26.2-sources.jar!/net/minecraft/world/entity/LivingEntity.java:1166`：伤害、击退和移动。
- `minecraft-26.2-sources.jar!/net/minecraft/world/entity/player/Player.java:232`：Player tick 和击退恢复。
- `minecraft-26.2-sources.jar!/net/minecraft/world/level/ServerExplosion.java:171`：爆炸冲量。
- `minecraft-26.2-sources.jar!/net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:120`：活塞位移和黏液块跳过 Player。

## Code Patterns

- `EntityPlayerMPFake.tick()`：实体 tick 内主动调用 `doTick()`。
- `PlayerList_fakePlayersMixin.replaceNetworkHandler()`：只替换 Fake Player 的 Listener 构造。
- `EntityMixin.isFakePlayer()`：用 Player 类型切换服务端本地权威。
- `ServerPlayer_fakeLastMovementMixin.bypassClientMovementInfo()`：用实体移动替代客户端已知移动。
- `EntityPlayerActionPack.onUpdate()`：先执行动作，再写本 tick 的移动输入。
- `Player_fakePlayersMixin.velocityModifiedAndNotCarpetFakePlayer()`：跳过玩家攻击后的旧速度恢复。
- `PistonMovingBlockEntity_playerHandlingMixin.moveFakePlayers()`：补 Vanilla 对 ServerPlayer 跳过的黏液速度。

## External References

- [Fabric Carpet 固定 Commit](https://github.com/gnembon/fabric-carpet/tree/dbedd4c91d4956c38874fe9509c48265f0afacf5)
- [EntityPlayerMPFake](https://github.com/gnembon/fabric-carpet/blob/dbedd4c91d4956c38874fe9509c48265f0afacf5/src/main/java/carpet/patches/EntityPlayerMPFake.java)
- [FakeClientConnection](https://github.com/gnembon/fabric-carpet/blob/dbedd4c91d4956c38874fe9509c48265f0afacf5/src/main/java/carpet/patches/FakeClientConnection.java)
- [Entity authority Mixin](https://github.com/gnembon/fabric-carpet/blob/dbedd4c91d4956c38874fe9509c48265f0afacf5/src/main/java/carpet/mixins/EntityMixin.java)
- [Player knockback Mixin](https://github.com/gnembon/fabric-carpet/blob/dbedd4c91d4956c38874fe9509c48265f0afacf5/src/main/java/carpet/mixins/Player_fakePlayersMixin.java)
- [ServerPlayer movement Mixin](https://github.com/gnembon/fabric-carpet/blob/dbedd4c91d4956c38874fe9509c48265f0afacf5/src/main/java/carpet/mixins/ServerPlayer_fakeLastMovementMixin.java)
- [EntityPlayerActionPack](https://github.com/gnembon/fabric-carpet/blob/dbedd4c91d4956c38874fe9509c48265f0afacf5/src/main/java/carpet/helpers/EntityPlayerActionPack.java)

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`：固定后端不修改、原连接保留和 Shadow Player 计算边界。
- `.trellis/spec/guides/cross-layer-thinking-guide.md`：跨后端、Proxy 和 Plugin 的职责边界。

## Caveats / Not Found

- Carpet 没有 Fake Player Player 计算的独立自动化测试目录。
- 本报告确认静态构造和 tick 路径。
- 本报告没有运行带真实玩家和 Fake Player 的集成服务器。
- Boat 的完整输入等价性没有源码证据。
- `createShadow()` 没有复制断开瞬间 `deltaMovement` 的源码证据。
- Carpet Fake Player 行为不等于 Vanilla 客户端逐 tick 行为。
- Carpet 把权威移到服务端。
- Vanilla 在线 Player 仍使用客户端移动结果。
- Carpet 的 MIT License 不改变 Minecraft 源码和运行时的许可边界。

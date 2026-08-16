# Research: Minecraft Java 26.2 击退、坠落、活塞与流体责任边界

- Query: 用本机固定 Minecraft Java 26.2 deobf source/jar 与 MCProtocolLib `26.2-20260809.160751-16` 查清攻击、伤害、爆炸、移动积分、坠落伤害、水流、船、活塞及 Position/teleport 的 Vanilla 包与状态语义，并区分当前实测症状
- Scope: internal / local fixed dependency source
- Date: 2026-08-14

## Findings

### 1. 版本与证据边界

本研究只使用本机固定制品，没有以 Wiki、旧版本反编译或网络搜索替代 26.2 证据。

| 制品 | 本机路径 | SHA-256 / 版本 |
| --- | --- | --- |
| Vanilla merged deobf source | `E:/Gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2-sources.jar` | `E1AAA82F91A79407D2828D2057F29F4C0009A559CC713B248AB71D04372B8DA3` |
| Vanilla merged deobf binary | `E:/Gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar` | `1463A746E967BAA2393530DEA69B0DA3C46838935B2A63C38843C1325F2BDEEB` |
| Vanilla official client jar | `E:/Gradle/caches/fabric-loom/26.2/minecraft-client.jar` | `40896EE9F1E2BEC3C934DAAC7E93D41E9E3D9C2F8AE0CA366D52FFBFD1AFA290` |
| MCProtocolLib source | `build/tmp/mcprotocollib-sources.jar` | `F1B8E3C338500EEC5C82ED868CA9FEF82B40C520451E9EB220542B3274C557C8` |
| MCProtocolLib binary | `E:/Gradle/caches/modules-2/files-2.1/org.geysermc.mcprotocollib/protocol/26.2-20260809.160751-16/4a8e1d*/protocol-26.2-20260809.160751-16.jar` | `07EC18BA92C8B4041286EEFF2470E08257FD1F383881515CBA4A0A9BF6FA98C1` |

项目固定点为 `plugin/build.gradle.kts:12` 和 `plugin/src/main/java/.../ProtocolTarget.java:5-8`。以下 `*.java:line` 均指上述 26.2 source jar 中的条目；MCProtocolLib 类名指 build 16 source jar。

### 2. 玩家攻击与 mob/箭矢攻击不是同一发包时机

#### 2.1 共同的伤害和默认击退入口

`LivingEntity.hurtServer()` 在完整伤害路径中先决定是否 `markHurt()`，再决定是否执行默认击退：

- `LivingEntity.java:1166-1254`：伤害、无敌帧与完整伤害分支。
- `LivingEntity.java:1233-1235`：伤害类型不属于 `DamageTypeTags.NO_IMPACT` 时调用 `markHurt()`。
- `LivingEntity.java:1237-1239`：伤害类型不属于 `DamageTypeTags.NO_KNOCKBACK` 时调用 `dealDefaultKnockback()`。
- `LivingEntity.java:1280-1295`：投射物以投射物速度的水平分量决定方向；其他来源以伤害源位置决定方向，默认强度为 `0.4F`。
- `Projectile.java:375-379`：投射物的 `calculateHorizontalHurtKnockbackDirection()` 返回自身 `deltaMovement.x/z`。
- `LivingEntity.java:1628-1645`：先应用 knockback resistance；水平旧速度减半后减去归一化方向乘强度；只有目标在地面时才把 Y 更新为 `min(0.4, oldY / 2 + power)`，空中时不新增默认 Y；最后设置 `hasImpulse`/`needsSync`。
- `Entity.java:1907-1909`：`markHurt()` 设置 `hurtMarked = true`。

完整伤害、`hurtMarked`、默认击退是三个不同事实。`NO_KNOCKBACK` 只取消默认击退；若它不是 `NO_IMPACT`，伤害仍可留下 `hurtMarked`，从而稍后发送一份“当前速度”的 Motion。

#### 2.2 普通 mob：追踪器延迟发送最终 Motion

`Mob.doHurtTarget()` 的顺序是：

1. 保存目标原速度。
2. `target.hurtServer(...)`，其中可能已经做默认 `0.4` 击退。
3. `causeExtraKnockback(...)`，再叠加攻击属性/附魔击退。

证据为 `Mob.java:1376-1393`。该方法没有针对 `ServerPlayer` 立即发包。

最终速度由实体追踪器在后续同步阶段发给追踪者和目标本人：

- `ServerEntity.java:221-224`：发现 `hurtMarked` 后清除标志，并调用 `sendToTrackingPlayersAndSelf(new ClientboundSetEntityMotionPacket(entity))`。

所以 mob 攻击依赖“追踪器到 self”的 Motion 路径。

#### 2.3 箭矢：同样依赖追踪器，但最终速度在命中末尾才完整

`AbstractArrow.onHitEntity()` 的关键顺序是：

- `AbstractArrow.java:419-524`：先计算伤害并执行 `hurtOrSimulate()`（约第 463 行），再执行 `doKnockback()`（约第 473 行）。
- `AbstractArrow.java:514-524`：Punch/附加击退又在命中处理中追加一次水平 push。

箭矢伤害本身不是 `NO_KNOCKBACK`，因此普通默认击退已在 `hurtServer()` 中发生；`doKnockback()`/Punch 可继续改变最终速度。它没有玩家攻击的即时 self 发包分支，仍由 `ServerEntity.sendChanges()` 发最终 Motion。

这解释了为何“mob 与箭矢都全丢”应首先作为同一追踪器/self Motion 路径检查，而不是分别归因于两套物理公式。

#### 2.4 玩家攻击：有专门的即时 self Motion，再恢复服务端速度

`Player.attack()` 保存旧速度、执行伤害，然后执行额外击退：

- `Player.java:945-999`：第 978 行保存目标旧速度，第 979 行伤害，第 981 行调用 `causeExtraKnockback()`。
- `Player.java:1113-1140`：额外击退处理。

关键差异在 `Player.causeExtraKnockback()`：对 `ServerPlayer`，只要 `hurtMarked` 为真，就立即向目标发送 `ClientboundSetEntityMotionPacket(entity)`，随后清除 `hurtMarked`，并把服务端目标速度恢复为攻击前保存的 `oldMovement`。这个 `ServerPlayer` 分支位于额外击退强度判断之外，额外击退为零也不改变该即时 self Motion 语义。

因此：

- 玩家攻击的目标 self Motion 在攻击调用栈中即时发送。
- mob/箭矢的目标 self Motion 在 `ServerEntity` 追踪同步阶段发送。
- 玩家攻击发包后，服务端实体速度恢复旧值；客户端负责把收到的新速度跑成位置并通过 MovePlayer 回报。

当前“玩家攻击多数丢、偶尔只有短位移，但 mob/箭矢全丢”的最强区分点就是这两个发包时机。偶发短位移说明不能只检查是否算出了击退，还要检查即时 Motion 后是否被下一份 Motion 或 Position correction 覆盖。

#### 2.5 客户端 Motion 是覆盖，不是叠加

- `ClientPacketListener.java:624-629`：`handleSetEntityMotion()` 按 entity id 查找实体后调用 `entity.lerpMotion(packet.movement())`。
- `Entity.java:2628-2630`：`lerpMotion()` 直接 `setDeltaMovement(x, y, z)`。
- MCProtocolLib build 16 `ClientboundSetEntityMotionPacket`：字段为 `entityId` 与 `Vector3d movement`，Vec3 由协议 helper 解码。

因此应在原始解包后确认 self entity id、完整三轴值与到达顺序。把 Motion 当作加法会与 Vanilla 不同；找不到实体时 Vanilla handler 也不会缓存等待实体出现。

#### 2.6 SetEntityMotion 可以在 bundle 中，但 Vanilla 不会跳过它

26.2 的 bundle 是 delimiter 包围的一组正常 clientbound play 子包，bundler 对 delimiter 之间的非 terminal 包没有按具体 packet type 排除。因而 `ClientboundSetEntityMotionPacket` **可以**位于 bundle 中；不能假设击退 Motion 总是独立于 delimiter 到达。

固定源码的处理语义：

- Vanilla `BundlerInfo.createForPacket()`：开始 delimiter 后，把直到结束 delimiter 的每个非 terminal packet 依次加入 bundle，数量上限 4096。
- Vanilla `PacketBundlePacker.decode()`：delimiter 区间转成 `ClientboundBundlePacket`。
- `ClientPacketListener.java:2432-2437`：`handleBundlePacket()` 按 `subPackets()` 原顺序逐一调用 `subPacket.handle(this)`；Motion 最终仍进入 `handleSetEntityMotion()`。
- MCProtocolLib build 16 `BundlerUnpackerDecoder`：delimiter 之间的每个 `MinecraftPacket` 加入 `currentPackets`，结束 delimiter 时下游只收到一个 `ClientboundBundlePacket(currentPackets)`。
- MCProtocolLib build 16 `ClientboundBundlePacket`：内容是 `List<MinecraftPacket> packets`。

所以监听层决定可见形态：在 MCProtocolLib bundler 之后监听时，必须观察 Bundle wrapper 及其有序子包，不能只订阅顶层 `ClientboundSetEntityMotionPacket`；在 delimiter 聚合前监听时则会看到每个原始子包。

本项目当前 patch 的进一步证据是 `plugin/patch/0002-automation-extension.patch:800-852`：`MinecraftDecoder` 对每个已经 frame/sizer 拆出的 packet 先读 packet id，再直接按 MCProtocolLib definition 触发 packet event，然后才继续 Velocity 解码。也就是说当前 `S2CPacketEvent<ClientboundSetEntityMotionPacket>` 的观察点位于 bundle 聚合之前；delimiter 中的 Motion 仍是一个带 Motion packet id 的独立 frame，应被该 event 命中。因此 bundle 是抓包时必须展开的边界，但仅凭“服务端使用了 bundle”不能解释该 raw event 层的 Motion 丢失。若日志来自另一个位于 MCProtocolLib `BundlerUnpackerDecoder` 之后的监听点，则结论相反：那里只看顶层具体 Motion 会漏包。

### 3. 自然伤害中哪些本来没有击退

固定 26.2 的 `DamageTypeTagsProvider.java:124-156` 和客户端 jar 内 `data/minecraft/tags/damage_type/no_knockback.json` 给出精确 `NO_KNOCKBACK` 集合：

`explosion`、`player_explosion`、`bad_respawn_point`、`in_fire`、`lightning_bolt`、`on_fire`、`lava`、`hot_floor`、`sulfur_cube_hot`、`in_wall`、`cramming`、`drown`、`starve`、`cactus`、`fall`、`ender_pearl`、`fly_into_wall`、`fell_out_of_world`（JSON id 为 `out_of_world`）、`generic`、`magic`、`wither`、`dragon_breath`、`dry_out`、`sweet_berry_bush`、`freeze`、`stalagmite`、`outside_border`、`generic_kill`、`campfire`、`spear`。

其中 `DamageTypeTagsProvider.java:89` 和 `no_impact.json` 表明 `NO_IMPACT` 只有 `drown`。

判读规则：

- 上述自然伤害本来没有默认击退，不应把“伤害发生但无新位移”一律判断为失败。
- 除溺水外，它们通常仍会 `markHurt()`，因此追踪器可能发送 Motion，但该 Motion 只是当时已有速度，不代表伤害产生了新冲量。
- 溺水同时属于 `NO_IMPACT` 与 `NO_KNOCKBACK`，不因这次伤害设置 `hurtMarked`，因而不要求出现伤害驱动的 Motion。
- 无敌帧/非完整伤害分支会改变 `markHurt` 和默认击退是否执行，不能只按伤害类型推断每一次命中必发 Motion。

### 4. 爆炸：单独 Explosion 包叠加玩家向量

爆炸不是普通默认击退：

- `ServerExplosion.java:181-209`：先 `hurtServer()`（约第 191 行），再按距离、遮挡和 knockback resistance 算独立爆炸向量（第 194-198 行），`entity.push(...)`（第 199 行）；非旁观且非 creative flying 的玩家记录进 `hitPlayers`（第 202-203 行）。
- 爆炸伤害类型本身属于 `NO_KNOCKBACK`，所以 `hurtServer()` 不再做默认 `0.4` 击退。
- `ServerLevel.java:1204-1235`：爆炸完成后，对距离平方小于 `4096`（64 格）的每个玩家发送 `ClientboundExplodePacket`；只有 `hitPlayers` 中的玩家带非空 knockback。
- `ClientPacketListener.java:1353-1371`：`handleExplosion()` 将可选玩家向量 `addDeltaMovement(...)`，即叠加而非覆盖。
- MCProtocolLib build 16 `ClientboundExplodePacket`：玩家 knockback 为 nullable `Vector3f`。

爆炸伤害仍可能设置 `hurtMarked`，所以同一事件附近还可能有追踪器 Motion。检查必须保留时序：Explosion 总可在 64 格内出现但 knockback 可为空；其中的 knockback 是加法；后到的 Motion 是覆盖。

### 5. LocalPlayer 收到速度后的真实 tick 顺序

#### 5.1 顶层顺序

`LocalPlayer.tick()` 的核心顺序为：

1. `super.tick()` 跑完实体基础 tick、流体 interaction、输入、travel、move、碰撞和速度衰减。
2. `sendInput()`。
3. 乘坐本地主控载具时发 `ServerboundMoveVehiclePacket`；否则 `sendPosition()` 发 MovePlayer。

证据：`LocalPlayer.java:228-246`。继承链为 `LocalPlayer -> Player -> LivingEntity -> Entity`：

- `Player.java:232-259` 调 `super.tick()`。
- `LivingEntity.java:2730-2771` 调 `super.tick()`，之后 `aiStep()`。
- `Entity.java:507-565` 的 `tick()/baseTick()` 先处理实体基础状态。

#### 5.2 流体 pushing 先于 travel

`Entity.baseTick()` 在 `Entity.java:531-533` 先保存眼部水状态，再调用 `updateFluidInteraction()`，然后更新 swimming 状态。`Entity.updateFluidInteraction()` 在 `Entity.java:1637-1660` 更新 tracker、重置水中 fall distance，并在允许时把水流以 `0.014`、熔岩以 `0.007`（FAST_LAVA）或 `0.0023333333333333335` 注入速度。

所以本 tick 的流体 current 会进入同一 tick 后面的 `LivingEntity.travel()`。

#### 5.3 输入、travel、move、碰撞、重力/阻力

`LivingEntity.aiStep()` 的可观察顺序：

- `LivingEntity.java:3020-3043`：玩家极小速度清零阈值，水平 length squared `< 9e-6`；Y 的绝对值 `< 0.003`。
- 随后读取/应用输入、跳跃逻辑。
- `LivingEntity.java:3086-3101`：调用 `travel()`。
- `LivingEntity.java:3103-3105`：travel 后应用 block effects。
- `LivingEntity.java:3127-3134`：再执行实体互推。

`LivingEntity.travel()`（`LivingEntity.java:2402-2409`）选择水、熔岩、fall flying 或空气路径。最容易被写反的细节是：在普通空气路径中，本 tick 先用当前速度移动/碰撞，之后才把重力和阻力写进“下一 tick 使用的速度”。

- `LivingEntity.java:2646-2655`：`handleRelativeFrictionAndCalculateMovement()` 先加输入、处理梯子限制，再 `move(MoverType.SELF, currentVelocity)`，然后取得碰撞后的运动结果。
- `LivingEntity.java:2440-2466`：`travelInAir()` 在上述 move 之后处理 levitation/重力，最后应用空气/地面 friction 与垂直 drag。
- `LivingEntity.java:2486-2512`：水中也是加输入、move，之后做水平/垂直 drag、fluid gravity adjustment 与跳出水面判断。
- `LivingEntity.java:2519-2534`：熔岩同样先 move，后阻力/重力。

因此 Motion 到达后的最小真实时间线是：

`packet 覆盖 deltaMovement -> 下一次 baseTick 流体可叠加 -> aiStep 输入/跳跃 -> travel 加相对输入 -> move/碰撞/落地状态 -> 重力/阻力生成下一 tick velocity -> sendPosition 回报新位置与标志`。

#### 5.4 move 内部的碰撞顺序

`Entity.move()`（`Entity.java:712-801`）依次处理 noPhysics、活塞轴限幅、stuck multiplier、边缘保护、碰撞裁剪、写位置、碰撞标志/onGround、客户端本地 fall check、碰撞后的速度消除/回弹、movement/block effects 与 speed factor。

- `Entity.java:1138-1166`：`collide()` 比较直接裁剪与 step-up 候选。
- `Entity.java:1241-1256`：形状碰撞按 `Direction.axisStepOrder(movement)` 确定轴顺序。

这意味着只比较“应用重力前后的一个速度”不足以判断失败；至少要分别记录 packet 写入、move 前、move 后位置/碰撞标志、drag 后速度。

### 6. 坠落轨迹与服务端坠落伤害是两条检查链

#### 6.1 服务端不靠自己的自然位置积分接受普通玩家位移

`ServerGamePacketListenerImpl.java:313-320` 的 player tick 会运行 `player.doTick()`，但之后把位置恢复到本 tick 开始的 `firstGood`。普通玩家最终接受位置仍由 MovePlayer 驱动。

所以“本地自然下落轨迹通过”不自动推出“服务端收到了足以累计 fallDistance 的下落位置”。

#### 6.2 服务端从已接受的坐标差累计 fallDistance

`ServerGamePacketListenerImpl.handleMovePlayer()`（`ServerGamePacketListenerImpl.java:1050-1185`）在接受分支：

1. 从 Pos/PosRot 读坐标；Rot/StatusOnly 则回退到当前服务端坐标。
2. 做有限值、移动过快、碰撞等校验。
3. snap 到目标坐标（约第 1141 行）。
4. 形成 `clientDeltaMovement`（第 1153 行）。
5. `setOnGroundWithMovement(packet.isOnGround(), horizontalCollision, delta)`（第 1154 行）。
6. `doCheckFallDamage(deltaX, deltaY, deltaZ, packet.isOnGround())`（第 1155 行）。

`Entity.doCheckFallDamage()/checkFallDamage()`（`Entity.java:1540-1566`）的规则：

- touching unloaded chunk 时抑制检查。
- 不在水中且 `dy < 0` 时执行 `fallDistance -= dy`。
- `onGround == true` 时对脚下方块调用 `fallOn(..., fallDistance)`，之后重置 fall distance。

`LivingEntity.java:363-390` 在上层更新 fluid/effect 状态后委托该逻辑；`LivingEntity.java:1771-1795` 与 `1830-1841` 按 safe fall distance 和 multiplier 计算伤害；`Player.java:1429-1438` 对 mayfly 玩家禁用坠落伤害，否则委托 LivingEntity。

协议中没有客户端提交的 `fallDistance` 或“当前下落速度”字段。服务端只从被接受的逐包负 Y 坐标差累计，再由客户端包携带的 `onGround=true` 结算。

#### 6.3 客户端必须发送什么

固定 26.2 `ServerboundMovePlayerPacket.java:9-225`：

- 一个 flags byte，bit 1 为 `onGround`，bit 2 为 `horizontalCollision`。
- `Pos`/`PosRot` 才携带坐标，能累计新的下落距离。
- `Rot`/`StatusOnly` 不携带坐标，服务端回退到当前坐标；它们可用 `onGround=true` 结算此前已累计的 fallDistance，但不能产生新的下降量。

MCProtocolLib build 16 的 `ServerboundMovePlayerPosPacket`、`PosRotPacket`、`RotPacket`、`StatusOnlyPacket` 与上述形态一致。

`LocalPlayer.sendPosition()`（`LocalPlayer.java:264-299`）按位置差阈值 `2e-4`、旋转变化和标志变化选择 PosRot/Pos/Rot/StatusOnly，并用 position reminder 至少每 20 tick 强制发送一次位置。

当前“自然下落轨迹通过，但所有掉落伤害丢失”应先检查：每个下降 tick 是否真的输出并被服务端接受为 Pos/PosRot，最终一次包是否携带 `onGround=true`。只看到本地 Y 变化或只看到 StatusOnly 都不能证明 fallDistance 已累计。

#### 6.4 correction 会怎样覆盖轨迹

移动被拒绝时，`handleMovePlayer()` 约第 1175 行 teleport 回开始位置，并只对纠正结果做后续处理。标准 `teleport(double...)` 在 `ServerGamePacketListenerImpl.java:1232-1244` 构造 delta 为 `ZERO`、relative flags 为空的 PlayerPosition 包。

- 等待 teleport ack 时，`handleMovePlayer()` 第 1064-1066 行忽略位置推进，只接收旋转。
- `updateAwaitingTeleport()`（第 1200-1214 行）20 tick 后重发。
- `handleAcceptTeleport()`（第 527-544 行）完成确认。

客户端收到这类 absolute Position 后不仅回到服务端坐标，还会把速度覆盖为 packet 中的零 delta，详见第 9 节。因而“击退偶发短位移”和“下落轨迹短暂存在但不累计伤害”都要检查首次 correction 及 ack 前被忽略的 C2S 区间。

### 7. 26.2 水流 interaction 的准确算法

#### 7.1 immersion/采样范围

26.2 的实现集中在 `EntityFluidInteraction.java`：

- `EntityFluidInteraction.java:32-89`：每 tick 清 tracker；取 `entity.getFluidInteractionBox()`；各轴用 `floor(min)` 到 `ceil(max)-1` 遍历方块。
- `Entity.java:4059-4067`：通常 interaction box 是 bounding box 各向内缩 `0.001`；载具可修改乘客的 interaction box。
- 计算 immersion 的基准 `entityY` 是原始 bounding box 的 `minY`，不是内缩 box 的 minY。
- 单格 fluid top 为 `blockY + fluidState.getHeight(...)`；只有 fluid top 不低于 interaction box minY 才参与。
- tracker 按 fluid state 的类型是否属于传入 tag 来归类，不是按 block id 字符串。
- 眼睛在某格 x/z 范围内且 `eyeY >= cellBottom && eyeY <= fluidTop` 时标记 eyes inside，端点为包含关系。
- immersion height 取所有命中格的 `max(fluidTop - entityY)`。

`EntityFluidInteraction.java:91-119` 还要求 interaction box 的 x/z 外扩一格所覆盖的所有 chunk 均为 FULL；缺任一 chunk 直接返回 false。对应 section 必须存在且 `hasFluid()`，否则跳过该 section。

#### 7.2 flow vector

`FluidState.java:49-55,95-97` 把高度和流向委托给 fluid type。`FlowingFluid.getFlow()`（`FlowingFluid.java:55-100`）对四个水平方向：

1. 邻居 fluid 为空或同类型才允许参与 `affectsFlow`。
2. 同类型邻居直接取其 own height。
3. 邻居高度为 0 且邻居 block 不 `blocksMotion` 时，再看邻居下方；若下方同类型，差值为 `currentOwnHeight - (belowHeight - 0.8888889F)`。
4. 其他情况下差值为 `currentOwnHeight - neighborOwnHeight`。
5. 累加 `direction.normal * difference`。
6. 当前 fluid 为 FALLING 且水平邻居或其上方存在阻流 solid face 时，先归一化水平向量，再加 `(0, -6, 0)`。
7. 对最终向量再次归一化。

`FlowingFluid.getHeight()`（`FlowingFluid.java:469-475`）：上方同类型时高度为 1，否则为 `amount / 9`。

`affectsFlow` 的 face 判定也不可省略：同 fluid 不阻流；UP 面按规则处理；ice 有例外；其他情况依赖对应面的 sturdy/solid face，而不只是 `blocksMotion`。

#### 7.3 玩家与非玩家的 normalize/scale 差异

遍历顺序固定为 `x -> y -> z`（`EntityFluidInteraction.java:52-86`）。对属于 tracker tag 的第 `k` 个相交 fluid cell，定义：

- `top_k = blockY_k + fluidState_k.getHeight(level, pos)`。
- `H_0 = 0`，`H_k = max(H_(k-1), top_k - entityBoundingBox.minY)`。注意这里先更新 tracker 全局最大 immersion，再缩放当前格，不是独立使用当前格自己的 immersion。
- `F_k = fluidState_k.getFlow(level, pos)`。
- `G_k = F_k * H_k`（当 `H_k < 0.4`），否则 `G_k = F_k`。
- `S = sum(G_k)`，`n` 为命中的 tracker cell 数；即使某格 `F_k` 为零，`accumulateCurrent()` 仍使 `n++`。

只有 `n != 0` 且 `lengthSquared(S) >= 1e-5` 才提交 current。`EntityFluidInteraction.Tracker` 在 `EntityFluidInteraction.java:153-190` 的完整提交公式是：

- Player 初始 impulse：`I0 = S / n`。
- 非 Player 初始 impulse：`I0 = normalize(S)`。
- 外部缩放后：`I1 = I0 * scale`；水为 `scale=0.014`，熔岩为 `0.007` 或 `0.0023333333333333335`。
- 取注入前旧速度 `Vold`。若 `abs(Vold.x) < 0.003 && abs(Vold.z) < 0.003 && length(I1) < 0.0045`，则 `I = normalize(I1) * 0.0045`；否则 `I = I1`。该阈值检查的是旧速度水平分量，但 impulse length 是三维长度。
- 最终 `Vnew = Vold + I`。

用源码分支描述即：

- 非 `Player`：把累计向量 normalize。
- `Player`：按命中流体格数量求平均，即 `scale(1 / count)`，不 normalize。
- 再乘外部 current scale（水 `0.014`；熔岩见上文）。
- 若原速度 `abs(x) < 0.003` 且 `abs(z) < 0.003`，而本次 current 长度 `< 0.0045`，则把 current normalize 到 `0.0045`。
- 最后 `addDeltaMovement(current)`。

这不是“所有实体聚合流向后统一 normalize 到 0.014”。当前生产实现的可区分证据是 `plugin/.../World.java:237-276` 对聚合 flow 做统一 normalize/scale；它与 Vanilla Player 的平均法、浅水缩放和最小 current nudge 不同。这里只记录责任差异，不提出改动。

#### 7.4 复现水流所需状态

精确执行上述算法至少需要：

- interaction box、原始 bounding box minY、eyeY、当前 deltaMovement、实体是否 `Player`、玩家 `abilities.flying`。
- x/z 外扩一格的 FULL chunk 可用性、section 是否 `hasFluid`。
- 每个采样格及四邻/邻下/必要邻上的 block state 与 fluid state。
- fluid 是否 empty、type/source/amount/falling、own height。
- block `blocksMotion`、相关 face sturdy/solid、ice 例外。
- fluid tag 的精确成员；26.2 jar 的 `water.json` 为 `minecraft:water` 与 `minecraft:flowing_water`，`lava.json` 为 `minecraft:lava` 与 `minecraft:flowing_lava`。
- dimension type 的 FAST_LAVA 属性。

`Player.java:869-870,1672-1673` 表明玩家 flying 时不受 fluid pushing/interaction 影响。缺失其中任何状态都可能表现为“水流、特殊环境全部丢失”，而不需要等待一个服务器 Motion 包。

### 8. 玩家水流与船在水中是两套运动责任

#### 8.1 自由玩家

自由玩家先经 `Entity.baseTick()` 的 generic fluid tracker，使用“Player 求平均”current；随后 `LivingEntity.travelInFluid()`/水中 travel 先 move，再做水阻力和 fluid gravity；tick 末输出 MovePlayer。

#### 8.2 船

`AbstractBoat.tick()`（`AbstractBoat.java:206-247`）的顺序：

1. 先计算 boat status。
2. `super.tick()`，因此船也经过 generic `EntityFluidInteraction`；船不是 Player，current 使用 normalize 规则。
3. 处理插值。
4. 只有本地权威控制时运行 `floatBoat()`、客户端控制/桨状态，然后 `move()`；非权威一侧把 delta 置零。

船还有独立状态与浮力：

- `AbstractBoat.java:369-385`：选择 IN_WATER、UNDER_WATER、UNDER_FLOWING_WATER、ON_LAND、IN_AIR。
- `AbstractBoat.java:459-486`：底部采样水面高度并判断 `checkInWater()`。
- `AbstractBoat.java:488-516`：顶部采样并区分 source/flowing underwater。
- `AbstractBoat.java:387-420`：扫描上方 water level。
- `AbstractBoat.java:524-565`：`floatBoat()` 按 status 应用不同重力、浮力、水平 friction 和垂直速度。
- `AbstractBoat.java:572-601`：`controlBoat()` 只负责驾驶输入；零输入不会取消 current 或浮力。

对“本地权威、玩家仍乘坐、四个 input flag 均为 false”的船，本 tick 仍按以下顺序运行：

1. `super.tick()` 先按非 Player 公式注入 generic fluid current。
2. `floatBoat()` 总会执行：先按 status 令 `(Vx,Vy,Vz) = (Vx*f, Vy+vspeed, Vz*f)` 并令 `deltaRotation *= f`；若 buoyancy 大于零，再令 `Vy = (Vy + buoyancy*(0.04/0.65))*0.75`（`AbstractBoat.java:524-563`）。
3. 零输入使 `controlBoat()` 的 acceleration 为 0，不新增驾驶推进；但仍更新 yaw、以零推进向量执行 add，并把两侧 paddle 设为 false（`AbstractBoat.java:572-600`）。
4. 仍执行 `move(MoverType.SELF, deltaMovement)`，并发送 `ServerboundPaddleBoatPacket(false,false)`；LocalPlayer 随后为 root vehicle 输出 MoveVehicle。

状态参数为：IN_WATER `f=0.9` 并按浸水高度产生 buoyancy；UNDER_FLOWING_WATER `vspeed=-0.0007,f=0.9`；UNDER_WATER `buoyancy=0.01,f=0.45`；IN_AIR `vspeed=-0.04,f=0.9`；ON_LAND 使用 landFriction（有 Player controller 时除以 2）。从 IN_AIR 首次入水还有 snap-to-water 且 Y 速度清零的分支（`AbstractBoat.java:528-555`）。只有非本地权威分支才在 `AbstractBoat.java:241-243` 直接把 deltaMovement 置零；这与“本地权威但零输入”不同。

乘客不是另一个自由水中玩家：`AbstractBoat.java:769-780` 会根据船体顶面裁剪或取消乘客的 fluid interaction box。通常由根载具接受 current/浮力，乘客跟随载具。

网络输出也不同：`LocalPlayer.java:237-243` 表明乘坐本地主控载具时，玩家发旋转状态并为 root vehicle 发 `ServerboundMoveVehiclePacket`，不是自由玩家的 Pos/PosRot。

因此“玩家水流失败”和“船/水流失败”应分开检查：

| 场景 | current 聚合 | 后续物理 | C2S 所有者 |
| --- | --- | --- | --- |
| 自由玩家 | Player：按 fluid cell count 求平均 | LivingEntity 水中 move/drag/gravity | MovePlayer |
| 本地主控船 | 非 Player：聚合后 normalize | boat status + floatBoat + move | MoveVehicle(root vehicle) |
| 船上乘客 | 通常被船裁剪独立 fluid interaction | 跟随 root vehicle | 玩家 Rot/状态 + root MoveVehicle |

仅复用“玩家 water travel”不能证明船正确；仅有船的 status/float 也不能证明 generic 非玩家 current 正确。

### 9. Position、ROTATE_DELTA 与 teleport 对速度的覆盖语义

`PositionMoveRotation.calculateAbsolute()`（`PositionMoveRotation.java:40-67`）同时解析位置、旋转和 delta movement：

- X/Y/Z 与 Y_ROT/X_ROT 对应 relative flag 决定相加或覆盖。
- 若有 `ROTATE_DELTA`，先把现有/源 velocity 依 `(source.xRot - absoluteXRot)` 绕 X 旋转，再依 `(source.yRot - absoluteYRot)` 绕 Y 旋转。
- 对每个速度分量，只有存在对应 `DELTA_X/Y/Z` flag 时，结果才是“旋转后的现有分量 + packet delta”；没有该分量 flag 时，packet delta 直接覆盖。

所以 `ROTATE_DELTA` 本身不等于保留速度。若三个 DELTA flag 都不存在，即使计算过旋转，三个旋转后的旧分量也会被丢弃，最终使用 packet 的三个 delta 分量。

客户端玩家位置包：

- `ClientPacketListener.java:796-805`：非乘客时应用 position packet；随后 ack teleport，并立即回发当前 PosRot，flags 为 `onGround=false`、`horizontalCollision=false`。
- `ClientPacketListener.java:808-827`：`setValuesFromPositionPacket()` 计算 absolute 值，并无条件 `entity.setDeltaMovement(newValues.deltaMovement())`；即使位置采用 interpolation，速度也立即覆盖。
- 玩家是 passenger 时，Position 对其位置/velocity 的应用被跳过，但 ack 与当前 PosRot 仍发送。

实体 teleport：`ClientPacketListener.java:669-694` 的 `handleTeleportEntity()` 也用相同 PositionMoveRotation 语义，并立即设置 velocity。

标准服务端 correction 使用 `deltaMovement=ZERO`、relative flags 空集合，因此同时 absolute snap 位置并把客户端速度清零。这是“只有短位移”的直接判别点。

不要与 `ClientboundEntityPositionSyncPacket` 混淆：虽然 MCProtocolLib build 16 的 PositionSync 也带 `deltaMovement`，Vanilla `ClientPacketListener.java:642-665` 的 handler 只应用位置、旋转与 onGround，没有调用 `setDeltaMovement()`。

MCProtocolLib build 16 对应证据：

- `PositionElement` 枚举为 `X, Y, Z, Y_ROT, X_ROT, DELTA_X, DELTA_Y, DELTA_Z, ROTATE_DELTA`，按 ordinal 编码 flags。
- `ClientboundPlayerPositionPacket` 与 `ClientboundTeleportEntityPacket` 都含 position、deltaMovement、yaw/pitch 与 relative 列表；后者另含 onGround。

### 10. 普通活塞通过而粘性活塞失败的源码分界

活塞推动实体不是一次新的 self Motion 包，而是客户端世界中的 moving piston block entity 每 tick 以 `MoverType.PISTON` 推动实体。

#### 10.1 extension、普通回缩与粘性回拉

`PistonBaseBlock.triggerEvent()`（`PistonBaseBlock.java:150-221`）：

- block event 参数 `b0 == 0`：extension，调用 `moveBlocks(..., true)`。
- `b0 == 1/2`：retraction，在 base 位置创建 `extending=false, source=true` 的 retracting `PistonMovingBlockEntity`（约第 179-188 行）。
- 只有 sticky piston 的 `b0 == 1` 合格回拉分支（约第 191-211 行）会额外调用 `moveBlocks(..., false)`，为被拉 block 创建另一个 moving BE。
- 普通 piston 的回缩分支（约第 213-215 行）只移除 head，不创建“被拉 block”的 moving BE。

`PistonBaseBlock.moveBlocks()`（`PistonBaseBlock.java:267-335`）为每个 moved block 明确创建 MovingPistonBlock 与 `source=false` 的 `PistonMovingBlockEntity`（约第 307-315 行）；extension 还为 piston head 创建 `source=true` 的 moving BE（约第 318-327 行）。

`MovingPistonBlock.java:54-55` 的普通 `newBlockEntity()` 返回 null，说明这些 BE 依赖 piston block event/moveBlocks 显式构造，不能假设后续看到 MovingPistonBlock 就会自动产生完整状态。

#### 10.2 实体推动

`PistonMovingBlockEntity.java:305-337` 每 tick 以 `0.5` 进度推进，并调用 `moveCollidedEntities()` 与 `moveStuckEntities()`。

- `PistonMovingBlockEntity.java:226-228`：extension 使用 facing direction，retraction 使用相反方向。
- `PistonMovingBlockEntity.java:120-190`：对相交且可推动实体调用 `move(MoverType.PISTON, ...)`。
- `PistonMovingBlockEntity.java:222-224`：所谓对实体的 sticky 行为由 moved block 是 `HONEY_BLOCK` 决定，不是由 piston 是 sticky piston 决定。
- `PistonMovingBlockEntity.java:110-117,361-389`：retracting source 的 piston head collision shape 还依赖 movedState/source/extending 状态。

故当前“普通活塞通过、粘性活塞不通过”优先区分 retraction 的事件与双 moving-BE 状态，而不是等待 Motion：

- 是否收到了正确 piston BlockEvent action（extension 0、retraction 1/2）。
- sticky pull 是否同时存在 retracting source/base BE 与被拉 block 的 non-source BE。
- 每个 BE 的 movedState、direction、extending、source、progress 是否正确。
- 是否只依赖 BlockEntityData；Vanilla 客户端的核心构造入口是 block event 触发 `triggerEvent()/moveBlocks()`。

当前生产代码的可定位事实是 `plugin/.../World.java:495-508` 从 BlockEntityData NBT 填充 `movingPistons`，而搜索未发现对应 BlockEvent 驱动路径；`World.java:605-650` 执行 moving piston tick。这个差异恰好能解释 normal extension 通过而 sticky retract/pull 失败，但结论仍需用实测包序列验证。

### 11. 面向当前五组症状的诊断检查表

以下检查只用于定位责任，不提出新功能或主动输入。

| 症状 | Vanilla 应观察到的第一差异 | 后续必须连续成立 | 最有区分力的失败点 |
| --- | --- | --- | --- |
| 玩家攻击多数丢、偶尔短位移 | 攻击调用栈内即时 self `ClientboundSetEntityMotionPacket`，可位于 delimiter bundle 内 | Motion 覆盖 self velocity；下一 tick move；输出 Pos/PosRot；无后到 correction 清零 | raw event 应逐帧命中；若监听在 bundler 后则展开 Bundle；再查短位移后的 Position/Teleport/Motion 覆盖 |
| mob 攻击全丢 | `ServerEntity.sendChanges()` 的 tracking-and-self Motion | 与玩家攻击相同的客户端积分/C2S 闭环 | 若玩家即时包能到而 mob 不到，隔离 tracker/self 路由或 bundle/dispatch，而不是击退公式 |
| 箭矢击退全丢 | 命中完成默认击退和 `doKnockback()` 后，由 tracker 发最终 Motion | Motion 应包含命中末尾的最终速度 | 与 mob 同丢强烈指向 tracker/self Motion；不要只采集 `hurtServer()` 中间速度 |
| 所有掉落伤害丢，但自然下落通过 | 每个被接受下降步有 Pos/PosRot 与负 deltaY | 服务端累计 fallDistance；落地包 `onGround=true`；不在水/mayfly/unloaded-chunk 抑制状态 | 本地轨迹与服务端 fallDistance 分离；StatusOnly 不能累计下降；correction/awaiting ack 可使下降未接受 |
| 普通活塞通过、粘性活塞失败 | sticky retraction 的 BlockEvent `b0=1` 和两组 moving piston state | 每 tick progress、source/extending/direction、collision shape 完整 | 不是 self Motion；优先检查 sticky pull 专属 event/被拉 block BE，而非 generic piston move |
| 玩家水流全丢 | baseTick fluid interaction 命中 water tag，Player 按 cell count 求平均 | current 加入后进入同 tick water move，再发 MovePlayer | chunk/section/fluid tag/height 数据；把 Player flow normalize 会与 Vanilla 不同 |
| 船/水流全丢 | 根船以非 Player 规则 normalize generic current | boat status/floatBoat/authority/move；乘客跟随；输出 MoveVehicle | 不能用玩家水 travel 代替；核对 root vehicle 权威、status、乘客 interaction box 和 MoveVehicle |
| 爆炸位移异常 | 64 格内 Explosion 包；可选 knockback | Explosion 先 add，后续 Motion/Position 按到达顺序可能覆盖 | 区分 nullable knockback、add 语义和后到 overwrite |

建议按同一 tick 时间线对照已有日志/抓包中的事实，不把阶段折叠：

1. 原始 S2C packet 类型、self entity id、三轴 delta/relative flags、到达序号。
2. handler 写入后的 deltaMovement。
3. baseTick fluid current 后、travel/move 前的 deltaMovement。
4. move 后 position、vertical/horizontal collision 与 onGround。
5. gravity/drag 后留给下一 tick 的 deltaMovement。
6. tick 末实际发出的 MovePlayer 或 MoveVehicle 变体、坐标和 flags。
7. 服务端是否接受该坐标，以及下一份 correction、teleport ack 或 Motion 是否覆盖。

这七个观察点可直接区分“包未到/实体未解析”“速度被后包覆盖”“本地积分缺失”“C2S 形态错误”“服务端拒绝/纠正”五类责任。

## Files Found

- `minecraft-merged-deobf-26.2-sources.jar!/net/minecraft/world/entity/LivingEntity.java`：伤害、默认击退、travel、重力/阻力、坠落伤害。
- `...!/net/minecraft/world/entity/player/Player.java`：玩家攻击的即时 self Motion、玩家 fall/mayfly 与 fluid 条件。
- `...!/net/minecraft/world/entity/Mob.java`：mob 攻击与额外击退顺序。
- `...!/net/minecraft/world/entity/projectile/arrow/AbstractArrow.java`：箭矢命中与最终击退顺序。
- `...!/net/minecraft/server/level/ServerEntity.java`：`hurtMarked` 的 tracking-and-self Motion 发包。
- `...!/net/minecraft/world/level/ServerExplosion.java`、`server/level/ServerLevel.java`：爆炸独立 push 与 Explosion 包分发。
- `...!/net/minecraft/client/multiplayer/ClientPacketListener.java`：Motion/Explosion/Position/Teleport 的客户端写入语义。
- `...!/net/minecraft/network/PacketBundlePacker.java`、`network/protocol/BundlerInfo.java`：bundle 聚合；`ClientPacketListener.handleBundlePacket()` 有序处理子包。
- `...!/net/minecraft/client/player/LocalPlayer.java`：tick 后 MovePlayer/MoveVehicle 输出。
- `...!/net/minecraft/world/entity/Entity.java`：baseTick、move/collision、fallDistance 和 fluid interaction 入口。
- `...!/net/minecraft/world/entity/EntityFluidInteraction.java`、`world/level/material/FlowingFluid.java`、`FluidState.java`：26.2 fluid height/current 精确算法。
- `...!/net/minecraft/server/network/ServerGamePacketListenerImpl.java`：MovePlayer 验证、fallDistance 驱动、correction 与 teleport ack。
- `...!/net/minecraft/world/entity/PositionMoveRotation.java`：relative position 与 delta/ROTATE_DELTA 组合规则。
- `...!/net/minecraft/world/level/block/piston/PistonBaseBlock.java`、`PistonMovingBlockEntity.java`、`MovingPistonBlock.java`：普通/粘性活塞事件与本地实体推动。
- `...!/net/minecraft/world/entity/vehicle/boat/AbstractBoat.java`：船的 water status、浮力、权威和乘客 fluid box。
- `minecraft-client.jar!/data/minecraft/tags/damage_type/no_knockback.json`、`no_impact.json`：26.2 精确伤害 tag。
- `minecraft-client.jar!/data/minecraft/tags/fluid/water.json`、`lava.json`：流体 tag 成员。
- `mcprotocollib-sources.jar!/org/geysermc/mcprotocollib/protocol/packet/ingame/...`：build 16 Motion、Explosion、Position、Teleport 与四种 MovePlayer 编解码类。
- `mcprotocollib-sources.jar!/org/geysermc/mcprotocollib/network/netty/BundlerUnpackerDecoder.java` 与 `ClientboundBundlePacket.java`：聚合后下游所见 Bundle wrapper 和有序 packet list。

## External References

无。研究刻意限定到本机固定 26.2 Vanilla 与 MCProtocolLib build 16 制品，未使用网络资料。

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`：Minecraft 26.2、协议 776、MCProtocolLib build 16 固定版本，以及 `Entity.move` / `LivingEntity.travel` / `Player.tick` 为运行时物理范围。
- `.trellis/spec/backend/quality-guidelines.md`：协议和物理语义须以固定版本证据验证。
- `.trellis/spec/language/java.md`：Java 层实现约束；本文件不修改实现。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/prd.md`：零主动输入与 Vanilla 行为目标。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/design.md`：shadow connection、world/player state 与包流边界。

## Caveats / Not Found

- 本研究说明 Vanilla 责任和最有区分力的检查点，没有实现修复，也没有提出零主动输入范围外的行为。
- 玩家攻击、mob/箭矢和爆炸的实际包顺序仍可能受服务端 tick、bundle 和代理 dispatch 时序影响；必须以同一连接的原始包序列验证，不能只按最终位置反推。
- Motion 可以位于 bundle 是协议结构允许性结论，不代表每次攻击都使用 bundle；当前项目 raw decoder event 位于聚合前，理论上仍逐个看到 bundle 子包。
- `NO_KNOCKBACK` 列表是固定 26.2 内置 tag。数据包可以在具体服务器重载 damage type tags；若验收服有数据包，需以登录后同步/服务器实际 registry/tag 为准。
- fluid flow 依赖完整邻接 block/fluid/face 状态。仅凭中心格 fluid amount 无法精确复现 falling fluid 的向下分量。
- normal/sticky piston 的结论指出了源码分支与当前状态入口差异，但没有该次实测的原始 BlockEvent/块更新序列，因此“缺 BlockEvent 驱动”是待抓包确认的高置信诊断，不是已由实测包证明的唯一根因。
- PositionSync 带 deltaMovement 但 Vanilla handler 不写 velocity 是 26.2 的具体语义；不可用 PlayerPosition/Teleport 的规则外推到所有带 velocity 字段的包。

# Research: MCProtocolLib 26.2 世界、实体与玩家物理状态

> 状态：本报告记录完整候选集合。初版只采用 `prd.md` 和 `design.md` 列出的最小状态。

- Query: 固定 MCProtocolLib 26.2 中，生命、死亡、实体、世界和受击位移需要订阅哪些 Packet。库提供哪些数据结构。Plugin 还需提供哪些模型。
- Scope: internal / mixed
- Date: 2026-08-13

## Findings

### 检查版本

- 最终方案固定 `org.geysermc.mcprotocollib:protocol:26.2-20260809.160751-16`。
- Packet 源码来自 `build/tmp/mcprotocollib-sources.jar`。
- Vanilla 行为来自 `E:/Gradle/caches/fabric-loom/26.2/minecraft-merged.jar`。
- 当前 `design.md` 只订阅基础连接 Packet，并明确延后完整实体和世界模型。见 `.trellis/tasks/08-11-automation-shadow-infrastructure/design.md` 的“Shadow 状态机”和“旧工具状态参考”。新需求会改变该范围，但本报告不修改规划。

### 结论

MCProtocolLib 提供 26.2 Packet 编解码、向量、实体类型、metadata、属性、效果、区块 palette 和 light 容器。

MCProtocolLib 不提供以下能力：

- 可查询的客户端世界。
- Entity 实例或 Entity tracker。
- Block State 到碰撞形状的映射。
- 玩家包围盒和方块碰撞求解。
- 重力、阻力、流体、攀爬和效果共同作用的 20 TPS 物理循环。
- Vanilla 的移动 Packet 选择和发送节流。

因此，“收到攻击后只保存 `ClientboundSetEntityMotionPacket.movement`”不充分。Velocity 后端保留的是服务端连接，不是原客户端的 `LocalPlayer`。真实前端关闭后，不再有客户端物理循环替玩家推进位置。

若要求受击、爆炸、下落和碰撞表现正常，Plugin 必须运行一个无输入玩家物理模型。模型按 tick 更新位置和速度，并发送对应的 serverbound movement Packet。

### 三个状态域

#### Self Player State

每个 Shadow 玩家至少保存：

- `entityId`、当前位置、速度、yaw、pitch、onGround 和 horizontalCollision。
- health、food、saturation 和死亡状态。
- 当前 `PlayerSpawnInfo`。它包含 dimension、worldName、gameMode、lastDeathPos、portalCooldown 和 seaLevel。
- abilities。包含 invincible、canFly、flying、creative、flySpeed 和 walkSpeed。
- 会影响移动或尺寸的 attributes、mob effects 和 entity metadata。
- vehicleId、passenger 关系和当前 pose。

最小 S2C 订阅集合：

| Packet | Self 状态作用 |
| --- | --- |
| `ClientboundLoginPacket` | 取得 `entityId`、世界集合、距离参数和 `PlayerSpawnInfo`。源码字段见 JAR 内该类 `17-27`。 |
| `ClientboundRespawnPacket` | 切换 dimension 和 world。按 `keepMetadata`、`keepAttributeModifiers` 决定保留项。源码 `18-25`。 |
| `ClientboundPlayerPositionPacket` | 应用绝对或相对位置、速度和旋转，并确认 teleport。源码 `21-26`。 |
| `ClientboundPlayerRotationPacket` | 更新绝对或相对 yaw、pitch。 |
| `ClientboundSetHealthPacket` | 更新 health、food 和 saturation。health 小于等于零进入死亡状态。 |
| `ClientboundPlayerCombatKillPacket` | 保存死亡消息。死亡判定仍以 health 为准。 |
| `ClientboundDamageEventPacket` | 保存伤害来源。它本身不携带击退速度。 |
| `ClientboundSetEntityMotionPacket` | 当 `entityId` 等于 self 时，替换或插值 self 速度。源码字段为 `entityId` 和 `Vector3d movement`。 |
| `ClientboundExplodePacket` | `playerKnockback` 非空时，将爆炸击退加入 self 速度。源码 `22-28`。 |
| `ClientboundPlayerAbilitiesPacket` | 更新飞行、无敌、创造模式和移动速度。 |
| `ClientboundUpdateAttributesPacket` | 当 `entityId` 等于 self 时，更新移动速度、重力、跳跃、尺度等属性。 |
| `ClientboundUpdateMobEffectPacket` | 当 `entityId` 等于 self 时，增加或更新移动相关效果。 |
| `ClientboundRemoveMobEffectPacket` | 当 `entityId` 等于 self 时，移除效果。 |
| `ClientboundSetEntityDataPacket` | 当 `entityId` 等于 self 时，更新 pose、flags 和其他 metadata。 |
| `ClientboundSetPassengersPacket` | 判断 self 是否乘坐实体，并保存 vehicleId。 |
| `ClientboundMoveVehiclePacket` | 服务端纠正当前载具的位置和旋转。 |

`ClientboundHurtAnimationPacket`、`ClientboundAnimatePacket` 和 `ClientboundPlayerCombatEnterPacket` 只影响表现或战斗 UI。基础物理模型不需要订阅。

`ClientboundEntityEventPacket` 包含枚举化 `EntityEvent`。如果事件会影响玩家状态，应处理 self 事件。不能把它作为生命值来源。

死亡后的继续行为需要显式产品决定。若初版自动重生，health 小于等于零后发送 `ServerboundClientCommandPacket(ClientCommand.RESPAWN)`，再等待 `ClientboundRespawnPacket`、teleport 和 `ServerboundPlayerLoadedPacket`。

#### Nearby Entity Tracker

实体 tracker 用于伤害来源、载具、实体碰撞和后续 target action。每个实体至少保存：

- entityId、UUID、`EntityType` 和 spawn `ObjectData`。
- 位置、速度、body yaw、head yaw、pitch 和 onGround。
- metadata、attributes、effects 和 passengers。

最小 S2C 订阅集合：

| 类别 | Packet |
| --- | --- |
| 创建和删除 | `ClientboundAddEntityPacket`、`ClientboundRemoveEntitiesPacket` |
| 相对移动 | `ClientboundMoveEntityPosPacket`、`ClientboundMoveEntityRotPacket`、`ClientboundMoveEntityPosRotPacket` |
| 绝对同步 | `ClientboundEntityPositionSyncPacket`、`ClientboundTeleportEntityPacket` |
| 速度和朝向 | `ClientboundSetEntityMotionPacket`、`ClientboundRotateHeadPacket` |
| 实体形状状态 | `ClientboundSetEntityDataPacket` |
| 载具关系 | `ClientboundSetPassengersPacket`、`ClientboundMoveVehiclePacket`、`ClientboundMoveMinecartPacket` |
| 属性和效果 | `ClientboundUpdateAttributesPacket`、`ClientboundUpdateMobEffectPacket`、`ClientboundRemoveMobEffectPacket` |

`ClientboundAddEntityPacket` 已提供 `EntityType`、ObjectData、初始位置、速度和三个旋转量。源码位于 JAR 内该类 `28-38`。

MCProtocolLib 使用 `EntityType`、`EntityMetadata<?, ?>[]`、`Attribute`、`AttributeModifier`、`AttributeType` 和 `Effect` 表示协议数据。

这些类型没有实体行为。Plugin 仍需提供：

- 按 `EntityType` 和 pose 计算包围盒的数据表。
- metadata ID 到 pose、flags 和尺寸语义的 26.2 映射。
- passenger 到 vehicle 的反向索引。
- 实体移除、dimension 切换和 chunk 卸载时的清理规则。
- 需要实体推动时的碰撞查询。

只要求玩家受击后移动时，不需要让所有附近实体运行物理。实体 tracker 只应用服务端发来的位置和速度更新。

#### Collision Chunk Cache

碰撞缓存只保存玩家物理查询需要的区块。它不等于完整渲染世界。

最小 S2C 订阅集合：

| Packet | Cache 作用 |
| --- | --- |
| `ClientboundRegistryDataPacket` | 保存 dimension type 等动态 registry。 |
| `ClientboundLoginPacket`、`ClientboundRespawnPacket` | 选择当前 dimension、world、minY 和 height。 |
| `ClientboundLevelChunkWithLightPacket` | 创建或替换一个 chunk 的 block sections。 |
| `ClientboundForgetLevelChunkPacket` | 删除 chunk。 |
| `ClientboundBlockUpdatePacket` | 更新一个 block state。 |
| `ClientboundSectionBlocksUpdatePacket` | 批量更新一个 section 的 block state。 |

以下 Packet 不属于碰撞缓存的最低集合：

- `ClientboundChunksBiomesPacket`。Biome 不决定基础碰撞。
- `ClientboundLightUpdatePacket`。光照不决定碰撞。
- `ClientboundBlockEntityDataPacket`。Block Entity NBT 不决定基础方块形状。
- `ClientboundBlockEventPacket` 和 `ClientboundBlockDestructionPacket`。它们是动画或进度。
- `ClientboundSetChunkCacheCenterPacket` 和 `ClientboundSetChunkCacheRadiusPacket`。实际 chunk 到达和 forget Packet 已决定缓存内容。
- 六个 World Border Packet。World Border 不改变 block collision，但会影响正常玩家移动限制。若“世界表现正常”包含边界，应另存 border 状态。

World Border 的完整 Packet 是：

- `ClientboundInitializeBorderPacket`
- `ClientboundSetBorderCenterPacket`
- `ClientboundSetBorderLerpSizePacket`
- `ClientboundSetBorderSizePacket`
- `ClientboundSetBorderWarningDelayPacket`
- `ClientboundSetBorderWarningDistancePacket`

### `ClientboundLevelChunkWithLightPacket` 的真实数据形状

MCProtocolLib 没有把该 Packet 的 chunk body 解成 `ChunkSection[]`。

它只提供：

```text
int x
int z
byte[] chunkData
Map<HeightmapTypes, long[]> heightMaps
BlockEntityInfo[] blockEntities
LightUpdateData lightData
```

源码见 `build/tmp/mcprotocollib-sources.jar!/org/geysermc/mcprotocollib/protocol/packet/ingame/clientbound/level/ClientboundLevelChunkWithLightPacket.java:23-40`。

构造器通过 `MinecraftTypes.readByteArray(in)` 复制完整 `chunkData`。它只解析 heightmap、block entities 和 light。它不解析 block palette。

MCProtocolLib 提供以下底层 helper：

- `MinecraftTypes.readChunkSection(ByteBuf, int blockStateRegistrySize, int biomeRegistrySize)`。
- `ChunkSection`。
- `DataPalette`。
- `BitStorage`。
- `GlobalPalette`、`ListPalette`、`MapPalette` 和 `SingletonPalette`。

Plugin 必须根据当前 dimension 的 section 数量，对 `chunkData` 连续调用 `readChunkSection`。

解码依赖两个 registry size：

- `blockStateRegistrySize` 决定 block global palette 位宽。
- `biomeRegistrySize` 决定 biome global palette 位宽。

`ClientboundRegistryDataPacket` 提供 registry `Key` 和 `List<RegistryEntry>`。`RegistryEntry` 只有资源 ID 和可空 NBT。

动态 biome registry 可以提供 `biomeRegistrySize`。Block State 是固定协议 registry。MCProtocolLib snapshot 没有 Block State registry、属性或碰撞形状数据表。

因此 Plugin 必须随 26.2 固定版本提供：

- block state ID 数量。
- block state ID 到 block properties 的映射。
- block state ID 到 collision voxel shape 的映射。
- fluid、climbable、powder snow、cobweb、honey、bubble column 等移动语义。

仅用 `ChunkSection.getBlock(x, y, z)` 可以取得数字 state ID。它不能判断该 state 是否可穿过，也不能计算碰撞盒。

### 受击后的正常位移

26.2 Vanilla 客户端收到 `ClientboundSetEntityMotionPacket` 后，对目标 Entity 调用 `lerpMotion`。收到 `ClientboundExplodePacket` 后，将 `playerKnockback` 加入本地玩家速度。

之后 `LocalPlayer.tick()` 每 tick 运行玩家和实体物理，再由 `sendPosition()` 选择 movement Packet。Vanilla 不是收到击退 Packet 后立即回发一个固定位置。

Shadow 的无输入物理 tick 至少需要：

1. 接收 self motion 或 explosion knockback。
2. 应用 ability、attribute、effect 和 dimension 规则。
3. 应用重力、阻力、流体和特殊方块运动。
4. 用玩家包围盒查询 collision chunk cache。
5. 分轴裁剪移动量并更新 onGround、horizontalCollision。
6. 更新位置和剩余速度。
7. 按 Vanilla 变化阈值选择 `Pos`、`Rot`、`PosRot` 或 `StatusOnly`。
8. 发送 `ServerboundClientTickEndPacket`，因为现在要求模拟正常客户端 tick，而不再只是最低保活。

对应 MCProtocolLib 类型：

- `ServerboundMovePlayerPosPacket`
- `ServerboundMovePlayerRotPacket`
- `ServerboundMovePlayerPosRotPacket`
- `ServerboundMovePlayerStatusOnlyPacket`
- `ServerboundMoveVehiclePacket`
- `ServerboundClientTickEndPacket`

Vanilla `sendPosition()` 使用位置平方变化阈值，并至少每 20 tick 发送一次位置。它在变化组合间选择四种 movement Packet。这个算法属于 Plugin 物理输出器，不属于 Patch。

单独实现击退抛物线仍不够“正常”。玩家可能在台阶、墙边、水中、梯子上、蜂蜜块上、蛛网内、载具内或 world border 附近受击。这些情况都依赖世界和玩家状态。

### Dimension、Abilities、Effects 和 Attributes

`PlayerSpawnInfo` 提供 dimension registry ID 和 world Key，但没有展开 dimension type NBT。Plugin 需保留配置阶段的 `ClientboundRegistryDataPacket`，再按 ID 解析 dimension type。

物理模型至少读取以下类别：

- dimension 的 minY、height、coordinate scale 和环境属性。
- `ClientboundPlayerAbilitiesPacket` 的 flying、canFly、creative、flySpeed 和 walkSpeed。
- `ClientboundUpdateAttributesPacket` 的 player movement speed、flying speed、gravity、jump strength、step height、scale 和 safe fall distance。
- `ClientboundUpdateMobEffectPacket` 和 `ClientboundRemoveMobEffectPacket` 的 speed、slowness、jump boost、slow falling、levitation、dolphin grace 等效果。

MCProtocolLib 的 `Attribute` 保存 type、base value 和 modifier 列表。它不计算 Vanilla 最终属性值。Plugin 必须按 `ModifierOperation` 顺序计算。

MCProtocolLib 的 `Effect` 是枚举。它不减少 duration，也不应用每种效果。Plugin 必须每 tick 维护 duration 和效果语义。

### 订阅宽度和转换开销

按上述最低集合计算，新增 S2C 订阅约为：

- Self player：14 个具体 Packet 类。部分也与实体 tracker 共用。
- Nearby entity tracker：16 个具体 Packet 类。
- Collision chunk cache：6 个具体 Packet 类。
- 可选 World Border：6 个具体 Packet 类。

去重后，生命、实体和碰撞世界约需 30 个具体 S2C Packet 类型。加入 World Border 后约为 36 个。

这个数量不会让未订阅 Packet 产生 MCProtocolLib 对象。批准的 lazy PacketEvent 先按方向、状态和 Packet ID 查缓存。没有 handler 的 Packet 继续原 Velocity 解码。

小型实体 Packet 的额外成本主要是：

- 一个只读 payload duplicate。
- 一次 MCProtocolLib Packet 实例化。
- 一个 Packet Event。
- 一次按 `Player` 路由和 Map 更新。

`ClientboundLevelChunkWithLightPacket` 是明显例外。订阅它后，每个 chunk Packet 会额外：

- 复制完整 `chunkData` 到新 `byte[]`。
- 解析 heightmaps、Block Entity NBT 和全部 light arrays。
- 创建一个 MCProtocolLib Packet Event。
- 由 Plugin 再遍历 `chunkData`，构建 `ChunkSection` 和 collision cache。

MCProtocolLib Packet 构造器无法只解析 block sections。批准的通用 PacketEvent 也不能按字段跳过 light 和 block entity。这个开销是完整碰撞世界在当前接口下的成本。

内存必须按玩家限制。建议 collision cache 只保留服务端当前发送且未 forget 的 chunk，并在 dimension 切换时整体清空。不要复制 heightmap、light 或 Block Entity NBT 到 Plugin 世界模型。

Nearby entity tracker 同样只保留当前 dimension 中未 remove 的实体。`ClientboundRespawnPacket` 切换 dimension 时清空实体表和 collision cache。

### Patch 与 Plugin 边界

本研究没有发现需要新增 shadow 专用 Patch 接口。

Patch 继续只负责 lazy PacketEvent 和 MCProtocolLib Packet 发送。

Plugin 负责：

- Self player state。
- Nearby entity tracker。
- Collision chunk cache。
- 26.2 block state 和 collision shape 数据。
- 20 TPS 无输入玩家物理。
- movement Packet 选择。
- death 和 respawn 策略。

## Files Found

- `plugin/build.gradle.kts:12-34`：固定 MCProtocolLib 版本和依赖方式。
- `build/tmp/mcprotocollib-sources.jar`：26.2 Packet、codec 和 data 类型源码。
- `E:/Gradle/caches/fabric-loom/26.2/minecraft-merged.jar`：Vanilla 26.2 客户端处理和玩家物理行为。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/design.md`：已批准的 lazy PacketEvent、Packet 发送和旧 Shadow 范围。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/headless-client-state.md`：Mineflayer 与 MCC 的世界、实体和物理模块边界。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/vanilla-idle-state.md`：最低保活与 Vanilla movement silence 结论。

## External References

- 未使用网络资料。结论来自工作区固定依赖源码和 Minecraft 26.2 字节码。

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`
- `.trellis/spec/language/java.md`

## Caveats / Not Found

- MCProtocolLib 不包含可直接复用的 26.2 玩家物理引擎。
- MCProtocolLib 不包含 Block State 到 collision shape 的 26.2 数据集。
- 本报告确认 Packet 和数据边界，没有证明自行实现的物理与 Vanilla 数值完全一致。
- Paper、Fabric、Forge 和后端 Plugin 可能改变击退、属性或移动校验。Plugin 应以实际收到的 motion、attribute 和 effect Packet 为输入。
- “表现正常”若要求所有方块、流体、载具和效果都与 Vanilla 一致，范围等价于实现或引入一个版本固定的客户端物理核心，明显大于基础保活状态机。

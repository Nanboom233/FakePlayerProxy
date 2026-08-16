# Research: Java Velocity Plugin 的 Player 计算方案

- Query: 哪些现有 Java 工具能在 Velocity Plugin 内接管 Minecraft 26.2 Vanilla 客户端的本地 Player 计算，以及最简可实施方案是什么。
- Scope: mixed
- Date: 2026-08-13

## Findings

### 1. 结论

没有现成 Java 依赖可以在当前 Velocity Plugin 内直接运行 Minecraft 26.2 的完整本地 Player 计算。

现有工具分成三类：

1. Minestom 提供服务端实体碰撞和基础空气物理。它不实现 Vanilla 客户端 `LocalPlayer` 计算。
2. Geyser 修正真实 Bedrock 客户端上报的移动。它不替代客户端产生无输入物理轨迹。
3. ViaProxy、Baritone 和 Spectron 都不提供独立的无头客户端物理引擎。

最简方案不是嵌入这些工具。

最简方案是在 Plugin 内实现一个固定 Minecraft 26.2 的窄 Player 计算器。它只覆盖当前 Shadow 要求：

- 接收服务端外部速度。
- 运行 20 TPS 零输入 Player 计算。
- 应用空气、地面、水和岩浆运动。
- 使用已加载 Block State 处理 AABB 碰撞。
- 计算 `onGround` 和 `horizontalCollision`。
- 发送 MCProtocolLib Move Player Packet。
- 接受服务端 Teleport 纠正。

MCProtocolLib 继续负责 Packet 和 Chunk Palette。

项目生成并打包紧凑的 Minecraft 26.2 物理数据。

Minestom 的 Apache-2.0 碰撞代码可以作为算法参考。若逐段移植，项目必须保留许可证通知。不能把完整 Minestom 作为 Plugin 依赖。

### 2. 候选比较

| 候选 | Java / 协议 | 世界数据 | 碰撞 | 流体和逐 tick Player 计算 | Move Player | 许可证 | 直接集成结论 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Minestom | Java 25，数据目标 26.2 | 完整服务端 Instance 和 Block Registry | 有方块、实体和世界边界碰撞 | 只有通用服务端空气物理。没有 Vanilla `LocalPlayer` 水流闭环 | 发送服务端实体同步，不发送客户端 Move Player | Apache-2.0 | 不能直接依赖。可参考或移植碰撞内核。 |
| Geyser | Java 21，MCProtocolLib 26.2 | 完整 `GeyserSession`、WorldManager 和 BlockMappings | 有 Java/Bedrock 差异修正 | 依赖真实 Bedrock 客户端产生位置和速度。不是无头 Player 计算器 | 把 Bedrock 输入转换成 Java Move Player | MIT | 不能拆成小依赖。可参考 Packet 选择和碰撞边界。 |
| ViaProxy | 可降级到 Java 8，支持 26.2 | ViaVersion Entity Tracker 和协议映射 | 无 Player 物理碰撞 | 无 | 转发真实客户端流量 | GPL-3.0 | 不适用。它仍需要真实客户端。 |
| MCProtocolLib | 当前项目使用 Java 17 build 15，协议 26.2 | Chunk Palette 和 Packet 类型 | 无 | 无 | 提供 Move Player Packet 类型 | MIT | 保留为 Packet 层。 |
| Baritone | Minecraft Mod，当前主分支绑定 Minecraft 客户端 | 直接使用 `ClientLevel` | 直接使用 Minecraft AABB 和 BlockState | 由真实 `LocalPlayer` 执行 | 由真实客户端发送 | LGPL-3.0 | 不适用。它是输入和寻路层，不是物理层。 |
| Spectron | Java Headless Bot，版本模型不完整 | 自有稀疏 Chunk 和 Block 表 | 无 | 无 | 只写固定位置 Packet | GPL-3.0 | 不适用。当前实现不足以作为物理参考。 |
| 精简 Vanilla client classes | Java 25，精确 26.2 | `ClientLevel` 和完整注册表 | 精确 Vanilla | 精确 Vanilla | 精确 Vanilla | Minecraft 分发和使用条款 | 不能作为小型 Plugin 依赖。运行边界接近完整客户端。 |

### 3. Minestom

#### 它实现了什么

Minestom master 在提交 `93f9d6c7b3698579afae05b3863bf328ecbacd60` 使用 26.2 数据 `net.minestom:data:26.2-rv3`。

`net/minestom/server/collision/PhysicsUtils.java:30-46` 暴露 `simulateMovement`。调用者提供以下数据：

- 位置
- 每 tick 速度
- Bounding Box
- World Border
- `Block.Getter`
- `Aerodynamics`
- 重力和飞行标志
- 上一个 `PhysicsResult`

`net/minestom/server/collision/CollisionUtils.java:119-146` 可以只通过 `Block.Getter` 调用方块碰撞。该入口比完整 `Instance` 入口更接近可拆内核。

`net/minestom/server/collision/BlockCollision.java:99-210` 扫描 Swept AABB。它输出裁剪后的位移、碰撞轴和 `onGround`。

`net/minestom/server/instance/block/Block.java:380-386` 提供摩擦。`Block.java:541-547` 提供碰撞形状。

#### 它没有实现什么

`PhysicsUtils.updateVelocity` 只处理重力、空气阻力和地面摩擦。`PhysicsUtils.java:66-80` 没有 Vanilla `LivingEntity.travelInWater`、`travelInLava`、流体推力或本地 Player Packet 选择。

Minestom 的 `Entity.tick` 是服务端实体逻辑。`Entity.java:711-721` 用 Minestom `Instance` 和 `ChunkCache` 运行服务端物理。它不是 Vanilla `LocalPlayer.tick`。

Minestom 的碰撞结果也不保证逐 tick 等同 Minecraft 26.2 客户端。它使用自己的 Swept AABB 算法和缓存。

#### 为什么不能直接作为依赖

Minestom 当前默认 Java 版本是 25。`build-src/src/main/kotlin/minestom.java-library.gradle.kts:6-7` 和 `:36-38` 设置 Java 25 Toolchain。

当前 Plugin 在 `plugin/build.gradle.kts:16-20` 和 `:49-52` 固定 Java 17。

Minestom 还是一个整体模块。`src/main/java/module-info.java:18-34` 要求 Gson、Fastutil、Flare、Adventure、JCTools、JFR、Desktop 和 Minestom Data。碰撞包虽然被导出，但它使用 Minestom 的 Coordinate、Block、Registry 和 World Border 类型。

因此不能只增加一个小 `minestom-physics` 依赖。

#### 可复用边界

可以把以下结构作为参考：

- `BoundingBox`
- `Shape`
- `SweepResult`
- `PhysicsResult`
- `BlockCollision`

实际移植仍需替换 Minestom `Pos`、`Vec`、`Block.Getter`、`Block`、`WorldBorder` 和 Registry 数据。

这已经接近重新实现一个小碰撞内核。

Minestom 只适合降低方块碰撞算法风险。它不能消除 Player 计算、流体、世界状态和 Packet 输出工作。

### 4. Geyser

#### 它实现了什么

Geyser master 在提交 `9163e0503362cc2357d3b1ff2c9c1a22927cd8b8` 使用 Java 21，并固定 MCProtocolLib `26.2-20260709.110151-15`。

`core/src/main/java/org/geysermc/geyser/level/physics/CollisionManager.java:295-342` 裁剪 Bedrock 客户端上报的位移。它也实现台阶处理。

`CollisionManager.java:349-400` 按 Y、X、Z 轴依次裁剪玩家 Bounding Box。

`CollisionManager.java:421-440` 能查询玩家是否接触水。

`core/src/main/java/org/geysermc/geyser/translator/protocol/bedrock/entity/player/input/BedrockMovePlayer.java:66-226` 选择 Java Move Player Packet。它包含 20 tick Position Reminder、旋转包、位置包和 Status Only 包。

#### 它没有实现什么

Geyser 不产生 Bedrock Player 的物理轨迹。

`BedrockPlayerAuthInputTranslator.java:71-231` 接收真实 Bedrock 客户端的 `PlayerAuthInputPacket`。随后 `BedrockMovePlayer` 把客户端位置转换成 Java位置。

`JavaSetEntityMotionTranslator.java:37-63` 只把 Java 服务端速度转发为 Bedrock `SetEntityMotionPacket`。真实 Bedrock 客户端继续执行重力、阻力和流体运动。

因此 Geyser 的 `CollisionManager` 是跨版本移动修正器。它不是可独立运行的 `LocalPlayer` 替代。

#### 为什么不能拆成小依赖

`CollisionManager.java:28-52` 直接依赖 `GeyserSession`、`SessionPlayerEntity`、`WorldManager`、`PistonCache`、Block Registry、Bedrock Packet 和 Cloudburst Math。

`core/build.gradle.kts:23-94` 还引入 Geyser common、api、MCProtocolLib、Bedrock Protocol、Netty、Adventure、Fastutil 和其他运行依赖。

即使复制 `CollisionManager`，也必须重写 Session、WorldManager、BlockMappings、Piston 和 Entity 数据接口。

Geyser 使用 MIT License。它的 Packet 选择逻辑和碰撞边界可以作为交叉参考。

### 5. ViaProxy

ViaProxy master 在提交 `552dc5e7262c7cbe4e3def5c4f49c7e33032a282` 支持客户端和服务端 26.2。

它的职责是协议代理和 ViaVersion 转换。

`src/main/java/net/raphimc/viaproxy/ViaProxy.java:279-301` 启动 Netty Proxy Server。`protocoltranslator/ProtocolTranslator.java:45-77` 初始化 ViaVersion、ViaBackwards 和其他协议转换器。

仓库只包含协议 Entity Tracker 和 World Packet Rewriter Mixin。没有 Player AABB、重力、流体或本地移动 tick。

它仍把实际 Player 计算交给连接的 Minecraft 客户端。

ViaProxy 使用 GPL-3.0。它不能作为当前 Plugin 的物理依赖，也不能提供需要的算法。

### 6. MCProtocolLib Bot

MCProtocolLib master 在提交 `19783c29ece24bc3f07f8ff08628549527e3de20` 提供通信和 Packet 对象。

`protocol/src/main/java/org/geysermc/mcprotocollib/protocol/ClientListener.java:70-184` 自动处理登录、协议状态切换、KeepAlive、Transfer 和 Configuration。

该监听器没有 Chunk World、实体 tick、Player 碰撞或 Movement Loop。

MCProtocolLib 提供以下可继续复用的部分：

- `ChunkSection` 和 Palette
- Clientbound 世界和实体 Packet
- Serverbound Move Player Packet
- Packet 编解码

它不提供 Player 计算。

### 7. Baritone

Baritone 提供路径规划和输入覆盖。它不实现 Minecraft 物理。

`src/main/java/baritone/pathing/movement/Movement.java:29-32` 直接导入 Minecraft `BlockPos`、`Direction`、`FallingBlockEntity` 和 `AABB`。

`Movement.java:123-143` 只设置跳跃、移动和点击 Input。

`src/main/java/baritone/utils/PlayerMovementInput.java:22-53` 继承 `net.minecraft.client.player.Input`。真实 Minecraft `LocalPlayer` 读取这些 Input 并执行物理。

Baritone 使用 Minecraft 构建插件。它不是独立 Java Library。

Baritone 使用 LGPL-3.0。即使许可兼容，它也没有可移植的 Player 物理代码。

### 8. Spectron

Spectron main 在提交 `deddd9d17b8b9e16bb92c364f38801ce22822e1f` 自称 Headless Minecraft Bot。

实际代码只维护基础状态：

- `core/src/main/java/de/lukasbreuer/spectron/player/PlayerLocation.java:12-33` 保存位置和旋转。
- `module/foundation/SynchronizePlayerPosition.java:16-20` 接受服务端位置并确认 Teleport。
- `connection/packet/outbound/play/PacketSetPlayerPosition.java:27-35` 写固定位置和 `onGround`。
- `chunk/Chunk.java:16-37` 用线性 `List<Block>` 保存方块。

`PacketChunkData.java:23-87` 的 Chunk 解码仍有打印、注释代码和未完成读取路径。

仓库没有 Physics、Collision、Velocity 或 20 TPS Movement Loop 类。

Spectron 使用 GPL-3.0。它既不适合复用，也不能作为正常 Player 计算的参考实现。

### 9. 精简 Vanilla Client Classes

Minecraft 26.2 客户端 JAR 包含准确的以下类：

- `net.minecraft.client.player.LocalPlayer`
- `net.minecraft.world.entity.LivingEntity`
- `net.minecraft.world.entity.Entity`
- `net.minecraft.client.multiplayer.ClientLevel`
- `net.minecraft.world.level.CollisionGetter`
- `net.minecraft.world.phys.shapes.VoxelShape`

但是这些类不是独立物理模块。

`LocalPlayer` 依赖完整 `Minecraft`、`ClientPacketListener`、Input、Abilities、Inventory、Effect、Vehicle 和 Client Level 生命周期。

`ClientLevel` 依赖 Registry、Chunk Source、Entity Storage、Dimension、World Border、Sound、Particle 和客户端事件。

Block State Shape 还会读取 Block Getter、邻居状态和 Entity Collision Context。

本地 26.2 Manifest 指定 Java Runtime major version 25。当前 Velocity Plugin 固定 Java 17。

即使把 Velocity 运行时升级到 Java 25，初始化这些类也需要大部分客户端运行依赖和资源。该方案不再是“精简 Player 计算”。它接近同一 JVM 内启动完整 Minecraft 客户端。

Plugin 也不能把 Minecraft 客户端 JAR 打入自己的发布物。复制反编译 Vanilla 代码还会引入额外的 Minecraft 许可风险。

因此只可把 Vanilla 26.2 作为行为基准和数据生成环境。不能把它作为 Plugin 运行时依赖。

### 10. 最简实现方案

#### 10.1 运行时组成

Plugin 只需要四个运行时责任。它们可以先放在现有 Automation 所有权内，不需要建立通用游戏引擎。

1. `PlayerState`
   - 保存位置、旋转、速度、Pose、生命、死亡、Effect 和碰撞标志。
2. `WorldState`
   - 保存当前 Dimension、已加载 Chunk Section、Block State ID 和 World Border。
3. `PlayerPhysics`
   - 每 tick 输入 `PlayerState` 和只读 Block 查询。
   - 输出新位置、速度和碰撞标志。
4. Packet 适配
   - 把 MCProtocolLib Packet 写入状态。
   - 按 Vanilla 规则发送 Move Player Packet。

实体状态继续由当前任务要求的 Entity Map 保存。

初版 Player Physics 不需要把 Entity Map 接入本地实体推挤。该功能已经在 `normal-physics-acceptance.md` 中延后。

#### 10.2 每 tick 最小顺序

```text
应用本 tick 前到达的服务端状态
-> 死亡时停止普通 Player 计算
-> 更新流体接触和流体推力
-> 选择水、岩浆或空气/地面计算
-> 用 Block Collision 裁剪位移
-> 更新位置、速度、onGround 和 horizontalCollision
-> 按变化选择 Move Player Packet
-> 发送 Client Tick End
```

Packet 处理和物理 tick 必须在同一个 Backend Event Loop 串行执行。

#### 10.3 方块碰撞

最小碰撞内核只需要以下类型：

- `Vec3`
- `Aabb`
- `Shape`，内容是 `Aabb[]`
- `CollisionResult`
- `BlockView`
- 逐轴或 Swept AABB 裁剪函数

当前验收只有零输入外力轨迹。可以先使用 Vanilla 风格的 Y、X、Z 轴裁剪和台阶处理。该范围比移植完整 Minestom `Entity` 小。

如果实现团队选择移植 Minestom `BlockCollision`，必须把它视为一个带 Apache-2.0 归属的内部实现。仍然需要单独实现 Vanilla 流体和 Player 速度公式。

#### 10.4 物理数据

运行时读取项目生成的 `physics-data-26.2`。

数据至少包含：

- `blockStateId -> shapeId`
- 方块摩擦
- 速度因子
- 流体类型和 Level
- 攀爬和特殊方块标志
- `shapeId -> Aabb[]`

该方案沿用 `physics-data-source.md` 的结论。

普通 Plugin 构建和运行不加载 Minecraft、Minestom 或 Geyser。

#### 10.5 明确不做

初版不需要以下内容：

- 完整 `ClientLevel`
- 完整 Minestom `Instance`
- Geyser Session
- Entity AI
- Pathfinding
- Rendering
- Inventory Simulation
- 本地实体推挤
- Piston 逐 tick 推动
- Vehicle、Elytra 和主动移动输入

这些内容都不能改善当前“Shadow 受击后表现正常”的最小闭环。

### 11. 集成成本排序

| 方案 | 初始成本 | 26.2 精度 | 运行依赖 | 后续维护 | 判断 |
| --- | --- | --- | --- | --- | --- |
| 直接依赖 Minestom | 中 | 中 | 很高，且要求 Java 25 | 高 | 排除 |
| 复制 Geyser CollisionManager | 高 | 低到中。它解决 Bedrock 差异 | 高 | 高 | 排除 |
| 嵌入 ViaProxy 或 Spectron | 高 | 不提供物理 | 高 | 高 | 排除 |
| 运行精简 Vanilla classes | 极高 | 高 | 接近完整客户端，Java 25 | 极高 | 排除 |
| 移植 Minestom 碰撞内核并自写 Player 计算 | 中 | 碰撞中，Player 公式可对齐 Vanilla | 低 | 中 | 可选 |
| 自写窄 Player 计算和窄碰撞内核 | 中 | 可按验收对齐 26.2 | 最低 | 中 | 推荐 |

推荐最后一项。

它没有最少的代码行数，但有最少的系统依赖、协议边界和生命周期耦合。

## Files Found

- `plugin/build.gradle.kts:12-34` - 当前 Java 17、MCProtocolLib 26.2 和 Netty 运行边界。
- `.trellis/spec/backend/velocity-plugin.md` - Patch、Plugin、MCProtocolLib 和 Minecraft 26.2 边界。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/vanilla-client-physics-owner.md` - Vanilla `LocalPlayer` 责任和精确调用链。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/physics-data-source.md` - Minecraft 26.2 物理数据生成方案。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/normal-physics-acceptance.md` - 初版 Player 计算验收边界。
- `Minestom/src/main/java/net/minestom/server/collision/PhysicsUtils.java:30-80` - 通用实体运动和空气阻力。
- `Minestom/src/main/java/net/minestom/server/collision/CollisionUtils.java:119-146` - 可用 `Block.Getter` 调用的碰撞入口。
- `Minestom/src/main/java/net/minestom/server/collision/BlockCollision.java:99-210` - Swept AABB 碰撞内核。
- `Geyser/core/src/main/java/org/geysermc/geyser/level/physics/CollisionManager.java:295-400` - Bedrock 到 Java 的碰撞修正。
- `Geyser/core/src/main/java/org/geysermc/geyser/translator/protocol/bedrock/entity/player/input/BedrockMovePlayer.java:66-226` - Java Move Player Packet 选择。
- `ViaProxy/src/main/java/net/raphimc/viaproxy/protocoltranslator/ProtocolTranslator.java:45-77` - 协议转换初始化，无 Player 物理。
- `Baritone/src/main/java/baritone/pathing/movement/Movement.java:123-143` - 向真实客户端注入 Input。
- `Spectron/core/src/main/java/de/lukasbreuer/spectron/module/foundation/SynchronizePlayerPosition.java:16-20` - 只接受位置和确认 Teleport。
- `MCProtocolLib/protocol/src/main/java/org/geysermc/mcprotocollib/protocol/ClientListener.java:70-184` - 登录、KeepAlive 和协议状态处理。

## External References

- [Minestom PhysicsUtils](https://github.com/Minestom/Minestom/blob/93f9d6c7b3698579afae05b3863bf328ecbacd60/src/main/java/net/minestom/server/collision/PhysicsUtils.java)
- [Minestom CollisionUtils](https://github.com/Minestom/Minestom/blob/93f9d6c7b3698579afae05b3863bf328ecbacd60/src/main/java/net/minestom/server/collision/CollisionUtils.java)
- [Geyser CollisionManager](https://github.com/GeyserMC/Geyser/blob/9163e0503362cc2357d3b1ff2c9c1a22927cd8b8/core/src/main/java/org/geysermc/geyser/level/physics/CollisionManager.java)
- [Geyser BedrockMovePlayer](https://github.com/GeyserMC/Geyser/blob/9163e0503362cc2357d3b1ff2c9c1a22927cd8b8/core/src/main/java/org/geysermc/geyser/translator/protocol/bedrock/entity/player/input/BedrockMovePlayer.java)
- [ViaProxy](https://github.com/ViaVersion/ViaProxy/tree/552dc5e7262c7cbe4e3def5c4f49c7e33032a282)
- [MCProtocolLib ClientListener](https://github.com/GeyserMC/MCProtocolLib/blob/19783c29ece24bc3f07f8ff08628549527e3de20/protocol/src/main/java/org/geysermc/mcprotocollib/protocol/ClientListener.java)
- [Baritone Movement](https://github.com/cabaletta/baritone/blob/ad627c83cc0ac6059d7a1b29eca4e54214584b5e/src/main/java/baritone/pathing/movement/Movement.java)
- [Spectron](https://github.com/breuerlukas/spectron/tree/deddd9d17b8b9e16bb92c364f38801ce22822e1f)
- [Minecraft 26.2 client artifact](https://piston-data.mojang.com/v1/objects/2dc72797acbc1b63fc16a11c4ac393605f453754/client.jar)

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`
- `.trellis/spec/language/java.md`

## Caveats / Not Found

- Minestom 的碰撞算法不是 Vanilla 26.2 客户端源码的独立发布版本。采用它需要用固定场景对比 Vanilla。
- Geyser 的碰撞数据包含 Java 与 Bedrock 差异修正。不能直接当作纯 Java Block Shape 数据。
- ViaProxy 的 Entity Tracker 只服务协议转换。它不提供可用于 Player 碰撞的完整实体状态。
- Spectron 当前代码没有可用 Player 物理。
- Baritone 当前提交绑定 Minecraft 客户端。它没有独立 Physics API。
- Minecraft 26.2 要求 Java 25。当前 Plugin 使用 Java 17。
- 技术研究不能替代 Minecraft 内容和派生数据的法律审查。
- 推荐方案仍需对照 Vanilla 26.2 验证水流、台阶、墙、平台边缘和爆炸速度累加。

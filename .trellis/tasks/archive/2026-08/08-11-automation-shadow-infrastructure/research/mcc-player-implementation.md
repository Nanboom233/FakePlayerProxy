# Research: Minecraft Console Client 玩家物理实现

- Query: Minecraft Console Client 当前版本如何实现本地 Player、TerrainAndMovements、PlayerPhysics、CollisionDetector、World、Chunk、实体、外部 Velocity、爆炸和 Movement Packet。哪些结构适合本项目用 Java 重写。
- Scope: mixed
- Date: 2026-08-13

## Findings

### 调查基线

本报告检查 MCC `master` 的提交 `d50e90d8600f28ad8a66f713317aed05c1fc885a`。

该提交日期为 2026-08-11。

MCC 明确接受协议 `776`。`ProtocolHandler` 把协议 `776` 映射到 Minecraft `26.2`。

Terrain、Inventory 和 Entity Handling 的支持上限也是 `26.2`。

但是，`PlayerPhysics` 和 `PhysicsConsts` 明确声明它们复现的是 Vanilla `1.21.11`。MCC 没有针对 `26.1` 或 `26.2` 的物理分支。

因此，MCC 的协议和状态解析支持 `26.2`。它的物理模型不能直接证明支持 `26.2`。

### 实际运行结构

`McClient` 长期持有以下对象：

- 一个 `World`
- 一个玩家 `Location`
- 一个 `PlayerPhysics`
- 一个 `MovementInput`
- 一个 `Dictionary<int, Entity>`

`TerrainAndMovements` 默认关闭。启用后，协议处理器解析 Chunk 和 Block 更新。

协议处理器使用两个线程。

- Reader 线程读取 Packet，并把 Packet 放入队列。
- Updater 线程按顺序处理 Packet，并以 20 TPS 调用 `McClient.OnUpdate()`。

这使 Packet 更新、玩家物理和移动发送处于同一个顺序域。

`McClient.OnUpdate()` 的物理调用链是：

```text
UpdatePathfindingInput
-> PlayerPhysics.UpdateEnvironment
-> PlayerPhysics.ApplyInput
-> PlayerPhysics.Tick
-> 同步 McClient.location
-> Protocol18.SendLocationUpdate
```

这条调用链真实存在。`PlayerPhysics` 不是孤立的未使用代码。

### PlayerPhysics

`PlayerPhysics.Tick(World)` 处理以下内容：

- 速度阈值归零
- 跳跃
- 输入缩放
- Ground 和 Air 运动
- Water 运动
- Lava 运动
- Gravity
- Drag
- Block Friction
- Block Speed Factor
- Climbable Block
- Sneak Edge Backoff
- Step Up
- Block Collision
- `onGround`
- `horizontalCollision`

玩家位置和速度保存在 `PlayerPhysics.Position` 和 `PlayerPhysics.DeltaMovement`。

玩家包围盒使用固定宽度 `0.6` 和固定高度 `1.8`。

`Tick()` 只更新本地状态。`McClient.OnUpdate()` 在 Tick 后调用 Packet 发送器。

### CollisionDetector

`CollisionDetector` 使用 swept AABB 的搜索范围收集方块碰撞盒。

它按轴裁剪位移。它还处理 Step Up 候选高度。

碰撞源只有 Block Shape。

它不查询以下碰撞源：

- Entity
- World Border
- Moving Piston
- Vehicle

`BlockShapes` 把 Block State ID 映射到一组本地 AABB。

它读取内嵌的 `BlockShapeData.json`。该文件约为 635 KB。

生成工具从 PrismarineJS `minecraft-data` 下载 `blockCollisionShapes.json`。工具只压缩 AABB。它不从 Vanilla 运行时生成数据。

当 Shape 数据不存在时，MCC 使用回退规则。

- Solid Material 返回完整方块碰撞盒。
- 其他 Material 返回空碰撞盒。

该回退规则会把复杂形状错误地变成完整方块或空气。

### World 和 Chunk

`World` 使用 `ConcurrentDictionary<(chunkX, chunkZ), ChunkColumn>`。

每个 `ChunkColumn` 保存一个 Section 数组。每个 `Chunk` 保存 `16 * 16 * 16` 个 Block。

协议层处理以下世界更新：

- Chunk Data
- Multi Block Change
- Block Change
- Unload Chunk
- Respawn 后清空 World

`World.GetBlock()` 在 Chunk 或 Section 未加载时返回 Air。

该行为适合寻路工具保持可用。它不适合权威的 Shadow 物理。Shadow 若把未知 Chunk 当作 Air，玩家可能在 Chunk 未完成时下坠并发送错误位置。

MCC 保存的是可查询的完整 Block State 网格。它没有专门的玩家附近碰撞缓存。

### Entity

MCC 使用 `Dictionary<int, Entity>` 保存实体。

协议层处理以下实体输入：

- Spawn
- Destroy
- Relative Move
- Rotation
- Teleport
- Position Sync
- Metadata
- Attributes
- Effects
- Equipment
- Velocity

Entity 保存位置、旋转、类型、Pose、Metadata、Attributes 相关事件、Effects 和 Equipment。

Entity 不保存以下物理状态：

- Velocity
- AABB
- Scale 后的尺寸
- Pushability
- Passenger 关系
- Vehicle 关系
- Collision 状态

`CollisionDetector` 不读取 Entity Map。

因此，MCC 的 Entity Handling 是观察和 Bot 事件系统。它不是玩家实体碰撞系统。

### 外部 Velocity

协议层可以解析 `EntityVelocity`。

对于 `1.21.9+`，MCC 读取 LP Vec3。旧版本读取三个 Short。

解析结果只调用 `McClient.OnEntityVelocity()`。

`OnEntityVelocity()` 只把事件发给 ChatBot。它不执行以下操作：

- 不更新 Entity
- 不识别 Self Entity
- 不设置 `PlayerPhysics.DeltaMovement`

`playerPhysics` 的全部外部引用中没有速度注入调用。

因此，普通攻击击退没有接入 MCC 的玩家物理。

### 爆炸

MCC 可以解析现代 Explosion Packet。

对于 `1.21.2+`，它读取 `hasPlayerKnockback`。如果该值为 true，它读取三个 Double。

MCC 随后丢弃这三个数值。

`McClient.OnExplosion()` 只向 ChatBot 发布爆炸位置、强度和受影响方块数量。

它不把爆炸击退加到 `PlayerPhysics.DeltaMovement`。

因此，爆炸击退没有接入 MCC 的玩家物理。

### 服务端位置更新

MCC 收到 Player Position And Look 后会更新本地位置并回复 Teleport Confirm。

对于现代 Packet，MCC 读取并丢弃 Delta X、Delta Y 和 Delta Z。

`McClient.UpdateLocation()` 随后调用 `PlayerPhysics.Teleport()`。该方法把速度清零。

这与现代 Vanilla 的相对位置和相对速度语义不等价。

该逻辑适合基本同步。它不能直接用于受击后的精确状态修正。

### Movement Packet

MCC 的 Movement Packet 发送器是最成熟的可参考部分。

它保存以下上次发送状态：

- Position
- Rotation
- `onGround`
- `horizontalCollision`
- Position Reminder

对于现代协议，它使用以下规则：

- 位移平方大于 `4.0E-8` 时发送 Position。
- 每 20 Tick 强制发送 Position。
- Rotation 变化时发送 Rotation。
- Ground 或 Horizontal Collision 变化时发送 Movement。
- Position 和 Rotation 同时变化时发送 Position And Rotation。
- `1.21.5+` 使用 Movement Flags 的第二位发送 Horizontal Collision。

该结构与 Vanilla `LocalPlayer.sendPosition()` 的职责相同。

本项目可以重写该状态机。不能直接复用 MCC 的 Packet 编码，因为本项目使用 MCProtocolLib Packet。

### 已声明但未接通的物理状态

`PlayerPhysics` 声明了以下字段：

- Slow Falling
- Levitation
- Creative Flying
- Flying
- Stuck Speed Multiplier
- Player Width
- Player Height
- Movement Speed

调用方没有把 Effect、Ability、Pose、Scale 或 Attribute 更新接入这些字段。

以下已知行为也不完整：

- Slime Block 落地只把 Y 速度归零。源码标记了待实现 Bounce。
- Water 只检查脚部和半身 Block 是否为 Water。
- Water 不计算 Fluid Height。
- Water 不计算 Flow Vector。
- Bubble Column 只被视为 Water。
- Lava 只使用 Block 类型判断。
- Player Height 不随 Pose 变化。
- Gravity Attribute 不接入物理。
- Movement Speed Attribute 不接入物理。
- Jump Strength 和 Scale Attribute 不接入物理。
- Entity Push 不存在。
- Piston Movement 不存在。
- World Border Collision 不存在。
- Vehicle Physics 不存在。
- Elytra 常量存在，但没有 Elytra 运动实现。

MCC 的注释称该引擎 faithfully replicating Vanilla。实际源码仍是明显简化模型。

### Respawn 和状态清理

MCC 支持生命更新和可配置的自动 Respawn。

Respawn 时，它清除 World、Entity、Inventory 和任务。

但是，它没有显式重建 `PlayerPhysics`。`physicsInitialized` 也没有在 `OnRespawn()` 中归零。

后续服务端位置 Packet 通常会通过 `Teleport()` 修正位置并清零速度。这是隐式恢复，不是完整的玩家物理生命周期。

### 依赖和许可证

MCC 当前使用 .NET 10。

完整应用依赖多个 UI、Chat、Scripting 和 Network 包。PlayerPhysics、CollisionDetector、Aabb 和 Vec3d 本身主要依赖 .NET 标准库和 MCC 的 World、Block 类型。

本项目不能把 MCC 当作 Java Runtime 依赖。

MCC 使用 CDDL 1.0。

CDDL 对包含 MCC 代码或 MCC 修改代码的文件施加源码提供和许可证要求。

逐行翻译 MCC 的 C# 实现为 Java 可能形成衍生代码。项目不能在未确认许可证策略时复制或机械翻译这些文件。

更稳妥的方式是：

- 使用 MCC 识别模块边界和测试场景。
- 使用固定 Minecraft 26.2 Vanilla 实现确认行为。
- 在 Java 中独立实现。
- 不复制 MCC 的源码结构、注释或表达式。

MCC 的 Block Shape 数据来自 PrismarineJS。该数据还有单独的来源和许可边界。

### 适合 Java 重写的结构

以下结构适合采用，但需要独立实现：

1. 一个玩家状态保存 Position、Velocity、Rotation 和 Collision Flags。
2. 一个 World 保存已加载 Chunk 的 Block State ID。
3. 一个静态表把 Block State ID 映射到 Shape。
4. 一个 AABB Collision 函数裁剪每 Tick 位移。
5. 一个 20 TPS 顺序循环先处理 Packet，再推进 Physics，再发送 Movement。
6. 一个 Movement Sender 保存上次发送状态并选择四种 MCProtocolLib Movement Packet。
7. Respawn、Dimension Change 和 Fresh Login 清空玩家物理与 World。

这些结构直接对应 Vanilla 客户端职责。它们不要求采用 MCC 的类层级。

### 不适合采用的行为

本项目不应采用以下 MCC 行为：

- 未加载 Chunk 直接当作 Air 并继续物理。
- 固定站立玩家 AABB。
- 只按 Material 判断流体。
- 忽略 Fluid Height 和 Flow。
- 忽略 Entity Collision。
- 忽略 Explosion Knockback。
- 把 Self Entity Velocity 只发布为事件。
- 忽略 Position Packet 的 Delta。
- Teleport 一律清零速度。
- 用少量硬编码 Material 实现方块物理属性。
- 在缺少 Shape 时把所有 Solid Block 回退为 Full Cube。

### 对本项目的最简方案

最简方案不是移植完整 MCC。

最简方案是实现一个固定 `26.2` 的无输入客户端物理切片。

该切片包含以下部分：

1. `PlayerState`

   保存自身 Entity ID、Position、Velocity、Rotation、Pose、Scale、Abilities、Movement Attributes、Effects、生命、死亡和 Collision Flags。

2. `WorldState`

   保存当前 Dimension 和服务端已加载的 Chunk Section。它处理 Chunk、Block Update、Section Block Update、Unload 和 Respawn。

3. `PhysicsData`

   使用固定 26.2 数据。它提供 Block Shape、Friction、Speed Factor、Fluid 和 Entity Dimension。

4. `PlayerPhysics`

   初版只处理零输入状态下仍会发生的客户端运动。

   - 外部 Velocity
   - Explosion Knockback
   - Gravity
   - Drag
   - Block Collision
   - Step Up
   - Fluid Height
   - Fluid Flow
   - Bubble Column
   - Climbable Block
   - Block Speed Factor
   - Pose AABB

5. `MovementSender`

   使用 MCProtocolLib Packet。它保存上次发送状态，并执行变化触发和 20 Tick Position Reminder。

6. 顺序模型

   Packet Handler 和 Physics Tick 使用同一个玩家 Backend Event Loop。未知 Chunk 暂停位置推进。收到所需 Chunk 后继续。

初版 Shadow 没有输入。因此，它不需要 MCC 的 Pathfinding、MovementInput、Jump、Sprint、Sneak Edge、Creative Flying 或 Elytra。

这会显著小于 MCC 的完整 `PlayerPhysics`。

但是，受击正常表现仍要求保留 Velocity、Gravity、Collision、Fluid 和 Movement Sender。不能只发送服务端给出的速度。

如果初版要求实体推挤，则还需要 Entity AABB 和 Push 计算。MCC 不能提供该实现，只能提供 Entity Packet Tracker 的基本参考。

## Files Found

- `MinecraftClient/McClient.cs:67-85`：World、Location、PlayerPhysics、MovementInput 和功能开关的所有权。
- `MinecraftClient/McClient.cs:631-720`：20 TPS PlayerPhysics 调用链和 Movement Packet 发送。
- `MinecraftClient/McClient.cs:3521-3544`：Respawn 时的 World 和 Entity 清理。
- `MinecraftClient/McClient.cs:3680-3731`：服务端位置同步和 Physics Teleport。
- `MinecraftClient/McClient.cs:4450-4461`：Entity Velocity 只发布 ChatBot Event。
- `MinecraftClient/McClient.cs:4601-4610`：Explosion 只发布 ChatBot Event。
- `MinecraftClient/Physics/PlayerPhysics.cs:64-241`：20 TPS 玩家运动、Gravity、Friction 和 Drag。
- `MinecraftClient/Physics/PlayerPhysics.cs:334-391`：Block Collision 和 Collision Flags。
- `MinecraftClient/Physics/PlayerPhysics.cs:478-557`：硬编码方块属性和简化流体判断。
- `MinecraftClient/Physics/CollisionDetector.cs:14-201`：Block AABB Collision 和 Step Up。
- `MinecraftClient/Physics/BlockShapes.cs:14-206`：Prismarine Shape 数据和回退逻辑。
- `MinecraftClient/Mapping/World.cs:43-61`：Concurrent Chunk Column Map。
- `MinecraftClient/Mapping/World.cs:392-493`：Chunk 存储、Block 查询和 World 清理。
- `MinecraftClient/Mapping/Chunk.cs:8-67`：完整 Section Block 数组。
- `MinecraftClient/Mapping/Entity.cs:11-117`：Entity Tracker 的数据字段。
- `MinecraftClient/Protocol/Handlers/Protocol18.cs:149-215`：Terrain 和 Entity 的 26.2 上限。
- `MinecraftClient/Protocol/Handlers/Protocol18.cs:293-332`：Packet 和 20 TPS 的顺序循环。
- `MinecraftClient/Protocol/Handlers/Protocol18.cs:1550-1617`：Player Position And Look 处理。
- `MinecraftClient/Protocol/Handlers/Protocol18.cs:1623-1650`：Chunk Data 入口。
- `MinecraftClient/Protocol/Handlers/Protocol18.cs:1923-2050`：Block 更新。
- `MinecraftClient/Protocol/Handlers/Protocol18.cs:2107-2120`：Unload Chunk。
- `MinecraftClient/Protocol/Handlers/Protocol18.cs:2751-2769`：Entity Velocity 解析。
- `MinecraftClient/Protocol/Handlers/Protocol18.cs:2951-3003`：现代 Explosion 和被丢弃的 Knockback。
- `MinecraftClient/Protocol/Handlers/Protocol18.cs:5453-5612`：Movement Packet 选择和发送状态。
- `MinecraftClient/Protocol/ProtocolHandler.cs:179-203`：协议 `776` 可用。
- `MinecraftClient/Protocol/ProtocolHandler.cs:411-416`：Minecraft `26.2` 到协议 `776` 的映射。
- `MinecraftClient/Physics/BlockShapeData.json`：内嵌 Prismarine Block Shape 数据。
- `tools/gen_block_shapes.py:1-133`：Prismarine 数据下载和压缩工具。
- `LICENSE.md:1-82`：CDDL 1.0 和源码提供要求。
- `MinecraftClient/MinecraftClient.csproj:1-56`：.NET 10、内嵌 Shape 数据和应用依赖。

## External References

- [Minecraft Console Client repository](https://github.com/MCCTeam/Minecraft-Console-Client/tree/d50e90d8600f28ad8a66f713317aed05c1fc885a)
- [PlayerPhysics.cs](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Physics/PlayerPhysics.cs)
- [CollisionDetector.cs](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Physics/CollisionDetector.cs)
- [BlockShapes.cs](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Physics/BlockShapes.cs)
- [McClient.cs](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/McClient.cs)
- [Protocol18.cs](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Protocol/Handlers/Protocol18.cs)
- [MCC CDDL 1.0 license](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/LICENSE.md)

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`
- `.trellis/spec/language/java.md`
- `.trellis/spec/guides/code-reuse-thinking-guide.md`
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/vanilla-client-physics-owner.md`
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/vanilla-physics-boundary-review.md`
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/physics-data-source.md`

## Caveats / Not Found

- MCC 没有发布独立的 Player Physics Library。
- MCC 支持协议 776，但物理源码只声称对应 Vanilla 1.21.11。
- MCC 没有把 Self Entity Velocity 接入 `PlayerPhysics`。
- MCC 没有把 Explosion Knockback 接入 `PlayerPhysics`。
- MCC 没有 Entity Collision、World Border、Piston 或精确 Fluid Flow。
- MCC 的 Block Shape 文件没有在文件头记录 Minecraft 版本和源数据提交。
- 本报告没有证明 MCC 当前 Block Shape 数据精确对应 26.2。
- CDDL 影响代码复制和翻译方式。该技术报告不替代法律意见。

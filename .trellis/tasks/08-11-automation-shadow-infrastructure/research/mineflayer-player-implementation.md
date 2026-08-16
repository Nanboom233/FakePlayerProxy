# Research: Mineflayer Player Implementation

- Query: Mineflayer、prismarine-physics、prismarine-world、prismarine-chunk 和 prismarine-block 如何实现本地 Player 计算。哪些设计和算法可供 Java Velocity Plugin 参考。
- Scope: mixed
- Date: 2026-08-13

## Findings

### Source Snapshot

本次研究直接读取以下仓库的当前默认分支源码。

| Repository | Commit | Observed package version |
| --- | --- | --- |
| Mineflayer | `a89e76b7a45e790247be77b5c18e155efd89315d` | `4.37.1` |
| prismarine-physics | `a5353a922f1dee075aa797cb53be31919f9e1f46` | `1.11.1` |
| prismarine-world | `e1296ec37029b0032e41aab810b0f22a00e63453` | `3.7.0` |
| prismarine-chunk | `ce60c5fcd09c6198e28de011eec3f8811b96923d` | `1.41.0` |
| prismarine-block | `c52fba46b794429d89806bd63bb0e9de1be8f981` | `1.23.0` |
| minecraft-data | `8a80816cbfb3fe2b609f2cde4e57796c8033af61` | data repository |

### Module Boundary

Mineflayer 把本地玩家计算拆为五层。

1. `minecraft-protocol` 负责收发 Packet。
2. Mineflayer `blocks` Plugin 把 Chunk Packet 和 Block Update Packet 写入本地 World。
3. `prismarine-world` 负责按 Chunk 坐标保存和查询已加载 Chunk。
4. `prismarine-chunk` 负责 Chunk Section、Palette 和 Block State ID。
5. `prismarine-block` 把 Block State ID 转换为方块属性和碰撞 AABB。
6. Mineflayer `physics` Plugin 驱动 20 TPS tick，接收服务端外力，并发送 Movement Packet。
7. `prismarine-physics` 只执行一次玩家物理步进。

Mineflayer 没有把整个客户端实现放进一个 Bot 类。Mineflayer 用 Packet Plugin 维护状态，用 `PlayerState` 生成一次计算快照，再把结果写回玩家实体。

该边界适合本项目，但本项目不需要复制 Prismarine 的通用多版本层。FakePlayerProxy 已固定 Minecraft 26.2。

### Tick Call Chain

Mineflayer 的当前调用链如下。

```text
setInterval(50 ms)
-> doPhysics()
-> accumulator 执行最多 4 个补偿 tick
-> tickPhysics(now)
-> 检查玩家位置有效
-> 检查当前位置 Chunk 已加载
-> new PlayerState(bot, controlState)
-> physics.simulatePlayer(state, world)
-> state.apply(bot)
-> updatePosition(now)
-> 选择 position / look / position_look / flying Packet
```

证据：

- Mineflayer `lib/plugins/physics.js:14-20` 固定 50 ms，并把 `bot.blockAt()` 包装成最小 World 接口。
- Mineflayer `lib/plugins/physics.js:61-75` 使用时间累加器，并限制每帧最多补偿四个 tick。
- Mineflayer `lib/plugins/physics.js:78-88` 在 Chunk 可用时执行 `simulatePlayer()` 和 Movement Packet 发送。
- Mineflayer `lib/plugins/physics.js:483-490` 在 Login 后启动定时器，在 End 后停止定时器。

本项目不应直接复制 Node 定时器模型。Plugin 已能把 Packet 处理和玩家状态修改放在同一个 Backend EventLoop。最简单的线程模型是共享一个 20 TPS 调度器，再把每个玩家 tick 投递到该玩家的 Backend EventLoop。这样 Packet 更新和物理 tick 不会并发修改同一份状态。

### PlayerState

`prismarine-physics` 的 `PlayerState` 是一次 tick 的可变输入和输出。

它从 Bot 复制以下状态：

- Position
- Velocity
- `onGround`
- Water、Lava 和 Web 状态
- 水平碰撞和垂直碰撞状态
- Elytra 状态
- Jump cooldown
- Yaw 和 Pitch
- Control State
- Attribute
- Jump Boost、Speed、Slowness、Dolphin's Grace、Slow Falling 和 Levitation
- Boots 的 Depth Strider
- Chest Slot 的 Elytra

`simulatePlayer()` 修改该快照。`apply()` 只把计算结果写回 Bot。

证据：`prismarine-physics/index.js:803-867`。

本项目可以沿用“状态对象进入一步物理计算”的形状，但无需每 tick 分配并复制一个 `PlayerState`。每名在线玩家已经有一个 `AutomationService`。该服务可长期持有唯一的 Player Physics State。物理函数直接更新该状态。这样可避免 20 TPS 下的重复对象和 NBT 读取。

Shadow 的初始零输入状态只需要把所有 Control State 设为 false。外部 Velocity 和重力仍会移动玩家。

### World, Chunk, And Block Data

Mineflayer 的 `blocks` Plugin 在 Login 时创建 World。它按 Packet 维护已加载 Chunk。

- `map_chunk` 创建或取得 Chunk，并调用 `Chunk.load()` 解码 Section 数据。
- `unload_chunk` 删除 Chunk。
- `block_change` 和 `multi_block_change` 修改 Block State ID。
- `update_light` 修改光照。玩家物理不读取光照。
- Login 或 Respawn 切换世界时，Plugin 卸载旧世界 Chunk。

证据：

- Mineflayer `lib/plugins/blocks.js:23-67`
- Mineflayer `lib/plugins/blocks.js:242-275`
- Mineflayer `lib/plugins/blocks.js:301-375`
- Mineflayer `lib/plugins/blocks.js:513-545`

`prismarine-world` 的同步接口只提供 `getColumn()`、`setColumn()`、`unloadColumn()`、`getBlock()` 和 `setBlockStateId()` 等查询和更新。持久化、异步生成和光照不是 Player Physics 的必要部分。

`prismarine-block` 使用 `registry.blockCollisionShapes`。它先按 Block 名称找到 Shape ID，再按 Block State metadata 选择 State Shape。一个 Shape 是一组本地方块坐标 AABB。

证据：

- `prismarine-world/src/worldsync.js:72-159`
- `prismarine-block/index.js:33-63`
- `prismarine-block/index.js:108-166`

本项目不需要移植 `prismarine-world` 或 `prismarine-chunk` 的完整对象模型。最简 Runtime 数据是：

```text
WorldState
  dimension identity
  minY
  height
  loadedChunks: chunkKey -> ChunkState

ChunkState
  sections: sectionY -> PalettedBlockState[4096]

PhysicsData
  blockStateId -> shapeId + physical flags
  shapeId -> AABB[]
```

光照、Biome、Heightmap、Block Entity、保存和 Chunk 生成都不参与初版无输入玩家位移。它们不应进入最简物理 World。

### Block Collision

`prismarine-physics` 的方块碰撞步骤如下。

1. 用宽度 `0.6` 和高度 `1.8` 创建玩家 AABB。
2. 扫描移动路径附近的所有 Block。
3. 把每个 Block State 的本地 AABB 平移到世界坐标。
4. 分轴裁剪 Y、X 和 Z 位移。
5. 如果发生水平碰撞，尝试 `0.6` 高的 Step Up 路径。
6. 选取水平移动距离更大的路径。
7. 从裁剪前后位移更新碰撞标志和 `onGround`。
8. 将被阻挡轴的 Velocity 清零。
9. 在落到 Slime Block 时反转 Y Velocity。
10. 扫描玩家内部方块，并处理 Web、Bubble Column、Soul Sand 和 Honey Block。

证据：

- `prismarine-physics/index.js:113-142`
- `prismarine-physics/index.js:157-360`

该算法是可用的最小 AABB 参考。它比移植 Vanilla `VoxelShape` 体系小很多。

但是，该实现不是逐版本精确的 Vanilla 复制。

- 源码注释明确承认 Sneak Edge 处理不能按表面公式复现 Vanilla。
- 玩家尺寸固定为 `0.6 x 1.8`。Pose 不改变 Physics AABB。
- 方块摩擦值主要由少数硬编码 Block ID 决定。
- 特殊 Block 行为靠硬编码分支扩展。
- 它没有处理 World Border 碰撞。
- 它没有处理实体碰撞。

因此，本项目可以参考分轴 AABB、Step Up 和碰撞标志算法。项目不能声称直接移植后就等价于 Vanilla 26.2。

### Gravity, Friction, Fluid, And Special Blocks

`prismarine-physics` 的普通地面和空中运动包含：

- 重力 `0.08 block/tick²`
- 空气 Y Drag `0.98`
- 地面摩擦 `blockSlipperiness * 0.91`
- 空中水平惯性 `0.91`
- 小于阈值的 Velocity 归零
- Ladder 和 Vine
- Slime、Soul Sand、Honey、Web 和 Bubble Column
- Levitation 和 Slow Falling

证据：

- `prismarine-physics/index.js:15-110`
- `prismarine-physics/index.js:466-607`

Fluid 流程如下。

1. 用收缩后的玩家 AABB 检查 Water 和 Lava。
2. 从 Fluid Level 和相邻方块计算 Water Flow。
3. 把归一化流向按 `0.014` 加到 Velocity。
4. 运行碰撞移动。
5. 应用 Water 或 Lava 的 Inertia 和 Gravity。
6. 如果玩家水平碰撞且上方空间可用，应用出水冲量。

证据：`prismarine-physics/index.js:622-713`。

本项目初版只需要无输入物理。因此可以删除 Heading、Sprint、Sneak Edge、Jump、Elytra、Firework 和装备附魔分支，除非当前 `/player` action 同一阶段要求它们。外部击退、重力、摩擦、方块碰撞、水流和 Lava 已能形成连续轨迹。

但“删除输入分支”不等于删除服务端状态。Slow Falling、Levitation、Movement Attribute 和可能改变重力的状态仍会改变被动运动。初版必须明确支持哪些 Effect 和 Attribute。

### External Velocity And Explosion

Mineflayer 有两条外力入口。

- `entity_velocity` 取得 Entity ID，并覆盖对应实体的 Velocity。Self Player 也在同一个 Entity Map 中。因此发送给 Self Entity ID 的 Velocity 会进入下一次 Player Physics tick。
- `explosion` 把 `playerKnockback` 加到现有 Self Velocity。它不覆盖现有 Velocity。

证据：

- Mineflayer `lib/plugins/entities.js:281-286`
- Mineflayer `lib/plugins/physics.js:312-327`

本项目应沿用这两个合并规则。

```text
SetEntityMotion(self) -> velocity = packet velocity
Explosion -> velocity += playerKnockback
```

服务端位置纠正是第三条入口。Mineflayer 按 Relative Flag 更新 Position 和 Velocity，并发送 Teleport Confirm。未标记为 Relative 的 Velocity 轴会清零。

证据：Mineflayer `lib/plugins/physics.js:371-451`。

该规则比单纯覆盖 Position 更重要。Shadow 必须在下一次 tick 前完成它。

### Movement Packet

Mineflayer 保存 `lastSent`，然后按变化选择 Packet。

- Position 和 Look 都变化时发送 Position + Look。
- 只有 Position 变化时发送 Position。
- 只有 Look 变化时发送 Look。
- 没有变化时按版本需要发送 Ground-only Packet。
- 即使位置没有变化，也至少每秒发送一次 Position。

证据：Mineflayer `lib/plugins/physics.js:101-203`。

当前 Mineflayer 把现代 `hasHorizontalCollision` 写为 `undefined`。这不满足本项目已经确认的“计算并上报碰撞标志”要求。FakePlayerProxy 应发送实际计算值。

本项目固定 26.2。它不需要 Mineflayer 的版本分支。最简发送器只需保存上次 Position、Rotation、Ground 和 Horizontal Collision，然后创建对应 MCProtocolLib 26.2 Serverbound Move Player Packet。

### Entity Tracking And Entity Push

Mineflayer 的 `entities` Plugin 维护 Entity ID 到 Entity 的 Map。它处理 Spawn、Remove、Relative Move、Absolute Position、Velocity、Metadata、Attribute、Effect、Passenger 和 Vehicle Packet。

证据：Mineflayer `lib/plugins/entities.js:74-113`、`lib/plugins/entities.js:260-350`、`lib/plugins/entities.js:576-588`、`lib/plugins/entities.js:748-795`。

但是，`prismarine-physics` 的 World 接口只有 `getBlock()`。其碰撞搜索只读取 Block Shape。源码没有读取 Entity Map，也没有玩家与实体推挤算法。

因此：

- Mineflayer 的实体表可作为 Packet 状态同步参考。
- Mineflayer 不能作为实体推挤实现参考。
- 使用 Mineflayer 算法时，附近实体不会改变 Self Player 的物理轨迹。
- 完整实体追踪不自动产生实体物理。

如果初版确实要求实体推挤，项目必须从 Vanilla 26.2 单独研究并实现 Self Player 与 Pushable Entity 的水平 Push。该功能不能从 `prismarine-physics` 获得。

### Death, Respawn, Mount, And Readiness

Mineflayer 在以下情况暂停本地物理：

- Login 后尚未收到初始 Position
- Mount
- Death
- Respawn

收到有效 Position 并应答后，它才恢复物理。它在死亡后最多继续发送 20 tick，然后停止 Movement Packet。

证据：Mineflayer `lib/plugins/physics.js:148-160`、`lib/plugins/physics.js:424-490`。

这个状态门控可供 Shadow 参考。它不能原样采用。

- Mineflayer 为旧服务端在 Respawn 后硬编码 1500 ms 延迟。
- 本项目固定 26.2，不应保留旧协议延迟。
- Shadow 是否自动 Respawn 是产品决策，不是 Physics Engine 的默认职责。
- Mount 时需要 Vehicle State Machine。单纯暂停物理只避免发出错误 Self Movement。

### Version Data

`minecraft-data` 当前默认分支的 `protocolVersions.json` 已声明 Minecraft 26.2、Protocol 776 和 Data Version 4903。

但是，当前 `dataPaths.json` 没有 26.2 条目。当前默认分支只提供到 1.21.11 的 PC Block、Collision Shape 和 Entity 数据路径。

因此，当前发布的 Prismarine Runtime 不能证明它拥有 26.2 的完整物理数据。只看到 Protocol 776 不代表 `prismarine-block` 能正确解析 26.2 Block State ID。

这和 `physics-data-source.md` 的结论一致。本项目必须从固定 26.2 Vanilla 环境生成自己的 Block State、Shape 和 Entity Physics 数据。不能把当前 `minecraft-data` 默认分支作为 26.2 权威数据。

### Dependencies And License

所有本次查看的 Prismarine 仓库都声明 MIT License。

- Mineflayer 4.37.1 要求 Node 22 或更高版本。
- Mineflayer 依赖 `minecraft-protocol`、`minecraft-data`、`prismarine-block`、`prismarine-chunk`、`prismarine-world`、`prismarine-physics` 和多个其他 Node 模块。
- `prismarine-physics` 依赖 JavaScript 的 `vec3`、`minecraft-data` 和 `prismarine-nbt`。
- `prismarine-world`、`prismarine-chunk` 和 `prismarine-block` 都是 JavaScript 模块。

Plugin 不能把这些 Node 模块作为 Java 17 运行时依赖。嵌入 Node 也会引入第二个 Runtime、跨线程状态同步和序列化成本。该方案不属于最简实现。

MIT 允许参考、修改和移植代码，但复制实质性代码时必须保留版权和许可声明。`minecraft-data` README 还说明部分数据来自 wiki.vg 和 minecraft.gamepedia.com。数据复用需要单独核对来源要求。

### What Can Be Used As An Algorithm Reference

以下内容适合作为 Java 实现参考：

- 50 ms 固定步长和有限补偿 tick
- Player State、World Query 和 Physics Step 的边界
- Chunk Map 和 Block State Update 模型
- Shape ID 到 AABB 数组的数据模型
- Swept AABB 分轴裁剪
- `0.6` Step Up 路径选择
- `onGround` 和 Collision Flag 的计算
- Gravity、Drag、Friction 和 Velocity Threshold
- Water Flow 和 Fluid Inertia 的基本流程
- Set Entity Motion 覆盖 Velocity
- Explosion Knockback 累加 Velocity
- Teleport Relative Flag 和 Velocity Reset 规则
- Movement Packet 差异发送和每秒 Position 保底
- Login、Death、Respawn 和 Mount 的物理门控

以下内容不能直接照搬：

- Node Timer 和 EventEmitter 生命周期
- 每 tick 构造 `PlayerState` 和读取 NBT
- 通用多版本 Feature Table
- 固定 Player AABB
- 硬编码 Block ID 物理属性
- 当前 Prismarine 26.2 数据状态
- `hasHorizontalCollision = undefined`
- 旧协议 Respawn 延迟
- 没有 Entity Push 的碰撞模型
- Mineflayer 的完整依赖图

### Minimal FakePlayerProxy Design Derived From Mineflayer

Mineflayer 证明，Shadow 不需要完整客户端对象模型。最简实现可以保留四个运行时部分。

```text
AutomationService
  PlayerPhysicsState
  WorldState
  PhysicsEngine
  lastSentMovement
```

`PlayerPhysicsState` 保存 Self Position、Velocity、Rotation、Ground、Horizontal Collision、Fluid、有效 Effect 和运行门控。

`WorldState` 只保存已加载 Chunk 的 Block State ID。它不保存光照、Biome、NBT、纹理或 Chunk 持久化。

`PhysicsEngine.tick()` 使用零输入。它依次执行 Fluid 采样、Velocity 阈值、Gravity 和 Drag、Swept AABB、Step Up、Block Special Effect、Collision Flag 和 Movement Packet 选择。

Packet Event 直接更新这两份状态。

```text
Chunk Packet -> WorldState replace chunk
Forget Chunk -> WorldState remove chunk
Block Update -> WorldState update state ID
Set Entity Motion(self) -> replace velocity
Explosion -> add knockback
Player Position -> apply relative flags, confirm teleport, reset required velocity axes
Set Health / Combat Kill -> update alive gate
Respawn -> clear old world and pause physics until new Position
```

该设计不需要以下独立组件：

- 通用 Entity Component System
- 通用 World API
- Chunk persistence
- Physics Event Bus
- Node Bridge
- 第二个线程安全状态副本
- 每 tick PlayerState Snapshot

实体推挤是唯一不能从 Mineflayer 最简算法取得的已知缺口。如果初版必须覆盖实体推挤，则在同一个 `PhysicsEngine.tick()` 中加入一次邻近 Pushable Entity 查询即可。无需为此移植 Mineflayer Entity 类体系。

## Files Found

- `.trellis/spec/backend/velocity-plugin.md`：当前 Plugin、Patch、Automation 和固定协议边界。
- `.trellis/spec/language/java.md`：Java 实现约定。
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java`：当前 Plugin 的 Automation Service。
- `plugin/src/main/java/com/fakeplayerproxy/protocol/McProtocolLibUpstreamClient.java`：当前 MCProtocolLib 使用边界。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/design.md`：当前 Shadow、Packet Event、World 和 Physics 设计。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/physics-data-source.md`：26.2 Physics Data 来源研究。
- `%TEMP%/fpp-prismarine-research/mineflayer/lib/plugins/physics.js`：Mineflayer Tick、外力、Teleport 和 Movement Packet 实现。
- `%TEMP%/fpp-prismarine-research/mineflayer/lib/plugins/blocks.js`：Mineflayer World Packet 处理。
- `%TEMP%/fpp-prismarine-research/mineflayer/lib/plugins/entities.js`：Mineflayer Entity State 同步。
- `%TEMP%/fpp-prismarine-research/prismarine-physics/index.js`：Player State 和 Physics Step。
- `%TEMP%/fpp-prismarine-research/prismarine-world/src/worldsync.js`：World 同步查询接口。
- `%TEMP%/fpp-prismarine-research/prismarine-block/index.js`：Block State 和 Collision Shape 映射。
- `%TEMP%/fpp-prismarine-research/prismarine-chunk/src/pc/1.21/ChunkColumn.js`：现代 Chunk Section 和 Block State 存储。
- `%TEMP%/fpp-prismarine-research/minecraft-data/data/dataPaths.json`：版本数据路径。
- `%TEMP%/fpp-prismarine-research/minecraft-data/data/pc/common/protocolVersions.json`：26.2 Protocol 身份。

## External References

- [Mineflayer source snapshot](https://github.com/PrismarineJS/mineflayer/tree/a89e76b7a45e790247be77b5c18e155efd89315d)
- [Mineflayer physics Plugin](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/plugins/physics.js)
- [Mineflayer blocks Plugin](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/plugins/blocks.js)
- [Mineflayer entities Plugin](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/plugins/entities.js)
- [prismarine-physics source snapshot](https://github.com/PrismarineJS/prismarine-physics/tree/a5353a922f1dee075aa797cb53be31919f9e1f46)
- [prismarine-world source snapshot](https://github.com/PrismarineJS/prismarine-world/tree/e1296ec37029b0032e41aab810b0f22a00e63453)
- [prismarine-chunk source snapshot](https://github.com/PrismarineJS/prismarine-chunk/tree/ce60c5fcd09c6198e28de011eec3f8811b96923d)
- [prismarine-block source snapshot](https://github.com/PrismarineJS/prismarine-block/tree/c52fba46b794429d89806bd63bb0e9de1be8f981)
- [minecraft-data source snapshot](https://github.com/PrismarineJS/minecraft-data/tree/8a80816cbfb3fe2b609f2cde4e57796c8033af61)

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`
- `.trellis/spec/language/java.md`

## Caveats / Not Found

- 没有在 `prismarine-physics` 中找到任何实体推挤或实体碰撞代码。
- Mineflayer 的 Player Pose 尺寸不完整。源码明确标记 Sleeping、Swimming、Crawling 和 Elytra Hitbox 为 TODO。
- Mineflayer 只近似 Vanilla Player Physics。其注释承认部分 Sneak Edge 行为不能准确复现。
- Mineflayer 当前默认分支没有完整 Minecraft 26.2 数据路径。
- Mineflayer 当前 Movement Packet 没有填写真实 Horizontal Collision Flag。
- 本次研究没有把 Prismarine 测试结果和 Vanilla 26.2 逐 tick 轨迹做数值对比。
- 本次研究没有判断 Minecraft 数据生成结果的法律性质。许可说明不是法律意见。

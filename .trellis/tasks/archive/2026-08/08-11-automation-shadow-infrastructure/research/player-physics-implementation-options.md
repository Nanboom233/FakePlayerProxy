# Research: Player 计算实现方案

- Query: 其他无头客户端和 Java 工具如何实现客户端 Player 计算。FakePlayerProxy 的最简实现是什么。
- Scope: mixed
- Date: 2026-08-13

## 结论

没有现成实现同时满足以下条件：

- 运行在 Velocity Plugin 内。
- 使用 Java。
- 支持 Minecraft 26.2。
- 接管无输入 Player 计算。
- 使用服务端已发送的世界状态。
- 产生正常 Move Player Packet。

最简方案是在 Plugin 内实现固定 Minecraft 26.2 的 Shadow `PlayerPhysics`。

这里的最简只表示运行依赖和内部结构最少。

它不缩减 Plugin 对客户端 Player 计算的责任。

它复用现有 `AutomationService` 的所有权和 Backend EventLoop。

它不引入 Node、.NET、Minestom、Geyser 或 Minecraft Client 运行时。

它使用 MCProtocolLib 收发 Packet 和读取 Chunk Section。

它使用项目生成的固定 26.2 物理数据。

Vanilla 26.2 是行为标准。

Mineflayer 用于核对物理步骤和状态闭环。

MCC 用于核对顺序模型和 Movement Packet 选择。

Minestom 只用于核对方块碰撞算法。

## 现有实现

| 实现 | 实际做法 | 可采用部分 | 不能采用原因 |
| --- | --- | --- | --- |
| Carpet Fake Player | 在后端创建 `ServerPlayer` 子类。切换本地权威。直接运行服务端 `doTick()`。 | 击退、爆炸、输入和 Player tick 的场景参考 | 必须修改后端服务器。没有独立物理模块。不保留同一后端 Player 或连接。 |
| Mineflayer | Packet Plugin 维护 Player 和 World。`prismarine-physics` 每 50 ms 推进一步。随后按状态差异发送 Movement Packet。 | 20 TPS 步骤、外力合并、流体计算、方块 AABB、Movement Packet 选择 | JavaScript 和 Node 22。依赖 Prismarine 数据体系。当前没有完整 26.2 物理数据。不处理实体推挤。 |
| MCC | Packet 更新和 `PlayerPhysics` 在同一顺序线程运行。World 保存 Chunk。每 tick 后发送 Movement Packet。 | 单一顺序域、长期 Player 状态、方块碰撞、20 tick 位置保底 | .NET 10。物理目标仍是 1.21.11。自身 Velocity 和 Explosion 没有接入物理。现代位置 Delta 被丢弃。流体和 Pose 不完整。 |
| Minestom | 服务端 Entity 使用通用运动和方块碰撞内核。 | AABB、Shape、碰撞结果和方块查询边界 | Java 25。不是 `LocalPlayer` 计算。没有完整流体和客户端 Movement Packet。完整依赖过重。 |
| Geyser | 真实 Bedrock 客户端产生轨迹。Geyser 修正轨迹并转换成 Java Movement Packet。 | Movement Packet 选择和碰撞场景参考 | 不产生无头 Player 轨迹。碰撞器依赖完整 `GeyserSession`。 |
| ViaProxy | 转发和转换真实客户端协议。 | 无 | 不实现 Player 物理。 |
| Baritone | 计算输入。真实 Minecraft `LocalPlayer` 执行物理。 | 场景参考 | 不提供独立物理。依赖完整 Minecraft 客户端。 |
| Spectron | 保存位置。确认服务端传送。发送固定位置。 | 无 | 没有 Collision、Velocity 或 20 TPS Player 计算。 |
| Vanilla Client 类 | `LocalPlayer`、`LivingEntity`、`Entity` 和 `ClientLevel` 完成准确计算。 | 唯一行为标准 | 类之间依赖完整客户端。Minecraft 26.2 使用 Java 25。不能作为小型 Plugin 依赖。 |

## 共同实现形状

Mineflayer 和 MCC 都使用相同的有效结构。

```text
Packet 更新 PlayerState 和 WorldState
-> 固定 20 TPS 推进 PlayerPhysics
-> 计算位置、速度和碰撞标志
-> Movement Packet 发送器比较上次发送状态
-> 发送对应 Move Player Packet
```

这个结构不需要完整客户端对象模型。

它也不需要通用游戏引擎。

## 最简运行时

最简运行时保留一个状态所有者、一个计算对象和一份只读数据。

### `AutomationService` 的玩家状态

保存本任务已经要求的玩家状态。

- 实体 ID
- 位置和旋转
- 速度
- Pose 和玩家碰撞箱
- `onGround`
- `horizontalCollision`
- 生命和死亡
- 影响被动运动的能力、属性和效果
- Player 加载状态

该状态是长期状态。

每 tick 不创建完整快照。

### `AutomationService` 的世界状态

只保存 Player 计算需要的服务端世界状态。

- 当前维度
- 最低 Y 和高度
- 已加载 Chunk Section
- Block State ID
- Chunk 加载和卸载
- Block Update 和 Section Update

它直接保存 MCProtocolLib `ChunkSection[]`。

它不创建第二套 Block State 数组。

`ChunkSection` 自带 Biome Palette。

Plugin 不读取或复制该 Palette。

它不保存 Light、Heightmap、Block Entity NBT 或 Chunk 持久化数据。

未知 Chunk 不作为空气。

物理查询进入未知 Chunk 时暂停位置推进。

### `PlayerPhysics`

`PlayerPhysics` 只接受玩家状态、只读方块查询和固定 26.2 物理数据。

每 tick 执行以下顺序：

1. 扫描流体并应用流体推力。
2. 更新 Swimming 标志。
3. 把小于 Vanilla 阈值的速度分量设为零。
4. 使用当前 Pose 和当前速度执行方块 Shape 碰撞。
5. 按 Vanilla 轴顺序和候选高度处理台阶。
6. 更新位置和碰撞标志。
7. 应用碰撞结果和方块速度因子。
8. 应用空气或流体的重力和阻力。
9. 按可用空间选择下一 tick 的 Pose。
10. 更新速度。

Motion Packet 到达时直接替换当前速度。

Explosion Packet 到达时直接累加当前速度。

PlayerPhysics 不保存待应用的外力队列。

Vanilla 26.2 先使用当前速度执行位移。

它随后应用本 tick 的重力和阻力。

Pose 在 tick 末尾更新。

本 tick 位移使用 tick 开始时的碰撞箱。

初版没有主动输入。

因此不需要输入系统、寻路、跳跃、疾跑、潜行、鞘翅或载具物理。

### Movement Packet 状态

`AutomationService` 保存上次发送的位置、旋转和碰撞标志。

它按 Vanilla 26.2 规则选择四种 Move Player Packet。

它至少每 20 tick 发送一次位置。

它在每个 Shadow GAME tick 末尾发送 `ServerboundClientTickEndPacket`。

死亡、未加载或未知 Chunk 不停止 Client Tick End。

不增加独立的 Packet 总线或 Movement Service。

## 数据方案

运行时使用项目内的 `physics-data-26.2`。

最小数据包含：

```text
blockStateId -> shapeId + friction + speedFactor + fluid + specialFlags
shapeId -> AABB[]
```

Pose、玩家尺寸和眼高来自固定 26.2 Player 物理数据。

MCProtocolLib 继续解码 Chunk Palette。

普通 Plugin 构建和运行不加载 Minecraft、Prismarine 或 Minestom。

## 算法来源

实现不逐行翻译 MCC。

MCC 使用 CDDL 1.0。

实现不复制完整 Mineflayer Runtime。

Mineflayer 和 Prismarine 使用 MIT。

实现不依赖完整 Minestom。

如复制 Minestom 碰撞代码，必须保留 Apache-2.0 归属。

最稳妥的实现方式如下：

1. 从 Vanilla 26.2 提取行为和常量。
2. 使用 Mineflayer 核对 Player 物理步骤。
3. 使用 MCC 核对状态顺序和 Movement Packet 选择。
4. 使用 Minestom 核对 AABB 碰撞场景。
5. 在 Plugin 中独立实现固定 26.2 代码。

## 被排除方案

### Node Sidecar

Node Sidecar 可以直接调用 Prismarine。

它会增加第二个运行时、IPC、状态序列化、故障恢复和部署要求。

它还不能解决 26.2 数据和实体推挤缺口。

该方案不是最简方案。

### Carpet 后端权威

Carpet 在后端服务器内创建新的 `ServerPlayer` 子类。

它让服务端直接保留 `doTick()` 产生的位置。

它还为击退、已知速度和本地权威增加 Mixin。

当前任务不修改后端服务器。

它也必须保留同一后端连接。

所以当前任务不能采用 Carpet 的权威切换方案。

### 完整 Minestom 或 Geyser

完整依赖会引入无关的服务端或协议转换对象模型。

它们仍不能提供完整客户端 Player 计算。

该方案不是最简方案。

### 精简 Vanilla Client

`LocalPlayer` 依赖 `LivingEntity`、`Entity`、`ClientLevel`、Registry、Block、Fluid、Effect、Inventory 和网络生命周期。

继续裁剪会变成维护一份 Minecraft 客户端分支。

该方案不是最简方案。

### 只发送服务端 Velocity

服务端只给出初始外力。

客户端必须继续积分重力、阻力、流体和碰撞，并上报 Move Player Packet。

该方案不能形成正常位移。

## 最小所有权边界

初版不需要增加通用框架。

`AutomationService` 继续持有玩家状态、世界状态和 tick 生命周期。

`PlayerPhysics` 只执行一次 Player tick。

共享的 `PhysicsData` 只提供固定 26.2 数据。

碰撞值类型属于 `PlayerPhysics` 的内部实现。

设计不增加 Player 服务、World 服务或 Movement 服务。

Movement Packet 选择保留在 `AutomationService` 的 tick 输出路径。

Chunk Packet 处理直接更新同一服务的世界状态。

玩家 Packet 处理直接更新同一服务的玩家状态。

实体 Map 继续用于已确认的实体状态。

初版本地实体推挤仍不在范围内。

## 顺序和多玩家

Manager 为每个服务在 Backend EventLoop 上创建一个 50 ms 周期任务。

该任务不经过共享调度线程或第二次投递。

服务只保存周期任务的取消句柄。

Packet 更新和物理计算在同一个 EventLoop 串行执行。

不同玩家持有不同的 `AutomationService`。

固定 26.2 `PhysicsData` 可以只读共享。

该方案不增加锁、状态副本或第二个服务所有者。

## 实施判断

推荐采用 Plugin 内固定 26.2 的 Shadow `PlayerPhysics`。

该方案不是代码行数最少的方案。

该方案具有最少的运行依赖、进程边界、状态同步和生命周期耦合。

它也保持已确认的责任边界。

- Patch 只提供通用 Packet 和连接能力。
- Plugin 持有整个客户端 Player 计算。
- 服务端继续负责伤害、死亡、外力初始值和移动裁决。

## Related Research

- `mineflayer-player-implementation.md`
- `mcc-player-implementation.md`
- `java-player-physics-options.md`
- `carpet-fake-player-calculation.md`
- `vanilla-client-physics-owner.md`
- `vanilla-server-physics-owner.md`
- `vanilla-physics-boundary-review.md`
- `physics-data-source.md`

## Caveats

- 现有工具都不能证明其 Player 物理逐 tick 等同 Vanilla 26.2。
- 当前 Prismarine 物理数据不能作为 26.2 权威数据。
- 初版本地实体推挤仍按已确认范围排除。
- 物理数据许可门禁已解除：本项目按用户决定采用
  `minecraft-data-generator` 根 `LICENSE` 的 MIT 条款并保留声明。

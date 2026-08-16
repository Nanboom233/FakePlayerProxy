# 非主动位置实现边界

## 定义

所有玩家控制输入固定为零。

Minecraft 26.2 Vanilla 客户端在该条件下仍会产生或接受的位移、速度、Pose、Passenger 关系和移动上报全部在范围内。

本文件是实施和检查使用的短 manifest。完整源码证据保留在 `vanilla-26.2-all-player-position-factors.md`。

## 责任分类

- CS：Plugin 执行客户端拥有的无输入计算，并发送正常 C2S 结果。
- SA：Plugin 应用服务端 Packet，不重新计算服务端决定。
- RF：Plugin 从 Entity 关系和 attachment 推导 Passenger 位置。
- NP：状态不直接改变位置，只在其他必需状态机读取时保留。

这些缩写只用于覆盖检查，不增加运行时类型。

## Tick

- `AutomationService` 使用现有 20 TPS 服务 Tick。它不创建第二个任务。
- Client game tick 使用 `max(50 ms, serverMillisecondsPerTick)`。
- Client game tick 不超过 20 TPS。
- Freeze 不停止 Local Player tick。
- Client Tick End 在每个 Client game tick 末尾发送。
- Player Loaded 未完成或玩家死亡只跳过普通 Player movement，不停止 Client Tick End。

## SA 输入

- Login 和 Respawn 提供 Entity ID、Dimension、Game Mode 和保留标志。
- Player Position、Rotation 和 LookAt 覆盖或校正自身位置与旋转。
- Entity Motion 替换速度。
- Explosion knockback 累加速度。
- Health 决定生命和死亡。
- Block replacement push、Portal destination、Sleep relocation、Respawn 和 Dimension destination 只消费服务端结果。
- Damage source、Void、Drowning、Burning、Freezing 和 Fall Damage 不在 Plugin 重算。

## CS 自由 Player

- Air、Gravity、Drag 和 Ground Friction。
- Water 和 Lava 的高度、流向、浮力和阻力。
- Levitation、Slow Falling 和 Dolphins Grace。
- Gravity、Scale、Step Height、Movement Efficiency、Water Efficiency 和 Bounciness。
- Ability、Game Mode、Equipment、Pose 和空间适配。
- Block、Entity、World Border 和移动活塞碰撞。
- Vanilla 轴顺序、台阶候选、速度因子和碰撞回弹。
- Bubble Column、Cobweb、Berry Bush、Powder Snow、Honey、Slime、Bed、Soul Sand 和 Climbable。
- 自然下落、落地、普通击退、Explosion 和实体推挤。
- 已经由服务端状态启用的 Flight 或 Glide 持续分支。

## RF 和载具

- 任意 Entity Type 都可以出现在强制 Passenger graph。
- graph 可以嵌套到任意深度。
- 位置从 root 到 leaf 应用固定 attachment。
- 普通 Entity root 只消费服务端 Move、Sync 和 Teleport。
- Minecart 只消费服务端插值。
- Player 作为 Passenger 时不运行自由 Player movement。
- 只有实际 root 的控制权规则选中本地 Player 时才运行客户端载具计算。
- `MovementKind` 覆盖 Boat、Horse、Camel、Pig、Strider、Happy Ghast 和 Nautilus。
- 本地控制 root 发送 Move Vehicle。
- Boat 额外发送零 Paddle。
- Passenger Player 发送 Rot，不发送自由位置 Packet。

## World

- 每个 Plugin `Player` 独立持有 `World`。
- `World` 保存 Dimension、ChunkSection、物理相关 Tag、移动活塞、World Border、Tick Rate 和 Entity registry。
- 只有不可变 `PhysicsData` 跨 Player 共享。
- 未知 Chunk 不进入 World。它不提供碰撞或 Fluid 状态，也不暂停 Player tick。

## Entity

- 运行时继承只保留 `Entity -> LivingEntity -> Player`。
- `EntityDefinition` 提供固定尺寸、Pose、attachment、Metadata schema、默认 Attribute 和 `MovementKind`。
- Entity 创建时使用固定默认值。
- 找不到目标 Entity 的更新 Packet 时直接忽略。
- 找不到 `SetPassengers` vehicle 时忽略整个 Packet。
- 找不到 passenger 时只跳过该 passenger。
- 不增加占位 Entity、pending Packet、关系 Map 或关系 Manager。
- 不增加载具 Entity 子类、`EntityFactory` 或 `EntityBehavior`。

## C2S 输出

- Free Player 选择 PosRot、Pos、Rot 或 StatusOnly。
- 每 20 个实际 Player movement tick 至少发送一次位置。
- Passenger 发送 Rot。
- 本地控制 root 发送 Move Vehicle。
- Boat 发送零 Paddle。
- 服务端 Position 后发送 Accept Teleportation 和准确 PosRot。

## 主动行为排除

- 主动行走、跳跃、疾跑和潜行。
- 主动下车、载具转向、载具跳跃和载具加速。
- 主动开始 Glide、使用 Firework 和切换 Flight。
- 自动 Respawn。

## 数据和运行边界

- 固定数据来自 Minecraft 26.2 生成环境。
- 运行时不加载 Minecraft、Prismarine 或 Minestom。
- 字段和 handler 只保留 Automation 位置计算读取的部分。
- 不要求完整 Vanilla 客户端状态或逐 bit 浮点等价。

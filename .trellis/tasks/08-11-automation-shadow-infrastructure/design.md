# Automation Shadow 设计

## 状态

设计已收敛。

没有未决产品问题。

## 名词

前端和原后端使用同一个 Netty EventLoop。

本文统一称它为连接 EventLoop。

`shadow` 只表示 `AutomationService` 的接管状态。

`Player` 表示 Plugin 的玩家类型。

`Velocity Player` 表示 `com.velocitypowered.api.proxy.Player`。

## Patch 集

### `0001-login-relay.patch`

该 Patch 只保留已完成的 login relay、加密、解密和 raw tunnel。

当前 Patch 中的 Automation、`shadow` 和测试代码必须移出。

### `0002-automation-extension.patch`

该 Patch 只增加通用 Velocity Plugin 能力：

- Packet Event
- MCProtocolLib Packet 发送
- 必要的连接访问
- 可取消的实际登出
- 关闭前端后的配置切换支持

该 Patch 不包含 Plugin 功能。

## 现有代码复用

实施时直接复用当前未提交修改中符合本设计且可用的代码。

不单独建立迁移流程或迁移验收。

## 构建

`plugin/build/server/source` 固定到上游提交 `843a47e2a38325309cd66133149fc9a984f76bb8`。

该目录不能包含修改或未跟踪文件。

构建创建 `plugin/build/server/work`，并按文件名应用全部 Patch。

Release 构建不能复制 `plugin/patch/test/`。

Patch 检查任务才把测试复制到工作树。

构建结束后删除工作树。

Patched Velocity 提供 MCProtocolLib 运行时。

Plugin 使用 patched Velocity JAR 作为 `compileOnly` 输入。

Plugin JAR 不打包 MCProtocolLib 或 Netty。

## 连接访问

运行时 Velocity Player 对象是 `ConnectedPlayer`。

Patch 只放开 Plugin 当前不能访问的连接方法。

Plugin 的 Manager 提供以下内部 helper：

```java
MinecraftConnection getFrontendConnection(Player player)
@Nullable MinecraftConnection getBackendConnection(Player player)
```

这两个 helper 的参数是 Plugin `Player`。

Manager 从 `player.velocityPlayer()` 取得准确的 Velocity Player。

`getFrontendConnection` 返回原前端 `MinecraftConnection`。

前端 Channel 关闭后，该对象仍保存当前 handler。

`getBackendConnection` 选择当前 in-flight 或 connected 后端。

后端不存在或 Channel 不活动时，该方法返回 null。

Plugin 直接使用 patched Velocity 类型。

Plugin 不使用反射、Mixin、Velocity Player 子类或 bridge 接口。

## Packet Event API

Patch 在 Velocity Event API 中增加两个最终类：

```java
final class C2SPacketEvent<T extends Packet> {
    Player getPlayer();
    T getPacket();
    void setPacket(T packet);
    void cancel();
    boolean isCancelled();
}

final class S2CPacketEvent<T extends Packet> {
    Player getPlayer();
    T getPacket();
    void setPacket(T packet);
}
```

两个类不共享抽象基类。

Packet Event 不提供连接。

Plugin 使用原生 `@Subscribe` 和具体的泛型参数。

Patch 在 listener 注册时读取具体 Packet 类型。

Patch 拒绝 Raw、通配、异步、`EventTask` 和 `Continuation` listener。

Patch 按协议状态、方向和 Packet ID 缓存匹配 listener。

Listener 变化时，Patch 只清除受影响的缓存项。

### Decoder 流程

1. Decoder 读取 Packet ID。
2. Decoder 查询 listener 缓存。
3. 没有 listener 时，Decoder 执行原流程。
4. 有 listener 时，Decoder 用只读 duplicate 创建 MCProtocolLib Packet。
5. Event Manager 在当前连接 EventLoop 发布一次 Packet Event。
6. Decoder 应用 Packet 替换或 C2S 取消结果。
7. Decoder 继续原 Velocity 流程。

Encoder 不发布 Packet Event。

解码或 Event 处理失败时，Velocity 记录错误并处理原 Packet。

## Packet 发送 API

目标后端 `MinecraftConnection` 提供以下方法：

```java
void sendPacket(Packet packet)
void sendPacket(Packet packet, boolean bypass)
```

单参数方法使用 `bypass=true`。

`bypass=true` 按后端协议状态编码 serverbound Packet。

Patch 随后写入目标后端 Channel。

`bypass=false` 从后端 association 取得对应 `Player`。

Patch 按前端逻辑状态转换 Packet，并调用当前前端 handler。

该路径不调用关闭前端的 Channel。

前端 handler 更新 Velocity 状态，并按原逻辑写入后端。

两个方法都不发布 Packet Event。

只有五个已列出的基础 Packet 使用 `bypass=false`。

其他响应、Move Player 和 Client Tick End 使用默认方法。

## 服务注册

首次配置 Registry Packet 早于 `ServerConnectedEvent`。

Plugin 因此使用原生 `PostLoginEvent`。

0001 在该 Event 前创建并暂停 Mod relay 的原后端连接。

Vanilla 短登录和普通 Velocity 登录在该时点没有后端连接。

注册流程如下：

1. Listener 返回原生 `EventTask`。
2. 该任务把注册提交到连接 EventLoop。
3. Manager 查询现有后端连接。
4. 没有后端连接时，任务恢复 Event。
5. 有后端连接时，Manager 创建一个 Plugin `Player` 和它的服务。
6. 注册完成后，任务恢复 Event。
7. 0001 随后恢复原后端读取。
8. S2C Packet Event 开始填充服务状态。

该流程不需要 relay marker、临时 Login Map 或第二个注册 Event。

## AutomationManager

Manager 保存以下 Map：

```java
ConcurrentHashMap<com.velocitypowered.api.proxy.Player, Player>
```

不同 Velocity Player 的连接 EventLoop 可以并行访问该 Map。

Packet 和命令使用准确的 Velocity Player 查询 Plugin `Player`。

Tick 直接使用已注册的 Plugin `Player`。

普通路径不扫描 UUID。

Fresh login 注册路径才扫描相同认证 UUID。

Manager 先用 `remove(oldVelocityPlayer, oldPlayer)` 删除旧条目。

Manager 把旧 `Player` 的关闭提交到旧连接 EventLoop。

注册 EventTask 等待旧服务关闭，再保存新条目。

周期 tick 发现后端失活时，Manager 使用 `remove(velocityPlayer, player)`。

旧周期 tick 开始时也检查 `map.get(velocityPlayer) == player`。

## 实际登出

Patch 复用 `DisconnectEvent`。

Patch 给该 Event 增加 `cancel()` 和 `isCancelled()`。

`ConnectedPlayer.teardown()` 执行以下顺序：

1. 关闭无关的 in-flight 后端。
2. 暂停当前原后端读取。
3. 发布 `DisconnectEvent`。
4. 等待所有 EventTask 完成。
5. 在连接 EventLoop 处理 Event 结果。
6. 注销旧 `Player` 并清理前端资源。
7. 未取消时，关闭原后端连接。
8. 已取消时，保留原后端连接并恢复读取。

取消结果是 `ConnectedPlayer` 的通用连接状态。

该状态不包含 Automation 或 `shadow` 语义。

`VelocityServerConnection.isActive()` 接受在线前端或已取消的实际登出。

该方法仍检查后端 Channel 和正常关闭状态。

现有 `BackendPlaySessionHandler` 继续处理后端 Packet。

Patch 不增加 Retained Handler。

## Shadow 接管

`/player shadow` 把以下操作提交到连接 EventLoop：

1. 查询命令源 Player 的服务。
2. 把 `shadow` 设为 true。
3. 发送全 false 的 Player Input。
4. 发送 Stop Sprinting。
5. Kick 真实前端连接。

清空输入必须早于 Kick。

`DisconnectEvent` listener 返回原生 `EventTask`。

该任务在连接 EventLoop 查询同一个服务。

服务处于 `shadow` 状态时，该任务调用 `cancel()`。

## AutomationService

状态 owner 固定如下：

| Owner | 状态 |
| --- | --- |
| `AutomationService` | 协议、Player Loaded、`shadow`、Action 和 Tick |
| `world/player/Player` | Entity、LivingEntity、输入、移动发送基线和 `World` |
| `world/World` | Dimension、Chunk、Block 和 Entity registry |

`AutomationService` 处理 Packet 和 tick。

它通过 final owner 读取和修改 `Player`。

Plugin 不为这些状态增加其他 Service owner。

## 线程模型

Packet Event 已在连接 EventLoop 运行。

命令、PostLogin、Disconnect 和配置结束操作都提交到该 EventLoop。

Manager 在该 EventLoop 为每个服务安排一个 50 ms 周期任务。

周期任务调用 `AutomationManager.tick(player, service)`。

Manager 每 tick 先取得当前活动后端连接。

后端存在时，Manager 调用 `service.tick(backend)`。

后端不存在时，Manager 调用 `remove(player, service)` 并取消该周期任务。

服务替换时，Manager 取消旧周期任务。

所有服务可变状态只由对应的连接 EventLoop 访问。

实现不使用锁、`volatile` 或跨线程状态副本。

## 基础协议

| S2C Packet | 状态或 C2S 输出 | bypass |
| --- | --- | --- |
| KeepAlive | KeepAlive | false |
| Ping | Pong | true |
| StartConfiguration | ConfigurationAcknowledged | false |
| SelectKnownPacks | SelectKnownPacks | false |
| FinishConfiguration | FinishConfiguration | false |
| PlayerPosition | AcceptTeleportation 和 PosRot | true |
| ChunkBatchFinished | ChunkBatchReceived | true |
| StoreCookie | 更新 Cookie Map | 无 |
| CookieRequest | CookieResponse | true |
| PlayerChat | 每 65 个签名发送 ChatAck | false |

普通 Packet Event 只更新状态，并安排下一轮响应。

Start Configuration 和 Finish Configuration 使用以下专用顺序。

### Known Packs 和 Registry Data

固定 Minecraft 26.2 只支持 `minecraft:core:26.2`。该值来自固定
`BuiltInPackSource.CORE_PACK_INFO` 和同一 26.2 JAR 的 `version.json`。

Plugin 记录服务端提供的完整 Known Packs 列表。真实前端的 C2S 选择只有在
它与服务端列表完全相同且 Plugin 支持服务端列表中的全部 Pack 时才保留。
其他情况替换为空列表。Minecraft 26.2 只在响应与请求列表完全相同时使用
Known Packs 优化，所以 Plugin 不发送部分交集。

C2S listener 使用 `event.setPacket(...)` 替换原 Packet。它不取消 Packet，
也不发送第二个 Packet。S2C Select Known Packs 和 Registry Data Packet 始终
原样到达真实前端。

Shadow 配置没有前端响应。Plugin 在支持完整服务端列表时发送原列表，否则
发送空列表。该 Packet 使用正常后端配置 handler。服务保存实际选择结果。

Registry Data 只为 Automation 的内部副本补全 null Dimension Type 数据。
服务端非 null 数据始终优先。补全保持服务端 Entry 顺序、ID 和 Key。固定数据
只包含 Automation 使用的 `min_y` 和 `height`。Biome registry 大小仍来自
服务端列表。

如果当前 Dimension Type 或其他 Chunk 解码所需 Registry 状态仍未解析，
`/player shadow` 使用现有 Automation unavailable 路径失败。该检查在断开
真实前端之前完成。

### Start Configuration

1. S2C Packet Event 重置配置状态。
2. 原 `BackendPlaySessionHandler` 暂停后端读取。
3. Velocity 发布原生 `PlayerEnterConfigurationEvent`。
4. Patch 跳过关闭前端的网络写入。
5. Event 完成后，Patch 设置 `pendingConfigurationSwitch`。
6. 20 TPS tick 发送一次 Configuration Acknowledged。
7. `bypass=false` 让原 handler 完成状态切换。

Start Configuration 后，Plugin 丢弃旧 PLAY 状态中未发送的 Chat Ack。

### Finish Configuration

1. S2C Packet Event 标记等待结束。
2. 原 `ConfigSessionHandler` 运行现有配置 Event。
3. Patch 跳过关闭前端的网络写入。
4. Velocity 发布原生 `PlayerFinishedConfigurationEvent`。
5. Plugin 把完整操作提交到连接 EventLoop。
6. Plugin 发送一次 Finish Configuration。
7. `bypass=false` 让原 handler 完成状态切换。

该流程不增加配置 Event、额外周期任务或 pending Packet Map。

## Player Loaded

真实前端发送 Player Loaded 时，C2S Packet Event 记录该状态。

Login 和 Respawn 重置该状态。

Shadow 在以下条件都成立时发送一次 Player Loaded：

- 真实前端尚未发送 Player Loaded
- 已收到初始 Player Position
- 已收到 `LEVEL_CHUNKS_LOAD_START`
- 玩家所在 Chunk 已完成解码

该门槛不使用渲染状态或固定延迟。

## 玩家和实体

状态使用以下继承结构：

```text
Entity
└── LivingEntity
    └── Player
```

`Entity` 保存位置、速度、旋转、AABB、Pose、碰撞、流体、插值和 Entity 关系。

`LivingEntity` 增加生命、Attribute、Effect 和死亡状态。

`Player` 增加准确的 Velocity Player 引用、输入和移动发送基线。

`Player` 不继承或实现 Velocity Player。

该分层不增加渲染、背包、配方或声音状态。

`Entity` 保存共享的 `EntityDefinition` 引用。

Entity 的固定类型数据来自 `world/entity/EntityTypeData`。

`World.addEntity()` 只创建 `Entity` 或 `LivingEntity`。

本地对象继续使用已登记的 `Player`。

除 `LivingEntity` 和 `Player` 外，设计不增加 Entity 子类。

`Entity` 只在需要客户端载具状态时创建一个 `VehicleState`。

`VehicleState` 只保存本地载具计算需要的计时器。

`Entity.tickVehicle()` 只 switch `MovementKind`。

该 switch 处理 Boat、Horse、Camel、Pig、Strider、Happy Ghast 和 Nautilus。

它不能 switch MCProtocolLib Entity Type。

普通 root 只消费服务端移动，并执行 passenger placement。

设计不增加 `EntityFactory`、`EntityBehavior` 或行为 Manager。

玩家状态从 Login、Respawn、Position、Rotation、Health、Motion、Explosion 和自身 Metadata 更新。

四种 C2S Move Player Packet 保存接管前的最终发送基线。

实体表处理 Add、Remove、Move、Position Sync、Teleport、Motion 和 Metadata。

Entity 构造时从 `EntityDefinition` 复制 Automation 会读取的 Metadata 和 Attribute 默认值。

Packet handler 把动态值写入明确字段。设计不保存通用 Metadata、Attribute、Effect、Equipment 或 Inventory 容器。

找不到目标 Entity 的更新 Packet 时，`World` 直接忽略该 Packet，不创建占位对象。

`World` 的 Entity registry 使用服务端 Entity ID 作为 key。

该 registry 登记本地 `Player` 对象和其他 `Entity` 对象。

本地 `Player` 不创建第二个 Entity 状态副本。

每个 `Entity` 保存一个可空 `vehicle` 引用和一个有序 `passengers` 列表。

`World` 处理 `SetPassengers`，并用 Packet 中的完整列表替换旧列表。

找不到 vehicle 时，`World` 忽略整个 `SetPassengers`。

vehicle 存在时，`World` 先解除该 vehicle 的旧 passenger 关系，再按 Packet 顺序绑定当前存在的 passenger。找不到的 passenger 直接跳过。

每次连接和解除都同时更新 `vehicle` 和 `passengers`。

Entity 移除时，`World` 解除所有进入和离开该 Entity 的关系。

设计不增加独立关系 Map、关系 Manager 或 pending 关系层。

Player 计算读取 Entity registry 中的关系、碰撞体和推挤状态。

Plugin 不运行 Entity AI 或服务端拥有的 Entity 模拟。

## 世界

每个 Plugin `Player` 组合一个独立的 `World`。

`World` 保存该 `Player` 收到的 Dimension、Chunk、Block、物理相关 Tag、移动活塞、World Border、Tick Rate 和 Entity registry。

该 `Player` 的连接 EventLoop 是 `World` 的唯一写入者。

Fresh Login 创建新的 `Player` 和 `World`。

Respawn、Dimension Switch 和 Backend Switch 只替换该 `Player` 的 `World`。

设计不增加 `ServerWorld`、`BackendGeneration`、Chunk holder、source 或共享锁。

全部 `Player` 只共享不可变的方块数据和实体类型数据。

服务处理 Registry Data、Tag、Game Event、Ticking State、Chunk、Forget Chunk、Block Update、Section Update、Block Entity 和 World Border Packet。

服务从 Chunk Packet 直接解码 MCProtocolLib `ChunkSection[]`。

世界 Map 使用打包的 Chunk X 和 Chunk Z 作为 key。

完整解码成功后，服务才替换 Map value。

Block Update 直接修改现有 `ChunkSection[]`。

未知 Chunk 的 Block Update 不创建占位 Chunk。

服务不保存 Light、Heightmap、普通 Block Entity NBT 或第二套 Block State。

移动活塞只保存位置计算读取的 Facing、Progress、Extending、Source 和 moved Block State。

Chunk Forget、配置切换和维度切换必须释放旧 Chunk。

## 物理数据

一个离线生成任务从固定 Minecraft 26.2 环境生成紧凑资源。

普通构建只读取已提交资源。

资源只保存运行时表数据。资源不保存版本、提交、格式、SHA 或 MD5 字段。

资源只提供以下数据：

```text
blockStateId -> blockPhysics
shapeId -> AABB[]
entityTypeId -> world/entity 实体类型数据
metadata schema by entity inheritance
default Attribute values
block physical scalars
special behavior kind
```

`blockPhysics` 包含碰撞 Shape、摩擦、速度因子、回弹、Fluid 数据和特殊行为分类。

实体类型数据包含尺寸、Eye Height、attachment、移动类型和固定控制属性。

动态 Metadata、Attribute、Effect、Equipment、Tag 和 Entity 关系只来自 Packet。

固定资源保持不可变，并由全部 `Player` 共享。

项目采用 `PrismarineJS/minecraft-data-generator` 根目录 `LICENSE` 的 MIT 条款。
来源信息只保留在任务资料和 MIT 声明中，不写入运行时资源。

## Player 计算

Plugin 不保留单体 `PlayerPhysics` 行为 owner。

`world/phys/CollisionPhysics` 处理位移裁剪、台阶候选和实体推挤几何。
`FluidPhysics` 处理流体累计和浮力。`VehiclePhysics` 处理载具运动公式。
`PistonPhysics` 处理活塞穿透、轴向限制和携带位移。

`world/player/Player.tick()` 处理零输入、乘客分支和移动 Packet 选择。

`world/World` 提供方块、流体、World Border 和 Entity 碰撞查询。

AABB 等纯计算 helper 保持无状态。

服务端继续负责伤害、死亡、初始外力、移动校验和位置纠正。

Plugin 负责零输入 Player tick、客户端拥有的环境和载具计算、关系跟随及移动 Packet。

`AutomationService` 只在以下条件调用 owner 的 `Player.tick()`：

- `shadow=true`
- 协议状态为 GAME
- 玩家未死亡
- Player Loaded 已完成

Motion Packet 到达时替换当前速度。

Explosion Packet 到达时累加当前速度。

服务不保存外力队列。

`AutomationService` 使用现有 50 ms 周期任务。它不创建第二个调度任务。

一个时间累加值决定当前服务 Tick 是否运行 Client game tick。

Client game tick 使用 `max(50 ms, serverMillisecondsPerTick)` 的 Vanilla cadence。服务端 Freeze 不停止 Local Player tick。Ticking Step 只推进服务端拥有的 World 状态。

每个 Client game tick 执行以下步骤：

1. 推进 World Border、移动活塞和服务端 Entity 插值。
2. Player Loaded 未完成或玩家死亡时跳过普通 Player movement。
3. 其他情况下固定玩家控制输入为零。
4. 执行 Entity base tick，更新 Fluid 和持续移动状态。
5. 根据 Passenger root 和本地控制权选择自由 Player、关系跟随或本地载具分支。
6. 自由 Player 由 `LivingEntity.travel()` 选择 Air、Water、Lava、Flight 或持续 Glide 分支。
7. `Entity.move()` 收集 Block、Entity、World Border 和移动活塞碰撞，并执行台阶与回弹。
8. 按 Vanilla 顺序执行 inside、stepOn、speedFactor、climbable 和其他特殊方块行为。
9. 更新位置、速度、碰撞标志、Pose、Scale 和空间适配。
10. 从 root 到 leaf 更新 Passenger attachment。
11. 选择并发送准确的 Player 或 Vehicle movement Packet。
12. `AutomationService` 始终发送 Client Tick End。

碰撞先裁剪 Y 轴。

`abs(x) < abs(z)` 时，碰撞随后裁剪 Z 和 X。

其他情况随后裁剪 X 和 Z。

水平碰撞时，Player 计算尝试 Minecraft 26.2 台阶候选。

碰撞集合包含方块 Shape、Entity AABB、World Border 和移动活塞 Shape。

World Map 继续区分未知 Chunk 和已加载空气。

未知 Chunk 不提供碰撞或 Fluid 状态，也不暂停 Player tick。

### 状态输入

Player 的 Metadata、Attribute、Effect、Ability、Equipment、Game Mode 和 Pose 只保留位置计算读取的部分。

World 的 Tag、Fluid、Block State、Block Entity、Border 和 Tick Rate 只保留位置计算读取的部分。

服务端伤害、生命、Motion、Explosion、Position、Rotation、LookAt、Respawn 和 Dimension 是权威输入。Plugin 不重算伤害、传送目标或维度目标。

### 环境行为

静态和上下文碰撞 Shape 使用固定 26.2 数据与相邻 Block State 解析。

`Entity.move()` 和 `LivingEntity.travel()` 根据 `BlockBehaviorKind` 处理特殊方块。

设计不增加 Block handler 对象、注册表或 Behavior Manager。

Block replacement push、Portal destination、Sleep relocation 和伤害结果继续使用服务端 Packet，不在 Plugin 重演服务端逻辑。

### Entity 和载具分支

普通 root Entity 和 Minecart 只消费服务端位置、Teleport 和插值状态。

任意 Entity 都可以成为强制 Passenger graph 的节点。关系位置从 root 到 leaf 依次应用 attachment。

Player 作为 Passenger 时不运行自由 Player movement。

只有实际 root 的控制权规则选中本地 Player 时，Plugin 才调用 `Entity.tickVehicle()`。

`Entity.tickVehicle()` 通过 `MovementKind` 处理 Boat、Horse、Camel、Pig、Strider、Happy Ghast 和 Nautilus。它不按 Entity Type 建立分支。

控制权判断只读取固定 Entity 定义、Metadata、Passenger 顺序和必要的本地 Equipment。

本地控制的 root 发送 Move Vehicle。Boat 额外发送零 Paddle。Player 始终发送 Passenger rotation，不发送自由位置 Packet。

主动下车、载具转向、载具跳跃和载具加速保持零输入，不生成对应主动 Packet。

### Movement Packet

服务沿用接管前的最后发送基线。

位置差超过 Minecraft 26.2 阈值时，服务发送位置。

服务至少每 20 个实际 Player movement tick 发送一次位置。

旋转或碰撞状态变化时，服务选择对应 Packet 变体。

服务端 Player Position 覆盖本地位置、速度和旋转。

服务随后发送 Accept Teleportation 和准确的 PosRot。

## 后续移动修复设计

### 固定数据

固定 generator 在一个阶段生成全部新字段。

新资源只增加三个数据项：

- Entity movement collision
- Entity piston reaction
- Block State Fluid face bitset

`world/entity` 保存实体类型数据。
`world` 保存方块状态数据。
固定资源读取代码按这两个 owner 写入数据。
Plugin 不增加运行时 Minecraft 依赖。

### Position 和 Motion

`FakePlayerProxyPlugin` 的对应 Packet listener 直接调用 `world/player/Player`。
`AutomationService` 只保持协议回应和 EventLoop 顺序。

Player Position 覆盖 Player transform 和 Packet velocity。
Plugin 随后发送 Accept Teleportation 和 false/false PosRot。
该 PosRot 更新最后发送基线。

普通 Entity 插值只保存目标位置、yaw、pitch 和剩余 tick。
插值不能保存或写入 velocity。

Position Sync 更新远端 Entity 的位置、旋转和 `onGround`。
Position Sync 忽略 Packet delta movement。

`Entity` 保存一个 relative-position codec 基线。
普通远端更新同时推进该基线和显示 transform。

本地控制 root 只推进 relative-position codec 基线。
普通 Move Entity 和 Position Sync 不能修改本地 transform。
Teleport 同时重置该基线和本地 transform。

### Entity collision 和 push

`World.collisions()` 只加入固定 26.2 允许的 Entity AABB。
该 Entity 不能是 removed，也不能属于同一 Passenger root。

`World.pushEntities()` 继续读取 pushable、noPhysics 和 Passenger root。
Movement collision 和 Entity push 不能共享一个简化规则。

### Fluid

`world/World.fluid(Entity, world/phys/AABB)` 继续返回现有 Fluid 结果。
该方法使用局部变量分别累计 Water 和 Lava。
Plugin 不增加 Fluid Tracker 对象。

扫描规则使用固定 26.2 语义。
扫描处理 Fluid height、完整液面、浅浸没和 falling face。
扫描也检查所需的邻接 Chunk。

如果一个邻接 Chunk 未加载，本次 Fluid 结果为空。
Plugin 不能使用部分 Fluid 结果。

Player 对相交 cell 的流向取平均。
其他 Entity 对流向执行归一化。
每种 Fluid 分别应用固定 scale。
最后，方法合并两个流向。
低速 Player 水流保留 Vanilla 最小推动。

### Boat

`Entity.tickVehicle()` 的 `MovementKind.BOAT` 分支继续处理 Boat。
Plugin 不增加 Boat Entity 子类。

`VehicleState` 只增加 Boat 状态、水面高度和 land friction。
Boat 状态包含 IN_WATER、UNDER_WATER、UNDER_FLOWING_WATER、ON_LAND 和 IN_AIR。

每个零输入 tick 先计算 Boat 状态。
然后，Boat 运行通用 Fluid current 和 `floatBoat()`。
最后，Boat 移动 root 并更新 Passenger attachment。

未淹没时，Passenger 不能重复使用 Boat 的 Fluid current。

`Player.tick()` 按以下顺序发送 Packet：

1. Paddle(false,false)
2. Player Rot
3. root Move Vehicle

其他 Vehicle 的输出不变。

### Piston

`FakePlayerProxyPlugin` 增加同步 `ClientboundBlockEventPacket` 订阅。
`AutomationService` 把该 Packet 发送给当前 Player 的 `World`。

`World` 使用 Packet 和已加载 Block State 更新 `MovingPiston` Map。
Plugin 不增加 Piston Handler、pending Map 或第二个 Service。

如果所需 Block State 缺失，Plugin 不能猜测 Piston 状态。
日志显示位置、event、方向和缺失位置。

每个 tick 计算每个 moving Shape 的轴向穿透。
Piston 限制本 tick 位移，并增加 Vanilla epsilon。
Entity 使用现有碰撞裁剪完成 Piston move。
Piston 不能直接修改 Entity position。

Honey carry 检查顶部接触和 `onGround`。
Slime 只覆盖运动轴速度。
`World` 移除完成或取消的 `MovingPiston`。
Chunk replacement 和 forget 继续清理 Piston 状态。

## 死亡和重置

`ClientboundSetHealthPacket` 是生命和死亡的唯一权威输入。

生命小于或等于零时，服务停止普通 Player 计算。

初版不发送 Respawn Packet。

进入 CONFIGURATION 时，服务清理旧协议、世界和实体状态。

收到 Respawn 时，服务清理旧世界、实体、速度和碰撞状态。

Fresh login 创建全新服务，不执行状态 reset 或转移。

## 版本

- Velocity 使用固定上游提交和 Java 21。
- Plugin 和 Patch 使用 Java 21。
- MCProtocolLib 使用 `26.2-20260809.160751-16`。
- Plugin 运行时不加载 Minecraft、Prismarine 或 Minestom。

## 追加：Plugin owner 和调用路径

本节追加当前决定。前文行为和已完成实现记录保持不变。

本节的结构决定由文末“最新 owner 和数据设计”取代。本节只保留历史记录。

Plugin 的目录分层为 `automation/`、`world/`、`world/entity/`、
`world/player/` 和 `world/phys/`。

`automation/AutomationManager` 持有 Velocity Player 到 Plugin Player 的 Map。
`automation/AutomationService` 持有协议、Shadow、Action、Player Loaded 和
EventLoop 顺序。它不持有 World、Entity 或物理状态副本。

`world/World` 持有每个 Player 的 Chunk、Block、Entity registry、Passenger、Border、
Tick Rate 和 Moving Piston 状态。
`world/entity/Entity` 和 `LivingEntity` 持有实体状态。
`world/player/Player` 持有玩家状态和玩家计算。
`world/phys` 只包含 `AABB`、`CollisionPhysics`、`FluidPhysics`、
`VehiclePhysics` 和 `PistonPhysics` 的无状态计算。

`AutomationService.lookAt(...)` 的计算调用链改为
`FakePlayerProxyPlugin.onLookAt(...) -> world/player/Player.lookAt(...)`。
Service 删除 `lookAt(...)`。该 Packet 不再经过 Service 的计算转发。

`AutomationService.position(...)` 的 relative position、relative rotation 和
velocity 解析调用链改为
`FakePlayerProxyPlugin.onPosition(...) -> Player.applyServerPosition(...)`。
Service 保留后端 ACK 和 PosRot 的发送顺序。

`AutomationService.rotation(...)` 的 relative yaw 和 relative pitch 解析调用链改为
`FakePlayerProxyPlugin.onRotation(...) -> Player.applyServerRotation(...)`。
Service 保留后端回应。

`health`、`abilities`、`gameMode`、`clientPosition`、`clientRotation` 和
`clientStatus` 直接写入 Player。`motion` 和 `explosion` 直接写入 Player 或
World 中的 Entity。Entity、World、Chunk、Block、Border、Registry、Tag 和 Ticking
Packet 直接写入对应 owner。

`PhysicsData` 拆为 `world/Block`、`world/entity/EntityTypeData` 和
`world/MinecraftData`。`MinecraftData` 只解析和持有不可变固定数据，不持有
连接、协议、Player 或 World 运行时状态。
`EntityDefinition`、`PoseDefinition`、`MovementKind` 和 `PistonReaction` 进入
`EntityTypeData`。`BlockBehaviorKind` 进入 Block。`VehicleState` 进入 Entity。
`InputState` 进入 Player。`MovingPiston` 和 `LevelChunkInstallResult` 进入 World。
`VanillaRegistryData` 删除。

结构迁移先使用 `mcp__idea__analyze_calls`。符号重命名使用
`mcp__idea_index__ide_refactor_rename`。Java 类文件及其 package 位置迁移使用
`mcp__idea_index__ide_move_file(file, destination, project_path)`。当前启用的 IDEA
MCP 不提供方法归属移动。方法 owner 迁移通过代码编辑完成。迁移后使用 IDEA
symbol 或 usage search 检查旧 owner 和旧调用路径不存在。

## 最新 owner 和数据设计

### AutomationManager

Manager 持有 `Map<Velocity Player, Plugin Player>`。它负责注册、查询、Fresh Login、
tick 生命周期、失活移除和 shutdown。Manager 保留现有 Logger 字段和构造参数。
连接 helper 移到 Plugin Player。动作入口移到 AutomationService 和 Plugin
Player。Manager 不保留动作转发、`run(...)` 或 `actionName`。

### Shadow 和 AutomationService

调用路径固定为：

```text
PlayerCommand
-> AutomationManager.get(Velocity Player)
-> Plugin Player.automationService()
-> AutomationService.shadow()
```

`AutomationService.shadow()` 在 owner EventLoop 内完成全部 Shadow 切换。它校验当前
Service 和 backend，设置 `shadow=true`，然后调用 Velocity Player 的
`disconnect(...)`。`DisconnectEvent` 读取该状态并取消实际登出，因此 backend 和
Automation 继续存在。

Shadow 不清除动作计划、输入或玩家状态。在线时，真实客户端承担击退、下落、水流和
移动等被动客户端职责。Shadow 时，AutomationService 调用 Player 接管这些职责。
主动动作由 AutomationService 规划，并在在线和 Shadow 状态都可运行。

AutomationService 保留协议状态、配置切换、协议回应、Shadow、主动动作计划和周期
调度。实际输入修改、动作 Packet、物理计算和移动输出进入 Plugin Player。

### Command

现有 `PlayerCommand` 转发壳先合并到 `PlayerCommandHandler`。Shadow 解析和建议从
Parser 合并到同一类。随后使用 IDEA 把 `PlayerCommandHandler` 重命名为
`PlayerCommand`。最终类直接实现 `SimpleCommand`，并且只接受 `shadow`。

合并完成后不保留 `PlayerCommandParser`、`PlayerCommandKind` 和
`ParsedPlayerCommand`。`ActionMode` 移到 `automation/`。`FppCommand` 的行为和文本
保持不变。IDEA 只更新重命名产生的类型引用。

### Fixed data

`MinecraftData` 重命名为 `world/data/Decoder`。`Block` 和 `EntityTypeData` 移到
`world/data/`。Decoder 解析一次 `minecraft-data/minecraft-data.bin`，并提供固定
Block、Shape、Entity Type、item ID 和 Dimension Type 查询。它不处理协议和运行时
状态，也不增加 Registry 类型。Known Pack 逻辑进入 AutomationService。

`world/world/World` 只保存每个 Player 的 Dimension、Chunk、Block Entity、Entity、
Passenger、Border、Tick 和 Moving Piston 状态。World 使用 Decoder 查询固定类型。

### Entity and Vehicle

Entity 只保存通用 position、velocity、pose、metadata、关系、碰撞和插值状态。
Entity 保存一个可空 Vehicle 组件。Vehicle 保存 Boat、boost、horse、strider、camel
和 happy ghast 状态，并处理载具 metadata、控制权、tick 和座位计算。

EntityTypeData 只保存不可变实体类型数据。载具固定数据进入其嵌套 Vehicle data。
`PistonReaction` 变为 `affectedByPiston` boolean。Entity 提供活塞、碰撞、minecart 和
metadata 语义操作。Plugin、World 和 Player 不直接检查 EntityTypeData。

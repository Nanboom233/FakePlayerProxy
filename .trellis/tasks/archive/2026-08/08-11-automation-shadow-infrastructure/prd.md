# Automation Shadow 基础设施

## 目标

Mod relay 玩家执行 `/player shadow` 后，Plugin 接管原后端连接。

真实前端连接关闭后，Velocity 保留原后端连接。

Plugin 继续维护基础协议和无输入 Player 计算。

当全部玩家控制输入为零时，Vanilla 客户端仍会产生或接受的位移、速度、Pose、乘坐关系和移动上报都必须继续运行。

Vanilla 玩家只使用 raw tunnel。

## 边界

| 部分 | 范围 |
| --- | --- |
| `0001-login-relay.patch` | login relay、加密、解密和 raw tunnel |
| `0002-automation-extension.patch` | 通用 Packet API、连接访问和可取消登出 |
| Plugin | Automation、`shadow`、协议状态、玩家状态、实体状态、世界状态和 Player 计算 |
| Mod | relay 客户端能力 |

Patch 不能依赖 Plugin。

Patch 不能包含 Automation、`shadow` 或 Player 计算。

Patch 文件只能包含生产代码。

Patch 测试只能放在 `plugin/patch/test/`。

`plugin/build/server/source` 必须保持固定上游提交的 clean 状态。

构建只能在 `plugin/build/server/work` 应用 Patch。

本任务不能修改其他 `/player` action。

实施时可以直接复用当前未提交修改中符合本设计且可用的代码。

### 状态准入

Plugin 大致参照 Vanilla 的状态边界，但不能复制完整客户端模型。

字段或判断只能服务以下至少一个用途：

- 协议响应
- Entity 关系和控制权
- 非主动位置变化
- 移动 Packet 输出
- 登录、切换和关闭生命周期

实现必须排除渲染、声音、配方和其他 Automation 不读取的状态。

每个新增字段必须有对应的 Minecraft 26.2 读取路径。

“非主动位置变化”只表示不需要玩家在本 tick 发起控制输入。服务端外力、环境、持续状态、乘客关系和已存在的载具控制状态都属于该范围。

## 功能要求

### 服务注册

Plugin 必须在原生 `PostLoginEvent` 注册 Plugin `Player`。

Listener 必须返回原生 `EventTask`。

该任务必须在连接 EventLoop 完成注册。

没有原后端连接时，Plugin 不能创建服务。

该规则必须排除 Vanilla raw tunnel 和普通 Velocity 登录。

有原后端连接时，Plugin 必须创建一个新 `Player` 和它的 `AutomationService`。

### Fresh login

Fresh login 必须创建新的 Plugin `Player` 和 `AutomationService`。

注册时，Manager 必须扫描相同认证 UUID 的旧服务。

Manager 必须用 `remove(oldVelocityPlayer, oldPlayer)` 删除旧条目。

Manager 必须在旧 Player 的连接 EventLoop 关闭旧服务。

注册 EventTask 必须等待旧服务关闭，再保存新服务。

Manager 不能保存第二个 UUID 索引。

新 `Player` 和服务不能接收旧对象的任何状态。

旧服务的周期 tick 不能删除新服务。

### Packet Event

Patch 必须提供独立的 `C2SPacketEvent<T>` 和 `S2CPacketEvent<T>`。

`T` 必须是具体的 MCProtocolLib Packet 类型。

两个 Packet Event 必须提供准确的 `Player`。

两个 Packet Event 必须支持读取和替换 Packet。

只有 `C2SPacketEvent<T>` 支持 `cancel()`。

Packet Event 只允许同步 listener。

Raw、通配、异步和 continuation listener 必须注册失败。

没有匹配 listener 时，Patch 不能创建 MCProtocolLib Packet。

一个网络 Packet 最多产生一个 MCProtocolLib Packet 和一个 Packet Event。

### Packet 发送

`MinecraftConnection` 必须提供以下方法：

```java
void sendPacket(Packet packet)
void sendPacket(Packet packet, boolean bypass)
```

单参数方法必须使用 `bypass=true`。

`bypass=true` 必须直接编码并发送到目标后端连接。

`bypass=false` 必须调用当前前端 handler。

两个方法不能发布 Packet Event。

两个方法使用 fire-and-forget 语义。

只有以下 Packet 使用 `bypass=false`：

- `ServerboundKeepAlivePacket`
- `ServerboundConfigurationAcknowledgedPacket`
- `ServerboundSelectKnownPacks`
- `ServerboundFinishConfigurationPacket`
- `ServerboundChatAckPacket`

普通 Packet 响应必须在连接 EventLoop 的下一轮发送。

该顺序必须让原 Velocity handler 先处理当前后端 Packet。

### Shadow 接管

`/player shadow` 必须只操作命令源 `Player` 的服务。

Plugin 必须在连接 EventLoop 执行以下步骤：

1. 把 `shadow` 设为 true。
2. 发送零输入 Packet。
3. 发送 Stop Sprinting。
4. Kick 真实前端连接。

Patch 必须给 `DisconnectEvent` 增加 `cancel()` 和 `isCancelled()`。

Velocity 必须在关闭原后端连接前完成该 Event。

Plugin listener 必须在连接 EventLoop 查询 `shadow` 状态。

`shadow=true` 时，Plugin 必须取消实际登出。

取消后，Velocity 必须注销旧 `Player`，并保留原后端连接。

未取消时，Velocity 必须执行原登出流程。

周期 tick 发现原后端连接失活时，Manager 必须删除准确服务并取消该 tick。

Manager 必须使用 `remove(player, service)`。

### 基础协议

Shadow 必须处理以下流程：

- KeepAlive 和 Ping
- PLAY 与 CONFIGURATION 切换
- Known Packs
- 传送和旋转同步
- Chunk Batch 确认
- Player Loaded 确认
- Cookie Store 和 Cookie Request
- 签名玩家聊天确认

Shadow 每收到 65 个不同签名后，必须发送一次 Chat Ack。

进入 CONFIGURATION 时，Shadow 必须清除未发送的 Chat Ack 状态。

Start Configuration 必须等待原生 `PlayerEnterConfigurationEvent` 完成。

现有 20 TPS tick 必须在 `pendingConfigurationSwitch` 后发送一次确认。

Finish Configuration 必须使用原生 `PlayerFinishedConfigurationEvent`。

Plugin 必须把完整结束操作提交到连接 EventLoop。

真实前端发送 Player Loaded 时，Plugin 必须记录该状态。

Shadow 不能重复发送 Player Loaded。

未记录时，Shadow 必须等待初始位置和当前 Chunk 可用。

### 状态 Owner

`AutomationManager` 必须保存 `Map<Velocity Player, Player>`。

Plugin 必须使用 `Entity -> LivingEntity -> Player` 继承结构。

`Player` 必须包装准确的 Velocity Player。它不能继承或实现 Velocity Player。

`Player` 必须持有自己的 `World` 和 `AutomationService`。

`AutomationService` 必须保存 final `Player` owner。它不能保存连接字段。

`AutomationService` 只保存协议、Player Loaded、`shadow` 和 Action 状态。

`Entity` 必须保存实体类型数据、位置、速度、AABB、Pose、碰撞、流体、插值和关系。

`LivingEntity` 必须保存生命和影响移动的 Attribute、Effect 和 Equipment。

`Player` 必须保存 Ability、输入、移动基线和玩家分支状态。

Plugin 不能增加其他状态 Service、Entity 子类、`EntityFactory` 或行为 Manager。

`World.addEntity()` 只能创建 `Entity` 或 `LivingEntity`。本地 `Player` 必须直接登记到 Entity registry。

`Entity.tickVehicle()` 必须 switch `MovementKind`。它不能 switch Entity Type。

载具状态只保存于对应 Entity。其他 Entity 不能创建独立载具状态对象。

每个 `Entity` 必须保存一个可空 `vehicle` 和一个有序 `passengers` 列表。

`SetPassengers` 必须替换完整乘客列表，并同时更新关系两端。

`SetPassengers` 找不到 vehicle 时必须忽略整个 Packet。

`SetPassengers` 找不到某个 passenger 时必须跳过该 passenger，并继续绑定其余已存在对象。

找不到目标 Entity 的 Motion、Metadata、Position 或 Remove Packet 必须直接忽略。

Entity 创建时必须从 `EntityDefinition` 初始化 Automation 会读取的 Metadata 和 Attribute 默认值。后续 Packet 只覆盖已收到的值。

Plugin 不能保存通用 Metadata、Attribute、Effect、Equipment 或 Inventory 容器。

移除 Entity 时，`World` 必须解除该 Entity 的全部 vehicle 和 passenger 关系。

Plugin 不能增加独立关系 Map 或 pending 关系层。

Plugin 只能应用服务端实体更新。

Plugin 不能运行 Entity AI 或服务端拥有的 Entity 模拟。

### 世界状态

Plugin 必须维护服务端已发送且未卸载的 Chunk。

`World` 只能包含该 `Player` 的 Packet 流提供的世界状态。

不同 `Player` 不能共享可变的 Chunk、Block 或 Entity 状态。

只有不可变的固定方块数据和实体类型数据可以由全部 `Player` 共享。

世界状态只包含当前维度、最低 Y、高度、`ChunkSection[]`、物理相关 Tag、移动活塞、World Border 和 Tick Rate 状态。

Plugin 必须在原 `ChunkSection[]` 应用 Block Update 和 Section Update。

Plugin 不能创建第二套 Block State 数组。

未知 Chunk 不能写入 World Map，也不能标记为已加载空气。

物理查询遇到未知 Chunk 时，该 Chunk 不提供方块碰撞或流体状态。Player 计算必须继续。

Plugin 不能为缺失 Chunk 增加等待队列、暂停状态或恢复器。

### 固定数据

固定方块数据必须提供 Block 数据。固定实体类型数据必须提供实体默认数据。

实体类型数据必须提供 Metadata 默认值、Attribute 默认值、尺寸、attachment 和移动类型。

Packet 必须把动态 Metadata、Attribute、Effect 和 Equipment 写入明确字段。

全部固定数据必须保持不可变，并由所有 `Player` 共享。

### Player 计算

AutomationService 必须按 20 TPS 运行服务 Tick。Client game tick 必须按 Vanilla 的 Tick Rate 规则选择是否在该服务 Tick 运行，且不能高于 20 TPS。

Plugin 不能使用单体 `PlayerPhysics` 作为行为 owner。

`Entity.move()` 必须处理位移、碰撞、流体、活塞和实体推挤。

`LivingEntity.travel()` 必须处理重力、阻力、Attribute 和 Effect。

`Player.tick()` 必须处理零输入、乘客分支和移动 Packet 选择。

`World` 必须提供方块、流体、World Border 和 Entity 碰撞查询。

AABB 等纯计算 helper 不能保存 Player 或 World 状态。

Player 计算必须处理以下输入：

- 玩家自身的 Entity Motion
- Explosion Player Knockback
- 服务端位置、旋转和 LookAt 纠正
- Player Metadata、Attribute、Effect、Ability、Equipment 和 Game Mode
- 当前方块、流体、移动活塞、World Border 和 Tick Rate
- 当前 Entity、Passenger、Vehicle 和控制权状态

Player 计算必须处理以下行为：

- 重力和阻力
- 地面摩擦
- Water 和 Lava 的高度、流向、浮力和阻力
- Block、Entity、World Border 和移动活塞碰撞
- Vanilla 台阶候选、速度因子和碰撞回弹
- 离开平台和落地
- Bubble Column、Cobweb、Berry Bush、Powder Snow、Honey、Slime、Bed、Soul Sand 和 Climbable 行为
- Pose、Scale 和空间适配
- Entity 推挤和服务端外力
- 任意深度 Passenger 关系跟随
- 本地控制的 Boat、Horse、Camel、Pig、Strider、Happy Ghast 和 Nautilus 零输入移动
- Minecart 和普通 root Entity 的服务端位置插值
- `onGround` 和 `horizontalCollision`

Motion Packet 必须替换当前速度。

Explosion Packet 必须累加当前速度。

Player 计算不能保存外力队列。

Player 计算必须先执行位移，再应用本 tick 的重力和阻力。

方块碰撞必须使用 Minecraft 26.2 的轴顺序。

Plugin 必须按变化类型发送 Move Player Packet。

Plugin 必须至少每 20 个实际 Player movement tick 发送一次位置。

Plugin 必须在每个 Shadow client game tick 末尾发送 Client Tick End。

死亡或 Player Loaded 未完成不能停止 Client Tick End。

死亡必须停止普通 Player 计算和 Move Player Packet。

初版不能自动 Respawn。

### 并发

每个服务的可变状态只能由对应的连接 EventLoop 访问。

不同玩家必须能并行运行。

普通查询路径必须使用准确的 `Player`，不能扫描 UUID。

## 后续移动修复

本次修复只处理固定 Registry P0 之后的非主动位置错误。

### 证据边界

- 现有日志确认了修复前的 LevelChunk 失败。
- 固定 26.2 源码和现有研究报告提供实现依据。
- 本次修复不启动 Minecraft 后端。
- 本次修复不执行手动实测或连接实测。
- 用户后续执行玩家视角实测。

### 修复要求

- Player Position 回执必须使用 `onGround=false` 和 `horizontalCollision=false`。
- 普通 Entity 插值只能修改位置和旋转。插值不能覆盖 Motion。
- Position Sync 不能读取 Packet 的 delta movement。
- 本地控制 root 必须忽略普通 Move Entity 和 Position Sync transform。
- Entity movement collision 和 Entity push 必须使用不同规则。
- 普通 Living Entity 和 Projectile 不能作为通用 movement collision Shape。
- Water 和 Lava 必须分开累计。
- Player 必须使用相交 Fluid cell 的平均流向。
- 其他 Entity 必须使用归一化流向。
- Fluid 必须处理浅浸没、完整液面、falling face 和最小推动。
- Boat 必须处理水面状态、浮力、阻力和 Passenger Fluid box。
- Boat 必须按 Paddle、Player Rot、root Move Vehicle 的顺序发包。
- Piston Block Event 必须构造伸出、缩回、粘性拉回和取消状态。
- Piston 必须按每个 Shape 的轴向穿透推动 Entity。
- Piston 必须处理 piston reaction、Honey carry、Slime velocity 和完成清理。

所有伤害来源继续使用 `ClientboundSetEntityMotionPacket`。
Plugin 不能增加伤害来源分支、自定义击退公式或外力队列。

后端继续计算掉落伤害。
Plugin 不能计算掉落伤害，也不能直接修改 Health。

本次修复不能增加新的 Service、Handler、Manager、tick task 或 Entity 子类。

### 用户实测目标

- 玩家攻击、Mob 攻击和 Projectile 命中都产生连续击退。
- 后端在玩家落地后产生掉落伤害。
- Player Position 回执不能提前结束后端的掉落状态。
- 普通 Entity 不阻挡玩家移动。Entity push 仍然有效。
- 静水、水平水流、下降水流和 Lava 使用固定 26.2 结果。
- Boat 在水中保持正确水位、浮力和水流位移。
- 服务端不能因 Boat 发包顺序校正 Boat。
- 普通 Piston 和 Sticky Piston 必须通过伸出、缩回和拉回测试。
- Honey carry、Slime velocity 和 Piston 完成清理必须通过测试。
- 未知 Chunk 不能变成空气。未知 Chunk 不能暂停 Player tick。
- Known Packs、Shadow、Fresh login、Passenger、Vehicle 和 `/player` Action 不能回归。

## 验收条件

- 固定 Velocity 源码保持 clean。构建只在一次性工作树应用 Patch。
- `0001` 只包含 relay。`0002` 只包含通用 Plugin API。Patch 不包含测试。
- Vanilla raw tunnel 不创建 Automation。Mod relay 只创建一个 `Player`。
- `/player shadow` 关闭前端并保留后端。Fresh login 创建全新状态。
- Packet listener 可以替换 Packet。只有 C2S listener 可以取消 Packet。
- 没有匹配 listener 时，Patch 不解码 MCProtocolLib Packet。
- 真实前端关闭后，Plugin 继续处理基础协议。
- Entity registry 只保存本地 `Player` 和服务端已发送的 Entity。
- Passenger 关系始终双向一致。Entity 移除会解除全部关系。
- 自然下落、外力、Fluid、特殊方块和碰撞产生连续位置结果。
- Attribute、Effect、Ability、Equipment、Pose 和 Scale 改变移动结果。
- Passenger 跟随 root。只有本地控制 root 发送 Move Vehicle。
- Tick Rate 控制 Client game tick。Client game tick 不超过 20 TPS。
- 服务端拥有的状态变化不运行第二套预测。
- 缺失 Chunk 不进入 World，也不暂停 Player tick。
- 死亡停止移动 Packet，但不停止 Client Tick End。
- 多个 Player 的状态相互隔离。其他 `/player` Action 保持不变。
- Plugin JAR 不包含 MCProtocolLib、Netty、Lombok 或 Velocity 类。

## 不在范围内

- Vanilla Automation
- 旧服务状态转移
- reset、reclaim 或 reattach
- 第二个后端连接
- Retained Handler
- Plugin 专用 Mixin
- Velocity 中的 `shadow`
- AES secret 导出或复制
- Resource Pack 自动处理
- Code of Conduct 自动接受
- 主动行走、跳跃、疾跑或潜行
- 主动下车、载具转向、载具跳跃或载具加速
- 主动开始鞘翅、使用烟花或切换飞行
- 完整 Vanilla 浮点等价
- 自动 Respawn
- 后端服务器持有的可变 World
- 跨 Player 的 Chunk 引用计数和 World 同步

## 实现门禁

- 生成物理数据采用 `PrismarineJS/minecraft-data-generator` 根目录 `LICENSE` 的 MIT 条款。
- 固定 26.2 紧凑生成物必须保留来源提交和 MIT 声明。
- 普通构建不能下载或启动 Minecraft。
- Plugin 运行时不能加载 Minecraft、Prismarine 或 Minestom。
- Plugin、Patch 和固定 Velocity 必须使用 Java 21。
- MCProtocolLib 必须固定为 `26.2-20260809.160751-16`。

## 追加：Plugin 目录和 owner 计划

本节追加当前决定。前文的 Patch、relay、Shadow、Fresh Login、Player-owned
World 和物理行为保持不变。

本节的结构决定由文末“最新结构和 owner 要求”取代。本节只保留历史记录。

目录调整为：

```text
automation/
  AutomationManager.java
  AutomationService.java
world/
  World.java
  Block.java
  MinecraftData.java
world/entity/
  Entity.java
  LivingEntity.java
  EntityTypeData.java
world/player/
  Player.java
world/phys/
  AABB.java
  CollisionPhysics.java
  FluidPhysics.java
  VehiclePhysics.java
  PistonPhysics.java
```

`AutomationManager` 继续持有 `Map<Velocity Player, world.player.Player>`。
它处理注册、Fresh Login、关闭和命令入口。

`AutomationService` 继续持有协议、Shadow、Player Loaded、Action、连接回应和
EventLoop 顺序。它不再保存 World、Entity 或物理计算副本。

`world/World` 持有单个 Player 的 Dimension、Chunk、Block Entity、Entity registry、
Passenger 关系、World Border、Tick Rate 和 Moving Piston 状态。

`world/entity/Entity` 和 `LivingEntity` 持有实体状态、属性、效果、装备、Pose、关系、
载具状态和实体类型数据。

`world/player/Player` 持有玩家状态、输入、Ability、Game Mode、移动基线和玩家计算。

`world/phys` 只持有无连接、无协议、无生命周期的 AABB、移动、碰撞、流体、船和活塞计算。

执行 `AutomationService.lookAt(...)` 迁移时，使用 `mcp__idea__analyze_calls` 找到
`FakePlayerProxyPlugin.onLookAt(...)`。当前启用的 IDEA MCP 不提供方法归属移动，
因此直接把计算代码迁移到 `world/player/Player.lookAt(...)`，修改 `onLookAt(...)`
调用 Player，并删除旧 Service 调用路径。

执行 `AutomationService.position(...)` 迁移时，把 relative position、relative
rotation 和 velocity 解析移到 `world/player/Player.applyServerPosition(...)`。
`onPosition(...)` 仍通过 Service 发送 Accept Teleportation 和 PosRot。

执行 `AutomationService.rotation(...)` 迁移时，把 relative yaw 和 relative pitch
解析移到 `world/player/Player.applyServerRotation(...)`。Service 只发送后端回应。

`health`、`abilities`、`gameMode`、`clientPosition`、`clientRotation` 和
`clientStatus` 直接调用 Player。`motion` 和 `explosion` 直接调用 Player 或
`World.entity(...)`。Entity、Metadata、Attribute、Effect、Equipment、Passenger、
Chunk、Block、Border、Tag、Registry 和 Ticking Packet 直接调用 World 或 Entity。

`PhysicsData` 的方块数据进入 `world/Block`。实体类型数据进入
`world/entity/EntityTypeData`。资源解析和固定 registry 进入
`world/MinecraftData`，只解析 `minecraft-data/minecraft-data.bin`。

`EntityDefinition`、`PoseDefinition`、`MovementKind` 和 `PistonReaction` 合并到
`EntityTypeData`。`BlockBehaviorKind` 合并到 `Block`。`VehicleState` 合并到 Entity。
`InputState` 合并到 Player。`MovingPiston` 和 `LevelChunkInstallResult` 合并到 World。
`VanillaRegistryData` 删除。Known Pack 使用 MCProtocolLib 的 `KnownPack`。

Mod 使用 `com.fakeplayerproxy.mod.config.ConsentStore`。它提供
`Optional<Boolean> find(String)` 和 `void remember(String, boolean)`，并写入
Fabric 配置目录下的 `fakeplayerproxy/consent_store.toml`。它不使用 Decision、Gson、
JSON 或新增 TOML 依赖。

本节不增加功能、Patch 接口、Service、Handler、Manager、tick task、Entity 子类或测试类型。

## 最新结构和 owner 要求

本节取代前一个 Plugin 目录和 owner 计划。既有协议、Shadow、物理行为和 Patch
边界保持不变。

目录固定为：

```text
automation/
  ActionMode.java
  AutomationManager.java
  AutomationService.java
command/
  FppCommand.java
  PlayerCommand.java
world/
  data/
    Block.java
    Decoder.java
    EntityTypeData.java
  world/
    World.java
  entity/
    Entity.java
    LivingEntity.java
    Vehicle.java
  player/
    Player.java
  phys/
    AABB.java
    CollisionPhysics.java
    FluidPhysics.java
    PistonPhysics.java
    VehiclePhysics.java
```

`AutomationManager` 只负责 Plugin Player 的注册、查询、替换、移除、Fresh Login、
tick 生命周期和 shutdown。Manager 保留现有 Logger 字段和构造参数。
动作方法从 Manager 迁移到实际 owner。Manager 不保留动作转发、`run(...)` 或
`actionName`。

`AutomationService` 负责协议状态、Shadow 开关、被动客户端职责和主动动作计划。
主动动作不依赖 Shadow。被动客户端职责只在 `shadow=true` 时运行。

`AutomationService.shadow()` 是唯一 Shadow 调用入口。它在 owner EventLoop 校验
Service 和 backend，设置 `shadow=true`，然后通过 Velocity Player Kick 当前真实
玩家。它不清除动作计划、输入或玩家状态。它不调用 `requestShadow()`、
`enterShadow()` 或其他 Shadow helper。

Plugin Player 包装 Velocity Player，并持有 World 和 AutomationService。它负责
连接访问、玩家状态、实际动作 Packet、被动物理计算和移动输出。它不保存 Shadow
开关。

`PlayerCommandHandler` 和旧 `PlayerCommand` 合并为新的 `PlayerCommand`。新类直接
实现 `SimpleCommand`，只解析、建议和执行 `shadow`。Shadow 解析从
`PlayerCommandParser` 移入新类。合并后不保留 Parser、Kind 和 Parsed record。
`ActionMode` 移到 `automation/`。

`FppCommand` 不修改逻辑、文本、建议或行为。IDEA 符号重命名只更新它对
`PlayerCommandHandler` 的类型引用。其他过时代码留给后续清理。

`Decoder` 只解析和查询固定 `minecraft-data.bin` 数据。`Block` 和
`EntityTypeData` 是固定类型数据，进入 `world/data/`。Decoder 不持有 Player、World、
协议或运行时状态，也不新增 Registry 类型。Known Pack 逻辑进入 AutomationService。
Dimension Type 查询保留在 data，World 使用查询结果更新运行时 Dimension 状态。

`world/world/World` 只持有单个 Player 的运行时世界状态，包括 Chunk、Block Entity、
Entity、Passenger、Border、Tick 和 Moving Piston。World 通过 Decoder 查询 Block
和 Entity Type，不保存类型表副本。

`Entity` 只保存通用实例状态、关系、碰撞和插值。`Vehicle` 保存全部载具运行时状态、
metadata、控制权、tick 和座位计算。`EntityTypeData` 只保存不可变类型数据和嵌套
Vehicle 类型数据。`PistonReaction` 重构为 `affectedByPiston` boolean。外部调用不直接
读取 EntityTypeData。

本节不增加功能、Patch 接口、Manager、Service、tick task、Registry 类型、Entity
子类或测试类型。

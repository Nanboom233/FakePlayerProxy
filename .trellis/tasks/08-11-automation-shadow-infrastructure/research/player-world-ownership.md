# Research: Player 分层和 World 所有权

- Query: 重新设计 Plugin Player。Player 包含 Velocity Player，并按 Vanilla 边界补全状态。比较共享 World 和玩家独立 World。
- Scope: internal / mixed
- Date: 2026-08-14

## Findings

### 当前状态归属

`AutomationManager` 当前保存 `Map<Velocity Player, AutomationService>`。
它负责注册、Fresh Login 替换、连接解析、EventLoop 切换和周期 tick。

`AutomationService` 当前同时保存以下状态：

- 协议阶段、Cookie、Chat Ack 和 Player Loaded。
- Action 调度和 Input。
- Player 的位置、速度、旋转、Pose、生命和移动发送基线。
- 当前 World 的 Registry 和 Chunk。
- 其他 Entity。
- PlayerPhysics。

证据：

- `AutomationManager.java:25` 保存 Player 到 Service 的 Map。
- `AutomationManager.java:91-94` 创建 Service 并安排 tick。
- `AutomationManager.java:100-117` 每次从 Velocity Player 取得前端和后端连接。
- `AutomationService.java:56-89` 把协议、Action、Player、Entity 和 World 状态放在一个类中。
- `AutomationService.java:106-159` 在 Login、Respawn 和 Configuration 中同时重置多类状态。
- `AutomationService.java:217-405` 直接处理 Player、Entity 和 World Packet。
- `AutomationService.java:520-595` 同时运行协议 tick、Action、物理和移动 Packet 输出。

当前 `WorldState` 已经按玩家持有。
每个 `AutomationService` 创建一个 `WorldState`。
`PhysicsData` 是共享的固定 Minecraft 26.2 数据。

现有结构的主要问题不是状态缺失。
主要问题是 `AutomationService` 没有清晰的状态边界。
Packet Handler 也需要反复取得 Service 和 Backend Connection。

### Vanilla 的边界

Vanilla 没有把整个客户端状态放进 `LocalPlayer`。
它使用以下边界：

| Vanilla 类型 | 所有状态 |
| --- | --- |
| `Entity` | Level 引用、位置、速度、旋转、AABB、Pose、Ground、流体、火焰、活塞移动和乘客关系 |
| `LivingEntity` | 生命、吸收、Attribute、Effect、受伤状态、死亡状态和使用物品状态 |
| `Player` | Profile、Abilities、Food、Inventory、选择槽和 Player 特有状态 |
| `LocalPlayer` | Connection、Input、Sprint、移动 Packet 基线和 20 tick 位置提醒 |
| `ClientLevel` | Chunk、Block Entity、Entity 表、World Border、Dimension 数据和 Level tick |
| `ClientPacketListener` | 协议会话、Registry、Chat、Cookie、当前 ClientLevel 和 Packet 到状态的转换 |

关键证据：

- `Entity.java:208-301` 保存 Entity 的基础状态和 `Level` 引用。
- `LivingEntity.java:220-282` 保存 Attribute、Effect、生命和受伤状态。
- `Player.java:151-173` 保存 Inventory、Food、Abilities 和 Profile。
- `LocalPlayer.java:111-170` 继承 Player，并保存 Connection、Input 和发送基线。
- `LocalPlayer.java:228-299` 运行 tick，并选择 Move Player Packet。
- `ClientLevel.java:149-179` 保存 Entity Storage、Chunk Source 和 World 状态。
- `ClientLevel.java:462-485` tick Entity 和 Passenger。
- `ClientPacketListener.java:391-431` 保存协议会话和当前 ClientLevel。
- `ClientPacketListener.java:493-535` 在 Login 时创建 ClientLevel 和 LocalPlayer。
- `ClientPacketListener.java:1245-1323` 在 Respawn 时按维度变化重建 ClientLevel 和 LocalPlayer。

一个 Vanilla 客户端只持有自己的 `ClientLevel`。
多个客户端不会共享一个可变 `ClientLevel`。
服务端共享的实际 World 不等于客户端收到的 World 视图。

### 推荐的 Player 分层

项目应固定使用以下术语：

- `Player`：Plugin 的玩家对象。
- `Velocity Player`：`com.velocitypowered.api.proxy.Player`。
- `World`：该 Player 当前收到的客户端 World 视图。
- `Entity`：参与 Player 计算的实体状态。

推荐结构：

```text
AutomationManager
└── Map<Velocity Player, Player>
    └── Player extends LivingEntity
        ├── Velocity Player
        ├── ProtocolState
        ├── AutomationState
        └── World

Entity
└── LivingEntity
    └── Player
```

各层的唯一职责如下：

| 类型 | 唯一职责 |
| --- | --- |
| `AutomationManager` | Player 注册、Fresh Login 替换、EventLoop 路由和生命周期 |
| `Entity` | 位置、速度、旋转、AABB、Pose、Ground、碰撞、流体和载具关系 |
| `LivingEntity` | 生命、吸收、Attribute、Effect、受伤和死亡状态 |
| `Player` | 包含 Velocity Player，并组合协议、Automation、输入、发送基线和当前 World |
| `World` | 当前 Dimension、已加载 Chunk、Block、Block Entity、World Border 和其他 Entity |
| `PlayerPhysics` | 只执行计算。它不拥有 Player 或 World 状态 |

`Player` 应保存准确的 Velocity Player 引用。
它不应再复制前端或后端连接字段。
它应按需从 Velocity Player 取得连接。
这样可以避免连接引用和 Velocity association 产生两个 owner。

Packet Event 仍使用 Velocity Player 查找 Plugin Player。
查找后，Packet Handler 只把 Packet 交给 Plugin Player。
Plugin Player 在自己的连接 EventLoop 修改状态。

Fresh Login 仍创建新的 Plugin Player。
旧 Plugin Player 在旧连接 EventLoop 关闭。
这个调整不改变已经确定的 Fresh Login 行为。

该分层参照 Vanilla 的状态边界。
它不要求复制 Vanilla 的渲染、背包、配方和声音状态。
初版只增加 Player 计算实际读取的字段。

### World 按玩家持有

玩家独立 World 的成本如下：

- 内存约为所有 Player 已加载 Chunk 和 Entity 的总和。
- 相同 Chunk 会在多个 Player 中重复保存。
- 相同 Chunk Packet 会为每个 Player 重复解码。
- Packet 不需要额外分发。Packet 只更新接收它的 Player。
- 每个 World 只由一个连接 EventLoop 修改。
- Respawn、Dimension Switch 和 Backend Switch 可以直接替换当前 World。
- Player 关闭后，World 随 Player 一起释放。

该方案保持客户端可见状态准确。
不同 Player 可以拥有不同 View Distance、Chunk 集合和 Entity 集合。
插件产生的按玩家 Block 或 Entity 修改也不会污染其他 Player。

复杂度为：

```text
Memory = O(sum(player loaded chunks) + sum(player visible entities))
Decode CPU = O(chunk packet deliveries)
Synchronization = O(1) per Player
```

### World 按后端服务器共享

共享动态 World 可以减少重叠 Chunk 的内存。
它也可能减少相同完整 Chunk 的重复解码。

但是后端 Packet 只描述接收 Player 的客户端视图。
它不是后端 World 的完整事件流。

共享动态 World 必须额外处理以下状态：

- 每个 Player 的已加载 Chunk 集合。
- 每个 Player 的 Chunk unload。
- 每个 Player 的 Entity 可见集合。
- 不同 View Distance 和加载时间。
- Dimension 和 Backend generation。
- 按玩家 Packet 修改。
- 多个连接 EventLoop 的写入顺序。
- 共享数据的引用计数或回收。

Entity 不能直接按服务器共享。
同一个 Entity 可能只对部分 Player 可见。
一个 Player 的 Remove Entities 也不能删除其他 Player 的 Entity。
共享 Entity 会让不可见 Entity 参与 Player 碰撞。

Chunk 也不能只用 `backend + dimension + chunk position` 作为唯一值。
两个 Player 可能在不同时间收到不同版本。
一个 Player 的 Forget Chunk 不能删除其他 Player 仍在使用的 Chunk。

多个连接 EventLoop 会同时写共享 World。
实现必须增加锁、单独 World Executor 或不可变快照。
锁和 Executor 会增加延迟与竞争。
它们也会破坏当前按 Player 串行处理的简单模型。

复杂度为：

```text
Memory = O(unique shared chunk versions + player visibility sets)
Decode CPU = O(unique identical chunk payloads), only after deduplication
Synchronization = O(all shared updates)
```

共享 World 不会减少网络 Packet 数量。
后端仍会向每个 Player 发送 Packet。

### 混合方案

初版不需要一个可变的 `BackendWorld`。
正确的初版边界是：

```text
Shared PhysicsData
Player-owned World
Player-owned Entity visibility
Player-owned Player state
```

`PhysicsData`、Block Shape 和固定 Registry 定义适合全局共享。
它们不可变，并且与 Player 可见性无关。

如果性能数据证明 Chunk 重复占用过高，可以增加一个透明缓存：

```text
WorldSnapshotCache
└── immutable ChunkSnapshot

Player World
└── Chunk position -> ChunkSnapshot reference
```

缓存只能共享不可变 ChunkSnapshot。
Player World 仍拥有 loaded set、Block update 结果、Dimension 和 Entity 表。
Block update 应使用 copy-on-write 或新的 ChunkSnapshot。
Packet 只能更新接收 Player 的 World。
缓存不能把一个 Player 的 Packet 广播到其他 Player。

缓存键至少需要：

- Backend generation。
- Dimension Key。
- Chunk position。
- Chunk 内容 hash 或版本。

因此，`shared immutable data + shared backend world + per-player view` 的完整三层结构目前过重。
推荐先使用共享固定数据和玩家独立 World。
后续只在测量后增加不可变 ChunkSnapshot 缓存。
该缓存不是 World 的事实来源。

### 性能和正确性比较

| 维度 | Server-shared World | Player-owned World | 推荐混合方案 |
| --- | --- | --- | --- |
| Chunk 内存 | 重叠高时较低 | 重复保存 | 不可变 Chunk 可按内容共享 |
| Entity 内存 | 较低，但可见性错误风险高 | 按 Player 重复 | Entity 保持 Player-owned |
| Chunk 解码 | 可去重，但需要 hash 和同步 | 每次 Packet 解码 | 完整快照可缓存 |
| Packet fanout | 不减少，还需维护订阅关系 | 无额外 fanout | Packet 只更新接收 Player |
| EventLoop | 需要跨 EventLoop 同步 | 单 Player 串行 | 共享对象只读 |
| Dimension Switch | 需要引用计数和 generation | 直接替换 World | Player 替换引用 |
| Backend Switch | 必须隔离服务器和重启世代 | 直接清理 Player World | 缓存按 Backend generation 隔离 |
| View Distance | 需要独立 membership | 自然准确 | membership 保持 Player-owned |
| Entity Visibility | 容易泄漏不可见 Entity | 自然准确 | 保持 Player-owned |
| 生命周期 | 需要回收策略 | 跟随 Player | 缓存独立限额或弱引用 |
| 初版复杂度 | 高 | 低 | 先实现两层，缓存延后 |

### 架构结论

1. `AutomationManager` 应保存 `Map<Velocity Player, Player>`。
2. Plugin `Player` 应包含准确的 Velocity Player。
3. Plugin `Player` 应成为单个玩家状态的唯一 owner。
4. Player 物理状态应按 `Entity -> LivingEntity -> Player` 分层。
5. `World` 应包含 Chunk 和其他 Entity。Player 只保存自己的实体状态。
6. 动态 World 应由 Player 持有。
7. 固定 PhysicsData 应全局共享。
8. 共享动态 BackendWorld 不应进入初版。
9. 后续优化只能共享不可变 ChunkSnapshot。
10. Packet 顺序和可见性始终以接收 Player 为准。

## Files Found

- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java` - 当前 Player Map、连接解析和生命周期。
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java` - 当前协议、Automation、Player、Entity 和 World 混合状态。
- `plugin/src/main/java/com/fakeplayerproxy/automation/WorldState.java` - 当前玩家独立 Registry 和 Chunk。
- `plugin/src/main/java/com/fakeplayerproxy/automation/EntityState.java` - 当前其他 Entity 状态。
- `plugin/src/main/java/com/fakeplayerproxy/automation/PlayerPhysics.java` - 当前无状态 Player 计算器。
- `plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java` - Packet Event 到 Service 的路由。
- `minecraft-merged-deobf-26.2-sources.jar` - Minecraft 26.2 的 LocalPlayer、ClientLevel 和 Entity 源码。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/vanilla-damage-physics.md` - Player 计算所需 World 和 Entity 状态。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/vanilla-physics-boundary-review.md` - 客户端和服务端物理边界。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/carpet-fake-player-calculation.md` - Carpet 的服务端 World 复用边界。

## Code Patterns

- `AutomationManager.java:25` - 当前 Map 的 key 是准确的 Velocity Player。
- `AutomationService.java:52-89` - 当前单类保存全部可变状态。
- `WorldState.java:23-30` - 当前 World 保存 Registry、Chunk 和 Dimension。
- `LocalPlayer.java:111-170` - Vanilla LocalPlayer 的 Connection、Input 和发送基线。
- `ClientLevel.java:149-179` - Vanilla ClientLevel 的 Chunk 和 Entity 所有权。
- `ClientPacketListener.java:493-535` - Login 创建 ClientLevel 和 LocalPlayer。
- `ClientPacketListener.java:1245-1323` - Respawn 的 World 和 Player 重建边界。

## External References

- 未使用网络资料。
- Minecraft Java 版本固定为 26.2。
- 源码来自本地 `minecraft-merged-deobf-26.2-sources.jar`。

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`
- `.trellis/spec/language/java.md`
- `.trellis/spec/guides/cross-layer-thinking-guide.md`

## Caveats / Not Found

- 现有 PRD 和 Design 明确禁止 `AutomationService` 保存 Player 或连接。用户本次要求是在讨论重新设计。主会话必须在用户确认后最小修改该既有结论。
- 当前没有 Player 数量、View Distance、Chunk 重叠率和内存测量。不能证明共享动态 World 已经必要。
- MCProtocolLib 只提供 Packet 和 Chunk 数据结构。它不提供完整 Vanilla ClientLevel。
- 本研究只设计状态所有权。它没有扩大初版物理行为范围。

## Follow-up: Backend Server 持有 World

- Query: 接受 Backend Server 持有 World、不区分玩家具体内容、按 Player 引用计数卸载的前提，给出最简可行结构。
- Scope: internal
- Date: 2026-08-14

本节接受新的设计前提。
本节覆盖前文“初版不共享动态 BackendWorld”的推荐。
前文性能和风险比较仍保留为决策记录。

### ServerWorld Key

`VelocityServerConnection` 属于一个 Player。
它不能作为共享 Backend Server 的 key。

`ServerInfo` 只包含名称和地址。
它也没有后端进程 generation。

证据：

- `VelocityServerConnection.java:65-69` 保存 RegisteredServer、Player 和该 Player 的 MinecraftConnection。
- `VelocityServerConnection.java:224-235` 只能取得 RegisteredServer 和 ServerInfo。
- `VelocityRegisteredServer.java:73-83` 保存不可变 ServerInfo 和连接到该 Server 的 Player 集合。
- `ServerInfo.java:21-39` 只保存名称和地址。
- `ServerInfo.java:52-65` 的相等性也只比较名称和地址。

推荐 key：

```text
BackendGeneration
├── RegisteredServer identity
└── local generation number

ServerWorldKey
├── BackendGeneration
└── worldName
```

`RegisteredServer identity` 必须使用对象 identity。
同名同地址的重新注册不能自动复用旧 World。

`worldName` 使用 `PlayerSpawnInfo.getWorldName()`。
Dimension Type ID 只作为 World metadata。
多个 World 可以使用相同 Dimension Type。

Velocity 没有提供后端进程 generation。
Plugin 必须创建本地 generation。

最小 generation 规则：

1. 第一个 Player 连接某个 RegisteredServer 时创建 BackendGeneration。
2. 后续 Player 连接同一个 RegisteredServer identity 时复用该 generation。
3. 最后一个 Player 离开时销毁 generation 和全部 ServerWorld。
4. 下一次连接创建新的 generation。

后端重启通常会关闭全部旧连接。
该规则可避免新连接复用旧 Chunk。

如果后端在仍有连接时原地重载 World，协议没有可靠的 generation 标识。
该情况只能依赖新的 Full Chunk 修正已引用区域。

### Chunk 引用计数

引用计数必须记录 Player membership。
只有一个整数不能保证重复 Load 和 Forget 幂等。

推荐结构：

```text
ServerWorld
├── Map<ChunkPos, ChunkEntry> chunks
└── Map<Player, Set<ChunkPos>> playerChunks

ChunkEntry
├── Chunk data
├── Set<Player> holders
└── Player source
```

`holders.size()` 就是引用计数。
不要再保存第二个可漂移的 `int refCount`。

Full Chunk 处理：

1. 解码完整 Chunk 到临时对象。
2. 在 ServerWorld 写锁内取得 Player 的 Chunk Set。
3. `playerChunks[player].add(pos)` 返回 false 时，不增加引用。
4. ChunkEntry 不存在时，安装临时对象并把该 Player 设为 source。
5. ChunkEntry 已存在时，只增加 holder，不覆盖现有 Chunk。

Forget Chunk 处理：

1. 在 ServerWorld 写锁内执行 `playerChunks[player].remove(pos)`。
2. 返回 false 时忽略该重复 Forget。
3. 返回 true 时从 `ChunkEntry.holders` 删除该 Player。
4. holders 为空时删除 ChunkEntry。
5. source 离开但 holders 非空时，从剩余 holder 选择新 source。

Player 离开、Dimension Switch 和 Backend Switch 必须调用统一的 detach。
detach 遍历该 Player 的 Chunk Set，并执行一次相同的 holder 删除。

双向 Set 是必要数据。
它支持以下操作：

- Full Chunk 幂等。
- Forget 幂等。
- O(Player loaded chunks) 的断开清理。
- source 的快速校验和切换。

### Full Chunk 的覆盖规则

不同 Player 的 Packet 流没有共享 server sequence。
后到的 Full Chunk 不一定更新。

以下场景会发生旧快照覆盖：

```text
Player A: Full Chunk old -> Block Update new
Player B: Full Chunk old arrives late
```

如果 B 的 Full Chunk 覆盖共享 Chunk，`new` 会回退成 `old`。

因此，ChunkEntry 存在且仍有 holder 时，后续 Full Chunk 不能覆盖数据。
它只增加 holder。

当 holders 归零后，ChunkEntry 被删除。
下一次 Full Chunk 才建立新的基线。

该规则接受一个前提：
只要 Chunk 仍被引用，source 会收到该 Chunk 的 Block Update。

### Block 和 Section Update

Block Update 是赋值操作。
相同状态的重复 Packet 本身是幂等的。

但是不同状态的更新可以跨连接重排：

```text
Server: state A -> state B
Player 1 stream: A -> B
Player 2 stream: A -> B
Shared arrival: Player 1 A -> Player 1 B -> Player 2 A
```

直接处理所有 Packet 会让共享状态从 B 回退到 A。

最简规则是每个 Chunk 只接受 source Player 的更新：

- Full Chunk 第一个 holder 成为 source。
- 只处理 source 的 Block Update 和 Section Blocks Update。
- 其他 holder 的相同 Packet 作为重复 Packet 忽略。
- source Forget 或离开时，从剩余 holder 选择新 source。

该 source 只负责 Packet 顺序。
它不表示该 Player 拥有独立 World 内容。

如果 Update 指向未加载 Chunk，直接忽略。
不要为未知 Chunk 创建空气或只有增量的 Chunk。
后续 Full Chunk 会建立完整状态。

source 切换存在一个无法完全消除的边界风险。
新 source 的 Packet 流可能领先或落后于旧 source。
协议没有跨连接 sequence，因此不能证明两个流的切换点完全一致。
接受共享单一内容前提后，该风险只能作为已知限制保留。

### Entity 是否共享

初版不应把 Entity 放进共享 ServerWorld。

Entity Packet 比 Block Packet 多一个问题。
相对 Move Entity Packet 是增量。
多个 Player 收到相同 Packet 时，重复应用会让 Entity 移动多次。

Entity 还包含以下 per-connection 生命周期：

- Add Entity。
- Remove Entities。
- 相对移动基线。
- Teleport 和绝对同步。
- Metadata、Passenger 和 Equipment。

共享 Entity 需要独立的 holder、source 和 generation。
它还需要处理 Entity ID 复用和 source 切换。

最简边界是：

```text
Backend Server
└── ServerWorld
    └── shared Chunk and Block

Player
└── Entity Map
```

PlayerPhysics 从共享 ServerWorld 查询 Block。
它从当前 Player 的 Entity Map 查询实体碰撞。

后续如果需要共享 Entity，key 至少为：

```text
BackendGeneration + worldName + entityId
```

初版不增加该复杂度。

### 多 EventLoop 的同步 owner

不同 Player 可以使用不同连接 EventLoop。
`ConcurrentHashMap` 不能保护 Load、holder、source 和 Chunk 替换的组合操作。

最小同步 owner 是 `ServerWorld`。

推荐每个 ServerWorld 使用一个 `ReentrantReadWriteLock`：

- Full Chunk 在锁外解码。
- Load、Forget、detach 和 Block Update 使用写锁。
- PlayerPhysics 的一次完整 Block 查询使用读锁。
- 不在锁内发送 Packet。
- Entity Map 仍由 Player 的连接 EventLoop 独占。

该方案不增加 World Executor。
它也不让 Player 状态跨 EventLoop 迁移。

后续只有在锁竞争被实际测量后，才考虑不可变 ChunkSnapshot 或专用 World Executor。

### 接受前提后的最简架构

```text
AutomationManager
├── Map<Velocity Player, Player>
└── Map<RegisteredServer identity, BackendGeneration>

BackendGeneration
├── local generation number
├── attached Player count
└── Map<worldName, ServerWorld>

ServerWorld
├── ReentrantReadWriteLock
├── Map<ChunkPos, ChunkEntry>
└── Map<Player, Set<ChunkPos>>

Player
├── Velocity Player
├── current BackendGeneration
├── current ServerWorld
├── Entity Map
└── Player state and Automation state
```

固定规则：

1. BackendGeneration 是 Server 共享 World 的生命周期 owner。
2. ServerWorld 是 Chunk、holder、source 和同步的 owner。
3. Player 只保存当前 ServerWorld 引用，不保存 Chunk 内容。
4. ChunkEntry 只使用一个 source Packet 流。
5. Full Chunk 只在 ChunkEntry 首次创建时安装。
6. holders 归零后立即卸载 Chunk。
7. Block 和 Section Update 只接受 source Player。
8. Entity Map 继续按 Player 持有。
9. PlayerPhysics 在 ServerWorld 读锁内查询 Block。
10. 最后一个 Player 离开时销毁整个 BackendGeneration。

### Follow-up Caveats

- Velocity 和 Minecraft 协议都没有提供后端进程 generation。
- 同地址负载均衡到不同后端进程时，RegisteredServer identity 不足以证明 World 相同。
- source 切换无法建立跨连接的严格 Packet 顺序。
- 用户前提排除了 per-player Block 修改。若后端插件发送不同 Block 内容，共享 World 会选择 source 的内容。
- Entity 仍按 Player 持有。该决定避免相对移动重复应用和 Entity visibility 混合。

## Final Decision: Player 持有 World

- Date: 2026-08-14
- Decision: 不采用 `ServerWorld` 共享方案。

量化结果不能抵消共享方案的状态和同步复杂度。

每个 Plugin `Player` 继续持有独立的 `World`。

本报告的共享方案分析保留为历史研究，不进入实施设计。

实施不能增加 `BackendGeneration`、Chunk holder、source、引用计数或共享锁。

只有不可变的固定 `PhysicsData` 可以跨 `Player` 共享。

`World` 的 Entity registry 必须登记本地 `Player` 对象和其他 `Entity` 对象。

本地 `Player` 使用服务端 Entity ID，不能创建第二个 Entity 状态副本。

每个 `Entity` 直接保存可空 `vehicle` 和有序 `passengers` 对象关系。

`World` 负责完整 `SetPassengers` 替换，并保持关系两端一致。

Entity 移除必须解除全部关系。

实施不能增加独立关系 Map、关系 Manager 或 pending 关系层。

Plugin 状态使用 `Entity -> LivingEntity -> Player` 继承结构。

`Player` 保存准确的 Velocity Player 引用，但不继承或实现 Velocity Player。

该分层只增加 Player 计算需要的状态。

`AutomationManager` 保存 `Map<Velocity Player, Player>`。

Plugin `Player` 创建并持有自己的 `AutomationService`。

`AutomationService` 保存 final `Player` owner，但不保存连接字段。

`AutomationService` 持有协议阶段、Cookie、Chat Ack、Player Loaded、`shadow` 和 Action 状态。

`Player` 持有物理状态、输入、移动发送基线和 `World`。

`AutomationService` 通过 final owner 处理 Packet 和 tick。

实施不能为这些状态增加其他 Service owner。

实施不保留单体 `PlayerPhysics` 行为 owner。

`Entity.move()` 处理位移、碰撞、流体、活塞和实体推挤。

`LivingEntity.travel()` 处理重力、阻力、Attribute 和 Effect。

`Player.tick()` 处理零输入、乘客分支和移动 Packet 选择。

`World` 提供环境和 Entity 查询。

AABB 等纯计算 helper 保持无状态。

`EntityFactory` 只在 Add Entity 时选择行为类型。

实现只为不同客户端行为增加 Entity 子类。

客户端行为族固定为 Boat、AbstractHorse、Camel、Pig、Strider、HappyGhast 和 AbstractNautilus。

Minecart 使用服务端插值行为。

普通 Entity root 只消费服务端移动并执行 passenger placement。

Tick 不能 switch Entity Type。

实施不能增加 `EntityBehavior` 接口或行为 Manager。

## Revised Entity Decision

- Date: 2026-08-14
- Decision: 撤销按载具行为族增加 Entity 子类的设计。

运行时只保留 `Entity -> LivingEntity -> Player` 继承结构。

`Entity` 保存共享的 `EntityDefinition` 和可空 `VehicleState`。

`EntityDefinition` 从固定 `PhysicsData` 提供类型、尺寸、attachment、Metadata、Attribute 和 `MovementKind`。

`World.addEntity()` 只创建 `Entity` 或 `LivingEntity`。

`Entity.tickVehicle()` 只 switch `MovementKind`，不能 switch Entity Type。

实施不能增加行为族 Entity 子类、`EntityFactory`、`EntityBehavior` 或行为 Manager。

Plugin 大致参照 Vanilla，但只保留协议、关系、非主动位置、输出和生命周期读取的状态。

每个保留字段必须有对应的 Minecraft 26.2 读取路径。

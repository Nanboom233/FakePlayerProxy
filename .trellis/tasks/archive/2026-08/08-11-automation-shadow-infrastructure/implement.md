# Automation Shadow 实施计划

## 状态

任务实施已完成，等待独立 check。

现有代码已经完成 Patch、relay、Automation 生命周期、基础协议、Shadow、Fresh Login 和初版 Player 计算。

实测已确认连接成功。现有 `PlayerPhysics` 未通过完整位置行为验收。

本轮只重构 Plugin Player。不要重新实现 Patch、relay、命令或 Mod UI。

本轮保持一个任务。Player、World、Entity registry 和移动输出必须同步迁移。

## 已完成基线

- `0001-login-relay.patch` 提供 login relay、加密、解密和 raw tunnel。
- `0002-automation-extension.patch` 提供 Packet Event、Packet 发送、连接访问和可取消登出。
- Patch 测试位于 `plugin/patch/test/`。
- 固定 Velocity source 保持 clean。构建使用一次性 worktree。
- `AutomationManager` 已支持多 Player、Fresh Login 和准确删除。
- `AutomationService` 已处理基础协议、配置切换、Chat Ack、Cookie 和 Player Loaded。
- `/player shadow` 已关闭前端并保留后端。
- 固定 26.2 Block 数据、Chunk 解码和初版移动输出可以复用。
- 最终 Velocity JAR 已包含 MCProtocolLib 所需的 runtime 依赖。

## 1. 迁移 Owner

1. 创建 `Entity`、`LivingEntity`、`Player` 和 `World`。
2. 让 `Player` 包装准确的 Velocity Player。
3. 让 `Player` 持有 `World` 和 `AutomationService`。
4. 让 `AutomationService` 保存 final `Player` owner。
5. 让 `AutomationManager` 保存 `Map<Velocity Player, Player>`。
6. 把现有状态移到唯一 owner。
7. 每次迁移一个行为后，删除旧 owner 中的对应状态。
8. 迁移完成后，删除 `EntityState`、`WorldState` 和 `PlayerPhysics`。

不要创建并行状态副本。不要增加其他状态 Service。

## 2. 扩展固定数据

1. 生成 `EntityDefinition`、Pose 尺寸、attachment 和 `MovementKind`。
2. 生成 Automation 使用的 Metadata 和 Attribute 默认值。
3. 增加 Block Shape、摩擦、速度因子、回弹和 Fluid 数据。
4. 增加 `BlockBehaviorKind` 和所需参数。
5. 生成器只输出运行时表数据，不写入版本、提交或 hash 元数据。
6. 在 `minecraft-data/` 下保留 `minecraft-data-generator-LICENSE` 根目录 MIT 声明。

普通构建不能运行生成器。运行时不能加载 Minecraft、Prismarine 或 Minestom。

## 3. 补全 Packet 状态

1. 处理 Login 和 Respawn 的保留标志。
2. 处理 Position、Rotation、LookAt、Motion、Explosion 和 Health。
3. 处理位置计算需要的 Metadata、Attribute、Effect、Ability 和 Equipment。
4. 处理 Entity Add、Remove、Move、Sync、Teleport、Motion 和 Minecart 插值。
5. 处理 Passenger、Tag、Chunk、Block、移动活塞、World Border 和 Tick Rate。
6. 在 Configuration、Respawn、Dimension Switch 和 Fresh Login 时清理准确状态。

未知 Entity Packet 直接丢弃。`SetPassengers` 不保存 pending 关系。

Packet handler 直接更新明确字段。不要增加通用状态容器。

## 4. 实现 World 和 Entity

1. 让每个 `Player` 持有独立 `World`。
2. 把本地 `Player` 和其他 Entity 放入同一个 Entity registry。
3. 在现有 `ChunkSection[]` 应用 Block Update。
4. 让未知 Chunk 返回空碰撞和空 Fluid 结果。
5. 让未知 Chunk 继续保持未加载状态。
6. 实现 Block、Entity、World Border 和移动活塞碰撞。
7. 实现 Vanilla 轴顺序、台阶和回弹。
8. 在 `Entity.move()` 中处理 `BlockBehaviorKind`。

不要增加第二套 Block State。不要增加 Block handler 对象或 Behavior Manager。

## 5. 实现 LivingEntity 和 Player

1. 在 `LivingEntity.travel()` 中处理 Air、Water、Lava、Flight 和持续 Glide。
2. 应用移动相关 Attribute 和 Effect。
3. 应用 Ability、Equipment、Pose、Scale 和空间适配。
4. 处理自然下落、普通击退、Explosion 和 Entity 推挤。
5. Shadow 接管时清空一次玩家控制输入；`Player.tick()` 保留并读取现有 Action 的 `InputState`，不在每 tick 清空。
6. 使用现有 50 ms 任务运行协议 Tick。
7. 使用一个时间累加值控制 Client game tick。
8. 让 Client game tick 遵循服务端 Tick Rate，并限制为 20 TPS。

不要创建第二个调度任务。不要重新计算伤害、传送目标或维度目标。

## 6. 实现 Passenger 和载具

1. 给每个 `Entity` 保存一个 vehicle 和有序 passengers。
2. 在 Entity 移除时解除全部关系。
3. 从 root 到 leaf 应用 passenger attachment。
4. 让 Passenger Player 停止自由移动计算。
5. 让普通 Entity 和 Minecart 使用服务端移动。
6. 只为本地控制 root 创建 `VehicleState`。
7. 在 `Entity.tickVehicle()` 中 switch `MovementKind`。
8. 处理 Boat、Horse、Camel、Pig、Strider、Happy Ghast 和 Nautilus。

不要增加载具 Entity 子类、EntityFactory、关系 Map 或关系 Manager。

## 7. 收敛输出

1. 让 Free Player、Passenger 和本地控制 root 使用互斥输出分支。
2. 让 Free Player 发送准确的 Move Player Packet。
3. 让 Passenger 发送 Rot。
4. 让本地控制 root 发送 Move Vehicle。
5. 让 Boat 发送零 Paddle。
6. 在服务端 Position 后发送 Teleport Ack 和准确 PosRot。
7. 在每个 Client game tick 末尾发送 Client Tick End。
8. 让死亡只停止普通移动 Packet。

Player Loaded 只控制初次加载和 Respawn。未知 Chunk 不控制 Tick。

## 8. 验证

1. 保留现有测试。Owner 迁移只修改原断言指向，不复制测试。
2. 只为现有测试没有覆盖的独立行为分支增加测试。
3. World 和关系测试覆盖缺失 Entity、Passenger 完整替换、Entity 移除解绑和未知 Chunk。
4. Player 计算测试覆盖自由移动、Fluid、特殊方块、Block 与 Entity 碰撞、服务端校正和 Tick Rate。相同算法使用参数化用例。
5. 载具测试为每个不同 `MovementKind` 保留一个代表用例。不能按 Entity Type 重复测试同一分支。
6. 实测只保留两个端到端流程。第一个流程覆盖 Shadow、自然下落、受击和 Fresh Login。第二个流程覆盖 Passenger 到本地控制载具的切换。

不要为每个 Packet、Metadata 字段、Attribute、Entity Type 或 Block State 单独增加测试。

按此顺序运行命令：

```powershell
.\gradlew :plugin:test
.\gradlew :plugin:patchCheck
.\gradlew :plugin:releaseJar
.\gradlew build
```

检查以下结果：

- 固定 Velocity source 保持 clean。
- 一次性 worktree 不存在。
- Plugin JAR 不包含 MCProtocolLib、Netty、Lombok 或 Velocity 类。
- `:plugin:test` 使用只含最终 Velocity JAR 的隔离 ClassLoader 执行 Java 21 runtime smoke。

## 回退

每个迁移步骤都必须保持一次编译通过的 owner 切换。

如果一个步骤失败，恢复该步骤的 owner 调用。不要恢复已经迁移的其他步骤。

不要直接修改固定 Velocity source。不要把测试写入 Patch。

## 实施结果

完成日期：2026-08-14。

- [x] Owner 已迁移到 `Entity -> LivingEntity -> Player` 和 Player-owned `World`，旧 `EntityState`、`WorldState`、`PlayerPhysics` 已删除。
- [x] 固定 26.2 资源使用紧凑二进制表，包含结构化 Block State、上下文参数、Pose/attachment、明确 Metadata schema、Attribute 默认值和控制物品 ID。
- [x] Packet 状态覆盖 Position/Rotation/LookAt/Motion/Explosion/Health、Metadata/Attribute/Effect/Ability/Equipment、Entity/Passenger、Tag/Chunk/Block/Piston、Border/Tick Rate 和 Move Vehicle correction。
- [x] World/Entity 实现方块、Entity、Border、Moving Piston、Powder Snow 和 Scaffolding 上下文碰撞，以及轴顺序、台阶、回弹、流体和实体推挤。
- [x] LivingEntity/Player 实现 Air/Water/Lava/Flight/Glide、位置相关 Effect/Equipment、Pose/Scale fit、单次 Shadow 输入清零和零主动输入持续计算。
- [x] Passenger graph、每 Pose attachment、Boat 多座规则、全部 `MovementKind`、明确控制权、Pig/Strider boost 和互斥输出分支已接入。
- [x] 固定资源 round-trip 通过；运行时只校验二进制结构和 EOF，不做来源或完整性 hash 校验。
- [x] `PlayerTest`/`WorldTest`/`AutomationServiceTest` 专项通过；完整 `:plugin:test` 共 72 tests、0 failures、0 errors、0 skipped。

### Known Packs 和固定 Registry 补充

- [x] 固定 26.2 generator 输出 `minecraft:core:26.2` 和四个 Vanilla Dimension Type 的 `min_y`/`height`。
- [x] 真实前端 C2S 选择使用 exact-all-or-empty Packet 替换；S2C Packet 保持不变。
- [x] Shadow 使用正常配置 handler 发送 exact-all-or-empty 选择，并保存实际选择。
- [x] null Dimension Type 只在 Automation 内部补全；服务端非 null 数据、顺序和 ID 保持不变。
- [x] 未解析 Registry 在断开前阻止 Shadow，并使用 Automation unavailable 用户路径。
- [x] Known Packs 和 Dimension Type 固定值已硬编码在 `VanillaRegistryData`，不生成 registry 元数据资源。
- [x] Known Packs/Registry 专项测试通过；完整 `:plugin:test` 将本轮总数更新为 81 tests、0 failures、0 errors、0 skipped。

独立 check 已完成 `:plugin:patchCheck`、`:plugin:releaseJar`、根 `build`、JAR 内容检查和 Java 21 smoke。Boat bubble metadata 未进入位置状态：固定 26.2 `AbstractBoat.tickBubbleColumn()` 的客户端分支只更新渲染角度，位置冲量属于服务端分支并由权威 Packet 消费。

## 后续移动修复计划

本次修复只修改 Plugin 和固定数据。
Patch、Velocity source、Known Packs、命令、Mod 和连接生命周期保持不变。

### 1. 核对现有证据

1. 读取现有 LevelChunk 失败日志。
2. 读取固定 26.2 源码研究。
3. 确认后续缺陷与 Registry P0 相互独立。
4. 不启动 Minecraft 后端。
5. 不执行连接实测或玩家视角实测。

### 2. 一次性补固定数据

1. 导出 Entity movement collision。
2. 导出 Entity piston reaction。
3. 导出 Block State Fluid face bitset。
4. 按 `world`、`world/entity` 的数据 owner 更新紧凑资源读取代码。
5. 更新来源、patch、输入和资源 SHA。
6. 确认普通构建不运行 generator。

完成后先运行资源 round-trip 和 `:plugin:compileJava`。

### 3. 修正 Position、Motion 和 Entity collision

1. 把 Player Position 回执和发送基线改为 false/false。
2. 删除普通 interpolation 的 velocity 保存和回写。
3. 让 Position Sync 忽略 delta movement。
4. 给 `Entity` 增加 relative-position codec 基线。
5. 让本地控制 root 继续推进该基线。
6. 让本地控制 root 忽略普通 Move Entity 和 Position Sync transform。
7. 用固定 movement collision 值筛选 Entity AABB。
8. 保持 Entity push 查询独立。
9. 用自动测试检查共享 Motion 路径和自然落地 Packet。

如果自动测试失败，只修共享 Motion 路径。
Plugin 不能增加伤害来源分支。

该阶段修改 `AutomationService`、`Entity`、`World` 和现有相关测试。

### 4. 修正 Piston

1. 接入 `ClientboundBlockEventPacket`。
2. 构造 extension 状态。
3. 构造 retracting source 状态。
4. 构造 sticky pulled block 状态。
5. 构造 cancelled-mid-push 状态。
6. 按每个 Shape 的轴向穿透推动 Entity。
7. 应用 piston reaction 和每 tick 位移限制。
8. 实现 Honey carry 和 Slime 轴速度。
9. 移除完成状态。
10. 对缺失输入输出诊断。
11. 不要创建占位 Piston。

该阶段使用 `world/World` 的内部活塞状态，不创建 handler 类。

### 5. 修正 Fluid

1. 把方法改为 `world/World.fluid(Entity, world/phys/AABB)`。
2. 分别累计 Water 和 Lava。
3. 实现完整液面和浅浸没。
4. 实现 Player 平均和其他 Entity 归一化。
5. 实现 Fluid scale 和最小水流推动。
6. 实现 falling face。
7. 如果邻接 Chunk 未加载，返回空结果。
8. 保持 `Entity.baseTick()` 只消费一个 `FluidSample`。

同一算法只保留一组参数化测试，不为每种 Block State 单独建测试。

### 6. 修正 Boat

1. 在对应 Entity 的内部载具状态中保存 Boat status、水面高度和 land friction。
2. 在 `MovementKind.BOAT` 分支计算 Boat status。
3. 运行通用 Fluid current。
4. 运行 `floatBoat()` 和零输入 move。
5. 未淹没时，阻止 Passenger 重复使用 Fluid current。
6. 增加本地控制规则。
7. 按 Paddle、Player Rot、root Move Vehicle 的顺序发包。
8. 用自动测试检查水流、浮力、Passenger attachment 和 Player Position。

不增加 Boat 子类，不实现主动 Paddle 或转向输入。

### 7. 聚焦验证

修改现有相反断言，新增测试只覆盖独立行为：

- Player Position 使用 false/false。
- pending interpolation 之后的 Motion 不能丢失。
- Position Sync 不能修改 velocity。
- 本地 root 不能被普通 transform 回滚。
- 本地 root 释放控制后使用已推进的 codec 基线。
- 普通 Living Entity 不作为 movement collision，但仍能产生 push。
- Fluid 使用一组参数化用例覆盖 Player/非 Player、浅水、falling face 和未知邻接 Chunk。
- Boat 使用一组状态用例覆盖 water/underwater/land/air，并验证一次完整输出顺序。
- Piston 使用一组状态转换用例覆盖 extend/retract/sticky/cancel，并验证 Shape push、Honey、Slime 和 cleanup。

不要按攻击来源复制单元测试，不测试日志文本，不新增 Patch 测试。

### 8. 完整自动验证

按顺序运行：

```powershell
.\gradlew :plugin:test
.\gradlew :plugin:patchCheck
.\gradlew :plugin:releaseJar
.\gradlew build
```

完整检查要求固定 Velocity source clean。
一次性 worktree 必须不存在。
Plugin JAR 依赖边界必须不变。
`:plugin:test` 拥有 Java 21 runtime smoke，且必须通过。

本次执行不启动 Minecraft 后端，也不执行玩家视角实测。

### 回退点

每个阶段完成聚焦测试后再进入下一阶段。
如果出现回归，只回退当前阶段。
固定数据格式和 loader 必须在同一阶段回退。
资源格式和 loader 不能不匹配。

## Registry P0 后剩余非主动位置修复结果

完成日期：2026-08-15。

- [x] 固定 26.2 紧凑资源增加 Block State fluid face bitset、Entity movement collision 和 Entity piston reaction；loader 已按同一字段顺序读取。
- [x] Player Position 使用 false/false 回执基线；Entity 使用独立 relative codec baseline；普通 interpolation 和 Position Sync 不再覆盖 velocity；本地控制 root 只推进 codec baseline。
- [x] movement collision 使用固定 Entity 值，Entity push 保持独立。
- [x] Piston BlockEvent 覆盖 extend、retract、sticky pull 和 cancelled-mid-push；per-shape push、reaction、0.51 每轴 tick 限制、Honey、Slime、完成态落盘和缺失输入诊断已接入。
- [x] Fluid 使用 Water/Lava 独立局部 accumulator，覆盖完整液面、浅浸没、Player 平均、非 Player 归一化、fluid scale、minimum nudge、falling face 和未知邻接 Chunk 空结果。
- [x] Boat 保存 status、水位和 land friction；按 status、通用 current、float、move 顺序计算，裁剪 passenger fluid box，并按 Paddle、Player Rot、Move Vehicle 顺序发包。
- [x] 聚焦与完整 `:plugin:test` 共 103 tests、0 failures、0 errors、0 skipped。
- [x] 最终顺序验证通过：`:plugin:test`、`:plugin:patchCheck`、`:plugin:releaseJar` 和根 `build`。
- [x] 固定 Velocity source clean，一次性 Velocity worktree 不存在，Plugin JAR dependency boundary clean，Java 21 `VelocityRuntimeSmokeTest` 实际执行成功。

本轮没有启动 Minecraft、Paper 或 Velocity 服务，没有连接后端，也没有执行玩家视角或手动连接实测。

## Build Task 与 Consent 收尾要求

- `server` 组只公开 `releaseJar` 和 `runServer`；内部 Velocity 产物任务不分组。
- `releaseJar` 和 `runServer` 不编译或执行测试源码，也不依赖测试或 verification 任务。
- Java 21 runtime smoke 迁移到普通 JUnit 测试，由 `:plugin:test` 使用最终 `velocity.jar` 的隔离 ClassLoader 执行。
- Plugin 编译和测试运行时通过一个不可解析的 `velocityHost` 依赖桶共享最终 Velocity JAR；MCProtocolLib、Netty、Guice 和 SLF4J 只保留 `compileOnly` 声明。
- Consent 的 `Do not notify me again for this server` 持久保存本次 Allow 或 Decline 决定。未勾选或按 Escape 时不保存；读取失败继续显示 Consent，写入失败继续本次登录。

## Minecraft Data 与检查收尾结果

完成日期：2026-08-16。

- [x] 删除 `automation-registry-26.2.properties` 和 `physics-data-26.2.properties`；固定 Known Pack 与 Dimension Type 值改为 Java 常量。
- [x] runtime 只保留 `minecraft-data/minecraft-data.bin` 和 `minecraft-data/minecraft-data-generator-LICENSE`。binary 不再包含 format/version/commit/hash header，loader 只做结构解码与边界校验。
- [x] generator 不再读取未使用的 `attributes.json`，也不再生成 Automation registry 数据；未增加任何替代 integrity/version/hash 机制。
- [x] `AutomationService.shadow` 使用 Lombok `@Getter`；Plugin 通过 `compileOnly` 与 `annotationProcessor` 使用 Lombok 1.18.42，最终 Plugin JAR 不包含 Lombok 类。
- [x] IDEA MCP 对实际修改的 Gradle、Kotlin、Plugin、Mod 与测试源码检查为 0 个 WARNING/WEAK WARNING；`GenResources.kt` 批量超时后单文件复检为 0 个问题。
- [x] disposable Velocity worktree 中应用后的 Patch Java 文件，以及 `plugin/patch/test`，均因不在 IDEA project content roots 而无法由 IDEA 分析；使用 Velocity `compileJava`、`checkstyleMain` 和 `:plugin:patchCheck` 补充验证，均通过。
- [x] `:plugin:test` 通过：104 tests、0 failures、0 errors、0 skipped，`VelocityRuntimeSmokeTest` 实际执行 1 次。
- [x] `:plugin:tools:compileKotlin`、`:plugin:releaseJar` 与 `:mod:build` 通过；server 组只公开 `releaseJar` 与 `runServer`，`releaseJar --dry-run` 不包含 test/check/patchCheck。
- [x] 最终 Plugin JAR 中两条 Minecraft data 资源各 1 份，无旧资源别名、测试类、Velocity/MCProtocolLib/Netty/Guice/SLF4J/Lombok 类。
- [x] 固定 Velocity source 为 `843a47e2a38325309cd66133149fc9a984f76bb8`、状态 clean，disposable worktree 不存在。

## 追加：结构迁移执行顺序

本节追加当前结构决定。旧章节保留为实施记录，不重新执行已完成的行为实现。

本节的结构步骤由文末“最新结构重构执行计划”取代。本节只保留历史记录。

1. 使用 IDEA MCP symbol search 和 `mcp__idea__analyze_calls` 建立当前调用关系。
2. 使用 `mcp__idea_index__ide_refactor_rename` 把 `Aabb` 重命名为 `AABB`，让
   IDEA 更新所有解析到的引用。
3. 使用 `mcp__idea_index__ide_move_file` 将 `automation/World.java` 移到
   `world/World.java`，由 IDEA 更新包声明和 import。
4. 使用 `mcp__idea_index__ide_move_file` 将 `automation/Entity.java` 和
   `LivingEntity.java` 移到 `world/entity/`，由 IDEA 更新继承和调用引用。
5. 使用 `mcp__idea_index__ide_move_file` 将 `automation/Player.java` 移到
   `world/player/Player.java`，由 IDEA 更新包声明和调用引用。
6. 使用 `mcp__idea_index__ide_move_file` 将 `automation/ActionMode.java` 移到
   命令包，将 `ProtocolTarget.java` 移到协议包，并由 IDEA 更新引用。
7. 使用 `mcp__idea__analyze_calls` 定位 `AutomationService.lookAt(...)` 的唯一生产
   调用方 `FakePlayerProxyPlugin.onLookAt(...)`。
8. 当前启用的 IDEA MCP 不提供方法归属移动；直接把 lookAt 计算代码迁移到
   `world/player/Player.lookAt(...)`，修改 `onLookAt(...)` 调用 Player，并删除
   Service 旧方法。
9. 把 `AutomationService.position(...)` 的 relative 解析移动到
   `Player.applyServerPosition(...)`。保留 Service 的后端 ACK 和 PosRot 发送。
10. 把 `AutomationService.rotation(...)` 的 relative 解析移动到
    `Player.applyServerRotation(...)`。保留 Service 的后端回应。
11. 把 health、abilities、gameMode、clientPosition、clientRotation 和 clientStatus
    的调用改为直接写入 Player。
12. 把 Entity、Metadata、Attribute、Effect、Equipment、Passenger、Chunk、Block、
    Border、Tag、Registry 和 Ticking Packet 的调用改为直接写入 World 或 Entity。
13. 把 `PhysicsData` 的方块数据移动到 `world/Block`，把实体类型数据移动到
    `world/entity/EntityTypeData`。
14. 将二进制解析、固定 item ID、Known Pack 和 Dimension Type 数据移动到
    `world/MinecraftData`；该类只持有不可变固定数据。
15. 将 `EntityDefinition`、`PoseDefinition`、`MovementKind` 和 `PistonReaction` 合并
    到 `EntityTypeData`。将 `BlockBehaviorKind` 合并到 Block。
16. 将 `VehicleState` 合并到 Entity，将 `InputState` 合并到 Player。
17. 将 `MovingPiston` 和 `LevelChunkInstallResult` 合并到 World，删除
    `VanillaRegistryData`。
18. 将 Mod 的 `ConsentDecisionStore` 改为 `config/ConsentStore`，使用一个 boolean
    和 `consent_store.toml`。
19. 将宽范围 suppress 改为局部 suppress，并在保留位置前写原因注释。
20. 更新受影响测试的包名和调用入口，不增加测试类型或测试专用生产 API。
21. 用 IDEA MCP 检查受影响文件的问题列表，再按现有项目任务执行编译和测试。

符号重命名使用 `mcp__idea_index__ide_refactor_rename`。Java 类文件及其 package
位置迁移使用 `mcp__idea_index__ide_move_file(file, destination, project_path)`。
当前启用的 IDEA MCP 不提供方法归属移动。方法 owner 迁移通过代码编辑完成。
迁移前使用 `mcp__idea__analyze_calls`，迁移后使用 IDEA symbol 或 usage search
检查旧 owner、旧方法和旧调用不存在。

本节不修改 journal，不增加功能，不修改 Patch，不增加 Service、Handler、Manager 或
tick task。

## 追加：符号级结构迁移计划

本节补全上一节的具体 owner、调用方和结果路径。它不重新执行已完成的协议、
Shadow 或 Player 物理功能。

本节的待执行结构步骤由文末“最新结构重构执行计划”取代。已完成记录保持不变。

### 1. IDEA MCP 结构操作

1. 对 `automation/Aabb.java` 调用 `mcp__idea_index__ide_refactor_rename`，再调用
   `mcp__idea_index__ide_move_file` 将其移动到 `world/phys/AABB.java`。
2. 使用 `mcp__idea_index__ide_move_file` 将 `World.java` 移到 `world/`，将
   `Entity.java`、`LivingEntity.java` 移到 `world/entity/`，将 `Player.java`
   移到 `world/player/`。IDEA 更新 package、import、继承和测试引用。
3. 使用 `mcp__idea_index__ide_move_file` 将 `ActionMode.java` 移到 `command/`，
   将 `ProtocolTarget.java` 移到 `protocol/`。`ParsedPlayerCommand`、
   `PlayerCommandParser`、`AutomationManager`、`AutomationService`、`FppCommand`
   和 `FakePlayerProxyPlugin` 改用新 owner。

### 2. Plugin 路由和 Service 边界

1. `AutomationManager.get(Velocity Player)` 改为返回
   `world.player.Player`。`FakePlayerProxyPlugin.withService(...)` 改为
   `withPlayer(...)`，回调参数为 Plugin Player 和当前 backend connection。
2. `onPlayerPosition(...)` 先调用
   `Player.applyServerPosition(position, deltaMovement, yaw, pitch, relatives)`，
   再调用 `AutomationService.acknowledgePosition(backend, teleportId)`。Player
   解析 relative position、relative rotation 和 velocity；Service 只按 Shadow
   状态发送 Accept Teleportation 和 PosRot。
3. `onPlayerRotation(...)` 先调用
   `Player.applyServerRotation(yaw, relativeYaw, pitch, relativePitch)`，再调用
   `AutomationService.acknowledgeRotation(backend)`。Player 计算最终旋转；Service
   只发送后端 Rot 回应。
4. 当前启用的 IDEA MCP 不提供方法归属移动；直接把
   `AutomationService.lookAt(...)` 的计算代码迁移到 `Player.lookAt(...)`。
   `onLookAt(...)` 直接调用 Player，Service 删除该方法。
5. `onHealth`、`onAbilities`、Game Mode、`onExplosion` 和四个 Serverbound Move
   listener 直接调用 Player。`onMotion` 根据 entity ID 调用 Player 或
   `World.entity(id)`；这些路径不再经过 Service 转发。
6. Add/Remove/Move/Sync/Teleport/Vehicle/Metadata/Attribute/Effect/Equipment Packet
   直接调用 World 或目标 Entity。Passenger、Chunk、Block、Border、Registry、Tag、
   Ticking 和 Minecart Packet 直接调用 World。
7. KeepAlive、Ping、Configuration、Known Packs、Cookie、Chat、Player Loaded、
   Action scheduler、Shadow、关闭和 EventLoop tick 保留在 AutomationService。
   World 更新完成后，需要发送协议回应的 listener 再调用对应 Service 方法。

### 3. Minecraft 数据 owner

1. 将 `PhysicsData` 重命名并拆为 `world/MinecraftData.java`、
   `world/Block.java` 和 `world/entity/EntityTypeData.java`。
2. `MinecraftData` 一次解析 `minecraft-data/minecraft-data.bin`，持有 shape registry、
   Block registry、EntityTypeData registry、固定 item ID、Known Pack 和 Dimension
   Type 常量。它不包含物理计算或每个 Player 的可变状态。
3. `Block` 保存一个 block state 的 key、shape、friction、speed、bounce、fluid 和
   behavior 数据；`BlockBehaviorKind` 合并为 `Block.Behavior`。
4. `EntityTypeData` 保存 entity pose、attachment、metadata ID、attribute 默认值和
   movement flags；`EntityDefinition`、`PoseDefinition`、`MovementKind` 和
   `PistonReaction` 合并为该类的字段和嵌套 enum/record。
5. `VanillaRegistryData` 删除。补全 Dimension Type 的逻辑进入
   `MinecraftData.completeDimensionTypes(...)`；Known Pack 使用 MCProtocolLib
   `KnownPack` 常量，不保留独立 registry owner。

### 4. World、Entity、Player 和 phys 边界

1. `World` 保留 Chunk、Block Entity、Entity registry、Passenger、Border、Tick Rate
   和 Moving Piston 生命周期。`MovingPiston` 与 `LevelChunkInstallResult` 合并为
   World 内部类型，不再保留独立源文件。
2. `Entity` 保留 position、velocity、pose、metadata、passenger/vehicle 关系和载具
   timer/status；`VehicleState` 字段和 `BoatStatus` 合并到 Entity。
3. `LivingEntity` 保留 health、attribute、effect、equipment 和 Vanilla travel 顺序。
4. `Player` 保留 Velocity Player、AutomationService、Ability、Game Mode、输入、
   movement output baseline 和玩家计算；`InputState` 合并为 Player 内部 record。
5. `world/phys/AABB` 只保存几何和相交/扩张操作。
   `CollisionPhysics` 保存轴向裁剪、台阶候选和 push 几何；`FluidPhysics` 保存水/岩浆
   累计、流向和浮力公式；`VehiclePhysics` 保存 Boat 和其他零输入载具公式；
   `PistonPhysics` 保存 swept AABB、穿透量、轴向限制、Honey 和 Slime 位移公式。
   这些类不持有 World、Entity、connection、Packet 或 tick 状态。

### 5. Consent、warning 和注释

1. 使用 `mcp__idea_index__ide_refactor_rename` 将 `ConsentDecisionStore` 重命名为
   `ConsentStore`，再使用 `mcp__idea_index__ide_move_file` 将源文件从
   `mod/consent/` 移到 `mod/config/`。
   删除 `Decision` enum；内存值改为 `Map<String, Boolean>`，API 固定为
   `Optional<Boolean> find(String serverAddress)` 和
   `void remember(String serverAddress, boolean allow)`。
2. 存储文件改为 Fabric config 目录下的
   `fakeplayerproxy/consent_store.toml`。每行使用 TOML quoted key 保存
   `"server-address" = true|false`。读取和写入使用 JDK Files；保留当前临时文件替换
   行为，删除 Gson、JSON 和 `Objects.requireNonNull`。
3. Mixin 读取 boolean 后直接选择 Allow/Decline；只有用户勾选
   `Do not notify me again for this server` 时调用 `remember(...)`。未保存地址继续显示
   Consent，初始焦点仍是 Decline。
4. 删除 `AutomationManager`、`AutomationService` 和 `FakePlayerProxyPlugin` 的
   方法级 `@SuppressWarnings("resource")`。每个 Velocity-owned connection 或
   EventLoop 表达式前使用局部 `//noinspection resource`，并写明 Velocity 拥有生命周期。
   `Entity.isDescending()` 改为正向语义方法，使调用方不再需要
   `BooleanMethodIsAlwaysInverted` suppress。
5. `AutomationManager`、`AutomationService`、`World`、`Entity`、`LivingEntity`、
   `Player` 和 `MinecraftData` 的类注释说明 owner、输入和生命周期。relative
   position、chunk 原子安装、fluid 累计、vehicle tick 和 piston movement 的注释说明
   Vanilla 顺序和失败边界；简单 getter、字段赋值和明显分支不增加叙述注释。

### 6. 收口检查

- [x] 使用 IDEA MCP `search_symbol` 确认旧 package、`Aabb`、`PhysicsData`、
  `ConsentDecisionStore`、独立 `VehicleState`、独立 `InputState`、独立
  `MovingPiston` 和 `VanillaRegistryData` 不再存在；`analyze_calls` 已确认
  Position、LookAt、Entity transform 和 Block update Packet 直接进入
  Player、Entity 和 World。
- [x] 使用 IDEA MCP 检查全部 22 个受影响源码和测试文件；WARNING 和 WEAK WARNING
  均为 0。
- [x] 已用 IDEA move/rename 更新现有测试的 package、类型名和调用入口；未增加测试
  类型、测试专用生产 API 或源码文本断言。
- [x] `:plugin:test`（104 tests）和 `:mod:test`（5 tests）均为 0 failure/error；
  单独运行的 `:plugin:releaseJar` 通过。未修改 Patch，未启动 Minecraft、Paper、
  Velocity 或玩家视角实测。

## 最新结构重构执行计划

本节执行最新 owner 决定。它不重做已完成的协议、物理或 Mod 功能。

### 1. Manager、Service 和 Player

1. 保留 AutomationManager 的 Logger、注册 Map、Fresh Login、tick 生命周期、失活
   移除和 shutdown。
2. 将 frontend、backend 和 EventLoop helper 从 Manager 移到 Plugin Player。
3. 将 Manager 的主动动作入口迁移到 AutomationService。
4. 将实际输入修改和动作 Packet 迁移到 Plugin Player。
5. 更新调用方后，不再保留 Manager 动作转发、`run(...)` 和 `actionName`。
6. 保留 AutomationService 的协议、Shadow、被动职责调度和主动动作计划。
7. 让主动动作在在线和 Shadow 状态使用同一调度路径。
8. 只在 Shadow 状态调用 Player 的被动物理和移动输出。

### 2. Shadow

1. 将 `/player shadow` 调用改为
   `PlayerCommand -> Manager.get -> Player.automationService().shadow()`。
2. 在 `AutomationService.shadow()` 内切换到 owner EventLoop。
3. 在同一方法内校验 Service、backend 和 Shadow 状态。
4. 在同一方法内设置 `shadow=true` 并调用 Velocity Player `disconnect(...)`。
5. 不清除动作计划、输入或玩家状态。
6. 不增加 `requestShadow()`、`enterShadow()` 或其他 Shadow helper。

### 3. PlayerCommand

1. 将旧 `PlayerCommand` 的 `SimpleCommand` 入口合并到 `PlayerCommandHandler`。
2. 将 Parser 的 Shadow 解析和建议合并到同一类。
3. 使用 `mcp__idea_index__ide_refactor_rename` 将 `PlayerCommandHandler` 重命名为
   `PlayerCommand`。
4. 让新 `PlayerCommand` 只接受无额外参数的 `shadow`。
5. 合并调用后，不再保留 PlayerCommandParser、PlayerCommandKind 和
   ParsedPlayerCommand。
6. 使用 `mcp__idea_index__ide_move_file` 将 ActionMode 移到 `automation/`。
7. Plugin 直接注册新的 PlayerCommand。
8. 除 IDEA rename 产生的类型引用更新外，不修改 FppCommand。

### 4. Fixed data and World

1. 使用 `mcp__idea_index__ide_refactor_rename` 将 MinecraftData 重命名为 Decoder。
2. 使用 `mcp__idea_index__ide_move_file` 将 Decoder、Block 和 EntityTypeData 移到
   `world/data/`。
3. 使用同一工具将 World 移到 `world/world/`。
4. 让 Decoder 只解析和查询固定 Block、Shape、Entity Type、item ID 和 Dimension
   Type 数据。
5. 将 Known Pack 逻辑迁移到 AutomationService。
6. 让 World 只保存每个 Player 的运行时世界状态，并通过 Decoder 查询固定类型。
7. 不增加 Registry 类型或固定数据副本。

### 5. Entity and Vehicle

1. 在 `world/entity/Vehicle.java` 建立载具运行时 owner。
2. 将 Entity 的 Boat、boost、horse、strider、camel 和 happy ghast 字段迁移到 Vehicle。
3. 将载具 metadata、控制权、tick 和座位计算迁移到 Vehicle。
4. 保留 VehiclePhysics 的无状态公式，并由 Vehicle 调用。
5. 让 Entity 只保存通用状态和一个可空 Vehicle 组件。
6. 将载具固定数据迁移到 EntityTypeData 的嵌套 Vehicle data。
7. 将 PistonReaction 重构为 `affectedByPiston` boolean。
8. 收窄 `Entity.definition()`，并把外部判断改为 Entity 语义操作。

### 6. Existing tests and validation

1. 将现有 Handler 和 Shadow Parser 用例合并到 PlayerCommandTest。
2. 更新现有 Manager、Service、Player、World、Entity 和固定数据测试的调用入口。
3. 不增加测试类型、测试专用生产 API 或源码文本断言。
4. 使用 IDEA usage 和 call hierarchy 检查旧 owner 和旧调用路径。
5. 修复受影响文件的 WARNING 和 WEAK WARNING。
6. 运行现有 `:plugin:test` 和 `:mod:test`。
7. 单独运行现有 `:plugin:releaseJar`。
8. 不修改 Patch，不启动 Minecraft、Paper、Velocity 或玩家视角实测。

## 最新结构重构执行结果（2026-08-16）

- [x] AutomationManager 仅保留 Logger、注册/查询/替换/移除、Fresh Login、tick、
  backend 失活清理和 shutdown 生命周期；调用方迁移后已移除动作转发、`run(...)`
  和 `actionName`。
- [x] Plugin Player 持有实际玩家状态、World、backend/frontend/EventLoop、主动动作
  Packet 和 Shadow 被动移动输出；AutomationService 持有动作计划、协议和 Shadow。
- [x] Shadow 入口为
  `PlayerCommand -> AutomationManager.get -> Player.automationService() -> AutomationService.shadow()`；
  未增加 Shadow helper，且只对被动客户端职责做 Shadow gate。
- [x] PlayerCommand 已合并为只支持精确 `shadow` 的单一 SimpleCommand；ActionMode 已移到
  automation。FppCommand 仅保留语义重命名产生的类型引用更新和 owner 移除后编译所必需的
  `closeBackend`、`look-north` 调用迁移。
- [x] 最终目录为 `world/data/{Block,Decoder,EntityTypeData}`、`world/world/World`、
  `world/entity/{Entity,LivingEntity,Vehicle}`、`world/player/Player` 和既有五个
  `world/phys` 类型；Known Pack 逻辑位于 AutomationService，未增加 Registry 或数据副本。
- [x] Vehicle 持有载具运行时状态，EntityTypeData.VehicleData 持有载具固定数据；
  PistonReaction 已替换为 `affectedByPiston` boolean，外部不再读取 Entity definition。
- [x] IDEA 受影响源码诊断为 0 WARNING；World 使用分段诊断避免整文件超时。关闭文件的
  batch provider 不提供完整 WEAK WARNING 覆盖，其返回的可用诊断为 0。
- [x] `:plugin:test :mod:test` 通过：Plugin 93 tests、Mod 5 tests，均为 0
  failure/error；单独 `:plugin:releaseJar` 通过。
- [x] 固定 Velocity source HEAD 为 `843a47e2a38325309cd66133149fc9a984f76bb8` 且
  `git status --short` 为空；一次性 `plugin/build/server/work` 不存在。
- [x] 未修改 Patch、journal 或 `.codex`，未启动 Minecraft、Paper、Velocity、client，
  未执行手动连接测试。

## 最终冗余收口（2026-08-16）

- [x] 删除 `DimensionType` 和 `PistonMove` 两个简单值 record，改用项目已有的
  FastUtil `Pair`；活塞 destination 由 source 和 direction 现场推导。
- [x] `EntityTypeData` 只保留一个 `MovementKind` 判别字段；VehicleData 不再重复保存
  movement kind，Entity 不再提供一次性的创建 factory。
- [x] 删除 World 的测试专用 `installChunk()` 和 `sectionCount()`；现有测试通过真实
  `decodeAndInstallChunk()` 入口安装区块。
- [x] 删除只有 GAME/CONFIGURATION 两个值的 `ProtocolState`，改用 `inGame` boolean。
- [x] 本任务新增和迁移代码中的纯字段 getter/setter 已改用 Lombok；带校验、转换、
  副作用、派生语义或 override 的方法保持显式实现。该约束已写入 Java language spec。
- [x] `:plugin:test` 在最终修改后通过；受影响文件的 IDEA WARNING/WEAK WARNING 为 0，
  残留旧符号扫描和 `git diff --check` 通过。

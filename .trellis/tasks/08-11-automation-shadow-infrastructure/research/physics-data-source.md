# Research: Minecraft 26.2 Shadow 物理静态数据来源

## 当前任务范围

初版资源只包含方块碰撞 Shape、地面摩擦和水状态。

玩家 Pose 尺寸和眼高属于固定 Player 物理数据。

实体状态继续来自 Packet。

初版 Player 物理不使用实体尺寸。

本报告后文提到的速度因子和特殊方块字段不进入当前实施范围。

- Query: Minecraft 26.2 Shadow 物理应从哪里取得 Block State、碰撞形状、方块物理属性和实体尺寸数据。
- Scope: mixed
- Date: 2026-08-13

## Findings

### 结论

Plugin 需要一份项目内的 Minecraft 26.2 物理数据文件。构建工具从固定 Minecraft 26.2 环境生成该文件。

Plugin 运行时只读取生成结果。Plugin 运行时不加载 Minecraft JAR、Mappings、Fabric、Prismarine 或 Minestom。

生成结果必须固定以下身份：

- Minecraft 版本 `26.2`
- 协议版本 `776`
- Data Version `4903`
- 生成器提交
- 数据格式版本
- 生成结果 SHA-256

使用 `minecraft-data-generator` 的 26.2 分支作为生成器起点。该项目直接读取 Minecraft 注册表和碰撞形状。

许可结论已经由用户确认：本项目以该仓库根目录 `LICENSE` 的 MIT 文本为准，
可以生成并分发固定 Minecraft 26.2 的紧凑物理数据，且必须保留该 MIT 声明。
固定生成器提交为 `ae2fa6729d147d98638c828c537649fc9bcb116c`。

该提交的根 `package.json` 同时把 `license` 元数据写为 `ISC`，与根 `LICENSE`
存在冲突。此冲突已记录，但用户已经明确决定本项目采用根 `LICENSE` 的 MIT 条款；
因此它不再是实现门禁，也不能被表述为未解决的许可结论。

不要直接采用 `minecraft-data` 当前 master 数据。它在 2026-08-13 尚未提供已合并的 26.2 数据集。

不要把完整 Minestom 加入 Plugin。Minestom 26.2 提供更丰富的数据和碰撞代码，但它会扩大依赖面。

### MCProtocolLib

当前 Plugin 固定以下依赖：

- `org.geysermc.mcprotocollib:protocol:26.2-20260809.160751-16`
- Minecraft `26.2`
- 协议 `776`

`plugin/build.gradle.kts:12-34` 固定 MCProtocolLib 和 Netty 版本。

`plugin/src/main/java/com/fakeplayerproxy/protocol/McProtocolLibUpstreamClient.java` 使用 MCProtocolLib Packet。

MCProtocolLib 提供以下相关类型：

- Block State ID 的区块 Palette
- `EntityType`
- Entity Metadata
- 属性和效果
- 玩家和实体位置 Packet

MCProtocolLib 不提供以下静态数据：

- Block State ID 到 Block State 的完整映射
- Block State ID 到碰撞形状的映射
- 方块摩擦和速度因子
- 流体形状和流体行为
- Entity Type 到实体尺寸的完整运行时表
- 玩家碰撞引擎

MCProtocolLib 仓库只找到计分板的 `CollisionRule`。该类型和物理碰撞无关。

因此，MCProtocolLib 继续负责 Packet。Plugin 物理必须使用独立静态数据。

### Prismarine minecraft-data

`minecraft-data` 是语言无关的 JSON 数据集。它的 README 声明 MIT License。

26.1 数据包含以下可用文件：

- `blocks.json`
- `blockCollisionShapes.json`
- `entities.json`

`blocks.json` 提供以下字段：

- Block ID
- Block 名称
- `defaultState`
- `minStateId`
- `maxStateId`
- State 属性
- 基础 `boundingBox`

`blockCollisionShapes.json` 提供两层映射：

- Block 名称到各 State 的形状编号
- 形状编号到一个或多个 AABB

`entities.json` 提供以下字段：

- Entity Type ID
- 宽度
- 高度
- 类型分类
- Metadata Key 列表

`prismarine-block` 使用这些文件。它按 State ID 选择 `stateShapes`。

`prismarine-block` 的 `package.json` 声明 MIT License。它是 JavaScript 库，不能直接作为 Java Plugin 依赖。

只复制 Prismarine 数据仍需保留其许可通知。`minecraft-data` README 还指出部分数据来自外部 Wiki。

这会产生额外的数据来源许可审查。项目应记录数据提交和许可文本。

### Prismarine 26.2 覆盖

Prismarine 的协议版本列表已经包含 Minecraft 26.2：

- 协议版本 `776`
- Data Version `4903`

但是，master 的 `dataPaths.json` 尚无 `pc.26.2` 数据路径。

开放 PR `PrismarineJS/minecraft-data#1219` 添加 26.2。其分支提交为 `4dd8762a45b97dafdb216b8d7a95ab92379e2c68`。

该分支的 26.2 路径仍复用 26.1 的以下数据：

- Block
- 碰撞形状
- Entity
- 属性
- 效果
- 物品

该分支只添加 26.2 的协议和版本文件。PR 在 2026-08-13 仍为 open 和 unstable。

因此，该分支不能证明 26.1 和 26.2 的物理数据相同。初版不能把它作为权威数据。

### Prismarine 生成器

`PrismarineJS/minecraft-data-generator` 使用 Fabric Mod 和 Minecraft 类生成 JSON。
其根目录 `LICENSE` 是 MIT 文本；根 `package.json` 的 `license: ISC` 是已记录的
元数据冲突。本项目按用户决定采用根 `LICENSE` MIT。

开放 PR `PrismarineJS/minecraft-data-generator#77` 添加 26.2。其提交为 `ae2fa6729d147d98638c828c537649fc9bcb116c`。

该 26.2 模块执行以下工作：

- 使用 Minecraft `26.2`
- 使用 Official Mappings
- 使用 Java 25
- 遍历 `Registries.BLOCK`
- 调用每个 `BlockState.getCollisionShape(...)`
- 导出每个 `VoxelShape` 的 AABB
- 导出 Block State ID 范围和属性
- 遍历 `Registries.ENTITY_TYPE`
- 导出 Entity Type ID、宽度和高度

该实现直接从固定 Minecraft 版本取得数据。它比复用 26.1 JSON 更可靠。

构建工具可以复用该生成器。构建工具也可以移植其少量导出逻辑。

### Minestom

Minestom 当前版本明确支持 Minecraft 26.2。最新发布为 `2026.08.07-26.2`。

Minestom 使用 Apache-2.0 License。其数据生成器使用 MIT License。

Maven Central 提供以下数据包：

- `net.minestom:data:26.2-rv3`
- SHA-256 `b97177d7e028f2c5e71f2f8febd898396c5c702d127c8ecf691549a31eefa9c0`

该数据包使用 Java 25 元数据。当前 Plugin 使用 Java 21。

数据包公开 Entity Type 尺寸、眼高、附件和默认属性。它不单独公开 Block State 数据文件。

完整 Minestom 加载 Block State 和碰撞形状。其 Block 数据包含以下字段：

- State ID
- State 属性
- 摩擦
- 速度因子
- 流体标志
- 碰撞形状
- 遮挡形状

Minestom 也提供 Java 碰撞算法。但是，Minestom 是完整服务端框架。

将完整 Minestom 放入 Velocity Plugin 会引入以下成本：

- Java 25 要求
- Adventure 5
- Fastutil
- Flare
- JCTools
- Minestom 注册表初始化
- 与 Velocity 和 MCProtocolLib 的重复类型

因此，完整 Minestom 不适合作为初版 Plugin 依赖。

Minestom 数据可作为 26.2 交叉校验源。它也可补充 Prismarine 生成器缺少的方块物理属性。

### 其他 Java 数据源

Geyser 包含 Java Block 碰撞注册表和特殊方块修正。Geyser 使用 MIT License。

Geyser 的碰撞模型服务于 Java 和 Bedrock 转换。它不是独立的 Java 玩家物理数据库。

ViaVersion 主要提供协议版本映射。它没有完整的现代 Java Block 碰撞形状数据库。

没有找到一个小型 Java 21 库，同时提供以下能力：

- Minecraft 26.2 Block State ID
- 完整 Block 碰撞形状
- 方块摩擦和流体属性
- Entity Type 尺寸
- 可直接配合 MCProtocolLib

### 最小可实施方案

构建工具生成一个紧凑的 `physics-data-26.2` 资源。Plugin 将该资源打入自己的 JAR。

构建工具使用固定的 Minecraft 26.2 客户端或合并 JAR。它使用 Official Mappings 调用 Minecraft 注册表。

构建工具不属于 Velocity Patch。Patch 不包含 Minecraft 物理数据或数据生成逻辑。

当前 Plugin 运行时只处理以下映射：

1. `blockStateId -> blockPhysics`
2. `shapeId -> AABB[]`

`blockPhysics` 只存储以下字段：

- Block State ID
- Block ID 或 Block 名称
- 碰撞 Shape ID
- 摩擦
- 是否为空气
- 是否含水
- 水 Level
- 水是否下落

`shapeId` 指向一组本地方块坐标 AABB。AABB 使用六个固定精度数值。

Plugin 不需要以下生成数据：

- 显示名
- 翻译
- 纹理
- 光照
- 掉落物
- 合成表
- Loot Table
- Entity AI

生成器应合并相同形状。生成器应按 Block State ID 输出连续表。

生成器应在文件头写入版本身份和 SHA-256。Plugin 启动时校验版本身份。

### 构建和运行边界

数据生成是独立的维护任务。普通 Plugin 构建不应下载或启动 Minecraft。

维护者只在 Minecraft 目标版本变化时运行生成器。生成器输出经过审查后进入仓库。

普通 Plugin 构建只读取仓库内的生成资源。CI 不需要接受 EULA 或启动 Minecraft。

运行时没有以下依赖：

- Minecraft JAR
- Official Mappings
- Fabric Loader
- Prismarine Node.js 模块
- Minestom Server

这条边界保持 Plugin 可在 Java 21 的固定 Velocity 中运行。

### 许可边界

Minecraft JAR 和 Mappings 只用于构建期检查和数据生成。不要把 JAR、Mappings 或反编译源码打入 Plugin。

生成工具代码使用其原许可证。项目必须保留所复制生成器代码的许可证通知。

用户已完成本任务所需的项目分发决定：允许提交并分发固定 26.2 紧凑生成结果，
同时保留生成器根 `LICENSE` 的 MIT 声明。该决定仅覆盖本任务采用的生成结果和归属落点。

Prismarine JSON 复用需要保留 MIT 通知。外部 Wiki 来源可能增加署名要求。

Minestom 数据复用需要保留其数据包许可证和生成器许可证通知。POM 声明 Apache-2.0，但生成器仓库 README 声明 MIT。

最小风险方案是使用自有导出格式。项目只分发物理所需的数值表。

### 校验方法

生成器应对以下项目做确定性校验：

- 最大 Block State ID
- Block State 总数
- Shape 总数
- Air 的空形状
- Stone 的整方块形状
- Slab、Stairs、Fence 和 Door 的多状态形状
- 静水和流动水的状态
- Ice 的摩擦
- Player 的 Pose 尺寸和眼高

实现前应把生成结果与 `net.minestom:data:26.2-rv3` 做一次差异检查。

差异检查只验证数据生成。它不要求 Plugin 依赖 Minestom。

### 最终生成结果

固定提交 `ae2fa6729d147d98638c828c537649fc9bcb116c` 的 26.2 Unimined Server
生成流程已成功完成。仓库保存生成器增量
`plugin/tools/minecraft-data-generator-26.2.patch`，为每个 Block State 增加摩擦、
空气、水量和 falling 水状态；紧凑导出器位于
`plugin/tools/compact-physics-data.mjs`。

提交资源包含 32,366 个连续 Block State 和 6,114 个碰撞 Shape，二进制大小
748,472 bytes，SHA-256 为
`dd6ada7b86e1851288acbd71cdfff51a6f38948e5f9b0acc4b78bc4f1f98e50f`。
资源 properties 还记录原始 blocks、collision shapes、生成器增量和最终资源的
SHA-256。根 MIT 文本保存在
`plugin/src/main/resources/META-INF/licenses/minecraft-data-generator-LICENSE.txt`。

## Files Found

- `plugin/build.gradle.kts:12-34`：当前待更新的 MCProtocolLib 26.2 和 Java 17 配置。
- `plugin/src/main/java/com/fakeplayerproxy/protocol/McProtocolLibUpstreamClient.java`：当前 MCProtocolLib Packet 使用方式。
- `.trellis/spec/backend/velocity-plugin.md`：Plugin、Patch、Minecraft 26.2 和协议版本边界。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/mcprotocollib-world-packets.md:142-182`：区块解码和静态 Block State 数据缺口。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/vanilla-damage-physics.md:393-405`：26.2 物理和 MCProtocolLib 能力边界。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/bot-physics-world.md:341-390`：现有 Java 物理库比较。

## External References

- [MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib)
- [minecraft-data](https://github.com/PrismarineJS/minecraft-data)
- [minecraft-data 26.2 PR](https://github.com/PrismarineJS/minecraft-data/pull/1219)
- [minecraft-data 26.2 branch](https://github.com/PrismarineJS/minecraft-data/tree/pc_26_2)
- [prismarine-block](https://github.com/PrismarineJS/prismarine-block)
- [minecraft-data-generator](https://github.com/PrismarineJS/minecraft-data-generator)
- [minecraft-data-generator 26.2 PR](https://github.com/PrismarineJS/minecraft-data-generator/pull/77)
- [Minestom 26.2](https://github.com/Minestom/Minestom/releases/tag/2026.08.07-26.2)
- [Minestom Data 26.2-rv3](https://repo1.maven.org/maven2/net/minestom/data/26.2-rv3/)
- [Geyser collision package](https://github.com/GeyserMC/Geyser/tree/master/core/src/main/java/org/geysermc/geyser/translator/collision)

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`
- `.trellis/spec/language/java.md`

## Caveats / Not Found

- Prismarine 26.2 数据 PR 尚未合并。其物理数据仍指向 26.1。
- Prismarine 26.2 生成器 PR 尚未通过合并门禁。
- Minestom Data 的 Block State 资源由其加载器隐藏。单独数据 JAR 不提供可直接复制的 `block.json`。
- Minestom 主库要求 Java 25。当前 Plugin 目标是 Java 21。
- 没有找到可直接配合 MCProtocolLib 26.2 的成熟 Java 21 物理数据依赖。
- Minecraft EULA 页面在本次命令行抓取中没有返回可引用正文；本任务不再把它作为
  已由用户批准的紧凑物理生成结果的实施门禁。
- 方块内部效果包含特殊行为。单一静态标志不能完整表示 Web、Powder Snow、Berry Bush 和 Portal。
- Entity 的 Pose 和 Scale 会动态改变碰撞箱。静态 Entity Type 尺寸只是基础值。

# Research: ServerWorld 共享的量化成本收益

- Query: 量化 MCProtocolLib build 16 Chunk、View Distance、玩家重叠、现有代码和 ServerWorld 共享方案的成本收益，并给出 break-even。
- Scope: mixed（本地代码、Vanilla 26.2、本地合成对象图与微基准）
- Date: 2026-08-14

## Findings

### 口径

- 运行目标仍为 Java 21、Minecraft Java 26.2、协议 776、`mcprotocollib:26.2-20260809.160751-16`。
- Chunk 对象图使用 JOL `GraphLayout` 测量 `ChunkSection[24]`。24 个 Section 对应普通 384 格高度 World。
- 合成 Chunk 的 Biome 保持 Singleton Palette，只改变 Block Palette。该口径只测 `ChunkSection[]` 及其可达对象，不包含 `HashMap`、Entity、光照、Heightmap、Block Entity 或 Packet 原始 `byte[]`。
- “重叠率 `r`”固定表示：第一个 Player 有 `C` 个 Chunk；每个后续 Player 有 `r*C` 个 Chunk 已存在于共享集合。因此 `U = C * (1 + (P - 1) * (1 - r))`。

### MCProtocolLib ChunkSection[] 堆占用

JDK 21、64-bit HotSpot、压缩引用、i7-9750H 上的合成结果：

| 场景 | Block Palette | 24-Section Chunk |
| --- | --- | ---: |
| 全空 | 24 个 Singleton Palette | 3.29 KiB |
| 低复杂度 | 4 个非空 Section，每个 16 states | 16.32 KiB |
| 代表场景 | 8 个非空 Section，每个 32 states | 36.60 KiB |
| 全低 Palette | 24 个非空 Section，每个 16 states | 81.48 KiB |
| 较复杂 Palette | 24 个非空 Section，每个 64 states | 129.85 KiB |
| 全局 Palette | 24 个非空 Section，每个 512 states | 196.98 KiB |

`512 states` 已进入 15-bit Global Palette。真实 Chunk 占用取决于非空 Section 数和 Palette；不能用单一常数声称所有 Chunk 都是 36.60 KiB。

辅助复现使用当前 shell 的 JDK 26 得到相同数量级：全空 3.11 KiB、24 个 2-state Section 54.86 KiB、24 个 512-state Section 196.80 KiB。JDK 版本和合成 Palette 不同，因此决策表统一使用上面的 JDK 21 数据。

### View Distance 的实际 Chunk 数

Vanilla 26.2 `ChunkTrackingView` 不是简单的 `(2d+1)^2` 正方形。按其距离判定枚举得到：

| View Distance | Chunk 数 `C` | 代表场景每 Player | 全空每 Player | 全局 Palette 每 Player |
| ---: | ---: | ---: | ---: | ---: |
| 6 | 213 | 7.61 MiB | 0.68 MiB | 40.98 MiB |
| 8 | 329 | 11.76 MiB | 1.06 MiB | 63.29 MiB |
| 10 | 473 | 16.91 MiB | 1.52 MiB | 91.00 MiB |
| 12 | 637 | 22.77 MiB | 2.05 MiB | 122.55 MiB |

这些是 Chunk Section 图的容量估算，不表示登录瞬间一定已经收到全部 Chunk。

### Player-owned 与 ServerWorld 共享

以下使用 `C=473`、`M=36.60 KiB`。Player-owned 为 `P*C*M`；ServerWorld 为 `U*M`。表中节省量暂不含集合 bookkeeping：

| Player 数 `P` | Player-owned | 50% 重叠：共享 / 节省 | 75% 重叠：共享 / 节省 |
| ---: | ---: | ---: | ---: |
| 1 | 16.91 MiB | 16.91 / 0 MiB | 16.91 / 0 MiB |
| 2 | 33.81 MiB | 25.36 / 8.45 MiB | 21.13 / 12.68 MiB |
| 5 | 84.54 MiB | 50.72 / 33.82 MiB | 33.81 / 50.72 MiB |
| 10 | 169.08 MiB | 92.99 / 76.09 MiB | 54.95 / 114.13 MiB |
| 20 | 338.16 MiB | 177.56 / 160.60 MiB | 97.21 / 240.95 MiB |

JOL 合成 `HashMap`/`HashSet` bookkeeping 模型中，`P=10`、`C=473`、100% 重叠时：

- Player-owned Chunk Map：306,560 B。
- `chunks + holders + playerChunks + source`：568,760 B。
- 共享方案额外约 256 KiB bookkeeping，而 Chunk Section 图节省约 152 MiB。

该模型使用普通 JDK 集合和 `Long` boxing。它不是尚未实现的类的精确布局，但表明 bookkeeping 比重叠 Chunk 图小两个数量级。零重叠时共享不节省 Chunk 图，还会因 `ChunkEntry`、双向 Set 和 source 增加内存。

### 堆内存 break-even

- 代表场景 36.60 KiB/Chunk：普通 JDK 集合模型约在 1% 至 2% 重叠后抵消共享 bookkeeping。
- 全空 3.29 KiB/Chunk：约需 8% 至 16% 重叠，Player 越少越接近上界。
- 复杂 Chunk 的 break-even 低于 1%。
- 这是堆内存 break-even，不是工程复杂度 break-even。

### 当前生产代码成本

| 类型 | 物理行 | 非空 LOC | 字段 | 与 World 相关的现状 |
| --- | ---: | ---: | ---: | --- |
| `WorldState` | 164 | 143 | 7 instance + 2 static | 16 methods、约 11 个控制分支；一个 Player 独占 Registry、Dimension metadata 和 Chunk Map |
| `AutomationService` | 732 | 653 | 32 instance + 1 static | 自己创建 `WorldState`，并在 Login、Respawn、Configuration、7 类 World Packet 和 physics tick 中直接调用 |

证据：

- `WorldState.java:23-30`：Registry Map、Chunk Map、Dimension、minimumY、height、biomeRegistrySize、load-start flag。
- `WorldState.java:71-96`：完整解码后安装 Chunk。
- `WorldState.java:98-135`：Forget、Block/Section Update 和 Block 查询。
- `AutomationService.java:56-89`：当前 32 个 Player/协议/Automation/Entity/World 字段。
- `AutomationService.java:126-158`：Login、Respawn 和 Configuration 重置 World。
- `AutomationService.java:363-384`：World Packet 转发入口。
- `AutomationService.java:540-549`：Player Loaded 和 physics 读取 World。
- `FakePlayerProxyPlugin.java:204-285`：Registry、Login/Respawn、Load Start、Full/Forget Chunk、Block/Section Update 的 Event 入口。

### 最小共享方案的代码增量

不要为该方案增加独立 `ServerWorldKey` 或 World Executor。`BackendGeneration` 内按 `worldName` 建 Map 已经表达 key。

相对当前代码的最小类型变化：

1. `WorldState` 改为共享 owner `ServerWorld`，不是并存两个 World 类型。
2. 新增 `BackendGeneration`，保存 `RegisteredServer` identity、本地 generation、attached Player 和 `Map<worldName, ServerWorld>`。
3. 新增 `ChunkEntry`，保存 `ChunkSection[]`、holders 和 source。
4. Plugin `Player` 属于已讨论的 Player 分层基线；共享 World 只额外要求它保存当前 `BackendGeneration` 和 `ServerWorld` 两个引用。

因此，共享本身是“2 个净新增类型 + 1 个现有类型职责替换”，不是额外建立一套 World 层。

预计状态增量：

- `BackendGeneration`：约 4 个字段。
- `ServerWorld`：在现有 World metadata 上增加 lock、`Map<ChunkPos, ChunkEntry>` 和 `Map<Player, Set<ChunkPos>>`，约净增 3 个 owner 字段。
- `ChunkEntry`：3 个字段（sections、holders、source）。
- Player：2 个共享 World 引用。

预计必要分支约 10 至 12 个：generation 首个/最后一个 Player，World 首次选择，Full Chunk 新建/重复，Player membership 重复，Forget 缺失/最后 holder，source 离开，非-source Update，未知 Chunk，Dimension/Backend detach。它们来自幂等和跨 EventLoop 正确性，不能用裸 `int refCount` 删除。

同步点保持为一个 owner：每个 `ServerWorld` 一个 `ReentrantReadWriteLock`。写锁覆盖 Load commit、Forget/detach、Block Update、Section Update；physics 的一次完整查询持有读锁。generation Map 只需由 `AutomationManager` 的既有生命周期入口管理，不新增 World Executor。

必要验证场景只保留 6 组：重复 Full/Forget 幂等；两个 Player 的 holder 卸载；后续 Full 不覆盖现有快照；只接收 source Update 及 source 切换；Dimension/Backend generation 隔离；并发读与 detach 不出现半更新。此处只记录实施期验证，不新增 research test 或 Patch test。

### CPU 收益和锁成本

JDK 21 合成微基准中，MCProtocolLib 解码一个 24-Section Chunk 约 0.03 至 0.09 ms；`C=473` 时约 14 至 43 ms/Player，而且只发生在 Chunk 加载期。

共享内存不自动带来解码 CPU 收益。当前最简单的正确流程是锁外解码，再在写锁内尝试安装；重复 Player 的 Packet 仍会完成解码。要跳过重复解码，必须先在锁内建立 LOADING 占位并处理失败、重试和并发首包，额外增加状态和分支。即使全部省掉，5 个 Player、75% 重叠也只节省约 1,419 次解码，即约 43 至 128 ms 的一次性加载成本。初版不值得为此增加 LOADING 状态。

当前 shell JDK 26 的辅助微基准测得无竞争 `ReentrantReadWriteLock` acquire/release 约 19 至 23 ns。它只说明裸锁数量级；真实风险是跨 EventLoop 写等待和 physics 读锁持有时间，不能从该数字推出尾延迟。必须把 Chunk 解码放在锁外，并且不在锁内发送 Packet。

共享方案不会减少网络 Packet、Event 分发或每 Player physics tick。source 过滤只减少重复 Block mutation，收益预计小于 Chunk 内存收益。

### 结论：值不值

- `P=1` 或玩家基本不重叠：不值。共享结构只增加约束和 bookkeeping。
- `P=2`、50% 重叠：约省 8.45 MiB；堆上已获益，但不足以单独证明架构复杂度。
- `P>=5`、View Distance 10、重叠至少 50%：约省 33.82 MiB 起；若这是目标运行形态，共享 ServerWorld 值得。
- `P>=10` 且聚集：节省 76 MiB 至 114 MiB，收益明确高于约数百 KiB bookkeeping。

接受“Backend Server 持有 World”的既定前提后，推荐实现最小 ServerWorld 共享，但只以减少重叠 Chunk 内存为目标。初版保留锁外完整解码，不增加 LOADING 占位、内容哈希、不可变快照、World Executor 或共享 Entity。Entity 仍由 Player 持有。

## Files Found

- `plugin/src/main/java/com/fakeplayerproxy/automation/WorldState.java` - 当前每 Player 的 Registry、Dimension 和 Chunk owner。
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java` - 当前 Player、协议、Automation、Entity 和 World 聚合状态。
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java` - 当前 Player 到 AutomationService 的生命周期和 EventLoop owner。
- `plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java` - MCProtocolLib Packet Event 到 service 的入口。
- `plugin/build.gradle.kts` - Java/MCProtocolLib build 约束。
- `research/player-world-ownership.md` - 已决定的 BackendGeneration、holder/source、ServerWorld lock 和 Entity per-Player 规则。

## External References

- MCProtocolLib `26.2-20260809.160751-16`：`ChunkSection`、`DataPalette`、`BitStorage`、`MinecraftTypes.readChunkSection`。
- OpenJDK JOL `0.17`：`GraphLayout` 合成对象图。
- Vanilla 26.2 `ChunkTrackingView`：View Distance 的实际 Chunk 距离判定。

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`：固定 MCProtocolLib build 16、当前 World decode/physics 边界、每 Player EventLoop 规则。
- `.trellis/spec/backend/quality-guidelines.md`：只为当前复杂度提取 owner 和同步边界。
- `.trellis/spec/language/java.md`：Java 类型和方法拆分约束。

## Caveats / Not Found

- JOL 数据来自合成 Palette，不是线上 Heap Dump；未包含 Packet 原始数据和光照等非 `WorldState` 内容。
- CPU 数据是单机微基准，不是 JMH，也没有模拟多个 EventLoop、GC 或锁竞争。
- Vanilla View Distance 数量是距离判定的满集合；实际加载受移动、发送速率和后端配置影响。
- 没有当前部署的 Player 数、重叠率或 Heap Dump。最终是否值得应以 `P>=5、VD10、重叠>=50%` 是否符合真实部署为判断点。
- 后端插件若给不同 Player 发送不同 Block 内容，或 source 切换时两个 Packet 流进度不同，共享单一内容仍有既有正确性限制。

## Product Decision

- Date: 2026-08-14
- Decision: 收益不值得增加代码复杂度。

初版继续使用 Player-owned `World`。

本报告只保留量化依据，不授权实施 `ServerWorld`。

# Vanilla 26.2 缺失状态语义

## 结论

Vanilla 客户端没有统一的缺失状态暂停器。

它在 Entity、关系、Metadata 和 Chunk 层分别处理缺失状态。

Shadow 初版采用相同语义。

## Entity Packet

`ClientPacketListener` 的 Motion、Metadata、Move、Position Sync 和 Remove handler 都先按 Entity ID 查询对象。

找不到对象时，handler 不创建占位对象，也不保存 Packet 等待重放。

`ClientboundAddEntityPacket` 先按 Entity Type 创建真实客户端 Entity，再应用 Spawn 数据并加入 Level。

远程 Player 缺少 PlayerInfo 时不会创建。

## Passenger 关系

`handleSetEntityPassengersPacket()` 找不到 vehicle 时记录警告并忽略整个 Packet。

vehicle 存在时，Vanilla 先解除该 vehicle 的全部旧 passenger，再按 Packet 顺序重新绑定。

找不到某个 passenger 时只跳过该 passenger。

Vanilla 不保存 pending passenger ID，也不在后续 Add Entity 时自动补做旧关系。

## Metadata 默认值

每个 Entity 构造函数创建 `SynchedEntityData.Builder`。

Entity 继承链通过 `defineSynchedData()` 写入每个字段的默认值。

Builder 要求全部已注册字段都已定义，随后生成完整 `SynchedEntityData`。

因此未收到 Metadata Packet 表示继续使用默认值，不表示 Entity 状态未知。

## Chunk 和碰撞

`ClientChunkCache.getChunk(x, z, status, false)` 在 Chunk 不存在时返回 `null`。

`Level.getChunkForCollisions()` 使用 `ChunkStatus.FULL` 和 `loadOrGenerate=false`。

`BlockCollisions` 只在查询结果非空时读取 Block State 和 Collision Shape。

缺失 Chunk 不贡献碰撞 Shape。玩家 Tick 不会因此暂停。

需要强制读取的普通 World 查询可以得到 `EmptyLevelChunk`。这同样不会产生方块或流体状态。

World 缓存仍然区分缺失 Chunk 和已加载的空气。查询语义没有把缺失 Chunk 写成空气 Chunk。

## 服务端校正

Vanilla 继续执行本地 Player Tick 和位置上报。

收到 Player Position 时，它覆盖本地位置，发送 Accept Teleportation，再发送当前 PosRot。

服务端校正负责收敛客户端预测偏差。

## 规划影响

- 不增加通用 `ready`、`paused` 或缺失状态恢复器。
- 不增加 Entity Packet 等待队列。
- 不增加 Passenger 等待层。
- Entity 创建时加载 Automation 需要的固定默认值。
- 缺失 Chunk 不提供碰撞或流体状态，但不暂停 Player tick。
- Player Loaded 继续只作为初次加载和 Respawn 的协议门控。

## 固定源码

版本为 Minecraft 26.2。

源码来自本机固定 merged deobf source JAR。

关键类如下：

- `net.minecraft.client.multiplayer.ClientPacketListener`
- `net.minecraft.client.multiplayer.ClientChunkCache`
- `net.minecraft.world.level.Level`
- `net.minecraft.world.level.BlockCollisions`
- `net.minecraft.network.syncher.SynchedEntityData`
- `net.minecraft.world.entity.Entity`

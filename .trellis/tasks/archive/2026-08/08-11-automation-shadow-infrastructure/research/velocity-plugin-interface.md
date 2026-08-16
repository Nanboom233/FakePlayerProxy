# Velocity Plugin 接口研究

## 状态

Packet Event、Packet 发送和事件缓存章节继续有效。

注册和连接生命周期由 `registration-connection-lifecycle.md` 替代。

后端关闭通知已由周期 tick 清理替代。

## 结论

Patch 不增加 automation 专用连接对象。

Plugin 使用原生 `PostLoginEvent` 注册 `AutomationService`。

登出处理复用现有 `DisconnectEvent`。

Plugin 继续使用 Velocity 的 `@Subscribe`。

客户端到服务端的 listener 使用 `C2SPacketEvent<T>` 参数。

服务端到客户端的 listener 使用 `S2CPacketEvent<T>` 参数。

Velocity 在 listener 注册阶段读取 `T`，并按 Packet 类型建立索引。

Packet 处理必须在连接 event loop 中同步完成。

Patch 不能依赖 Plugin。

Plugin 可以依赖 Patch 和 Velocity。

Patch 和 Plugin 共享同一份 MCProtocolLib 运行时依赖。

## Velocity 证据

`ServerConnectedEvent` 触发时，Velocity 已暂停后端读取。

该事件完成后，Velocity 才安装 play handler 和恢复读取。

该事件公开准确的 `Player`。

证据位于 `TransitionSessionHandler.java:113` 到 `TransitionSessionHandler.java:147`。

`DisconnectEvent` 当前在 `ConnectedPlayer.teardown()` 关闭后端后触发。

因此，Plugin 当前不能用该事件阻止后端关闭。

证据位于 `ConnectedPlayer.java:938` 到 `ConnectedPlayer.java:969`。

已决定把该事件移到后端关闭前。

Velocity 在事件完成前暂停后端读取。

`DisconnectEvent.cancel()` 取消此次实际登出。

取消不阻止真实前端 Channel 关闭。

取消后，Velocity 注销旧 `Player`，但保留原后端连接。

未请求保留时，Velocity 保持原关闭行为。

该设计不增加第二个玩家登出事件或事件结果类型。

## ServerConnection 关闭清理

保留的 `ServerConnection` 可以在玩家登出后独立关闭。

现有 `DisconnectEvent` 不会为该关闭再次发布。

Automation 已有 50 ms 周期 tick。

Manager 在每次 tick 前查询活动后端连接。

后端不存在时，Manager 使用 `remove(player, service)` 删除准确条目。

Manager 随后取消该周期 tick。

因此，Patch 不需要增加后端关闭 Event。

`ServerConnection` 已代表一个玩家到后端服务器的连接。

它已公开 `getServer()`、`getPreviousServer()` 和 `getPlayer()`。

它没有公开原始数据包处理、原始数据包发送或连接关闭方法。

## `@Subscribe` 泛型筛选

原生 `VelocityEventManager` 只读取 listener 方法的原始参数类型。

两个 Packet Event 的泛型参数在运行期都会擦除。

Java 会在运行期擦除事件对象上的 `T`。

但是方法签名仍保存具体 Packet 类型的泛型信息。

Patch 可以在注册 listener 时读取 `Method.getGenericParameterTypes()`。

Patch 不需要新增 `@SubscribePacket`。

Patch 也不需要 `setPacketHandler`。

Packet listener 必须使用具体的 `C2SPacketEvent<T>` 或 `S2CPacketEvent<T>`。

Raw Packet Event 和通配 Packet Event 无法确定 Packet ID，因此禁止注册。

该限制避免把缺失类型信息解释为全 Packet 订阅，也避免每个 Packet 都执行 MCProtocolLib 解码。

## Velocity Packet

`MinecraftDecoder` 从输入中先读取 packet ID，再解码 payload。

`MinecraftEncoder` 先写入 packet ID，再编码 payload。

Velocity 的 `MinecraftPacket` 位于 `proxy` 模块。

它不属于 Velocity 公共 API。

该接口直接使用 Netty `ByteBuf`。

它还包含 `handle(MinecraftSessionHandler)`。

Velocity Packet 类按协议版本执行编码和解码。

`StateRegistry` 按协议状态、方向和版本映射 Packet ID。

Velocity 只解码它已注册的 Packet。

Velocity 在 play 状态把未注册 Packet 保留为原始 `ByteBuf`。

Velocity 的 `KeepAlivePacket` 同时表示 clientbound 和 serverbound Packet。

该类是可变对象，并通过 `randomId` 保存字段。

## 协议 776 覆盖检查

Velocity 内部 Packet 类与 MCProtocolLib Packet 类不是一对一关系。

Velocity 源码还包含旧协议 Packet、辅助类和跨方向复用的 Packet 类。

这些类不能按类名与 MCProtocolLib 比较。

本设计只需要比较协议状态、方向和 Packet ID。

运行时检查使用 Velocity `MINECRAFT_26_2` 和 MCProtocolLib `26.2`。

两者的协议号均为 `776`。

Velocity 共注册 86 个 `(state, direction, packetId)` 项。

该数量包含 75 个可解码项和 11 个只编码项。

MCProtocolLib 对这 86 个项均有 Packet 定义。

缺失项数量为 0。

因此，Packet API 不需要暴露 Velocity 内部 Packet 类型。

Plugin 只使用 MCProtocolLib Packet 类型。

玩家登录事件和玩家登出事件仍表示连接生命周期。

Packet 到达不表示连接生命周期已经完成。

## Login Finished Packet

MCProtocolLib 使用 `ClientboundLoginFinishedPacket` 表示后端 Login Success。

Velocity 使用 `ServerLoginSuccessPacket` 表示同一个线上 Packet。

后端 Decoder 可以发布该 Packet 的 `S2CPacketEvent`。

该 Packet 只表示后端接受登录协议。

它不表示玩家已完成 Velocity 登录。

Login relay 在该 Packet 到达时仍未注册前端玩家。

普通后端登录在该 Packet 后仍需完成配置和 Join Game。

Velocity 源码也明确说明后端登录此时尚未完成。

前端 Login Success 由 Velocity 主动发送，不经过 Decoder。

因此，Decoder-only Packet Event 看不到前端 Login Success。

Plugin 可以订阅该 Packet，但不能用它注册 `AutomationService`。

`AutomationService` 在同步 `PostLoginEvent` 中注册。

Mod relay 在该 Event 前已经创建并暂停原后端连接。

Plugin 使用现有后端 helper 取得该连接。

后续 `S2CPacketEvent<ClientboundLoginPacket>` 初始化 GAME 状态。

Patch 不修改 `ServerConnectedEvent`。

## Minecraft 原生 Packet

Minecraft 网络协议只定义字节格式。

网络协议不定义可供 Plugin 使用的 Java Packet 类。

线上 Packet 包含 Packet ID 和字段数据。

外层连接还处理长度、压缩和加密。

Mojang 服务端有自己的内部 Packet 类。

这些类属于服务端实现，不属于网络协议公共接口。

因此，“原生 Minecraft Packet”必须指明是线上格式还是 Mojang 内部类。

## MCProtocolLib Packet

最终方案使用 MCProtocolLib `26.2-20260809.160751-16`。

MCProtocolLib 独立实现 Minecraft 网络协议。

它为协议 Packet 提供 `Packet` 和 `MinecraftPacket` 接口。

它使用 `PacketRegistry` 按方向映射 Packet ID 和 Packet 类。

它为大部分协议 Packet 提供单独的 Java 类。

它把 KeepAlive 分成 `ClientboundKeepAlivePacket` 和 `ServerboundKeepAlivePacket`。

这两个类使用只读 `pingId` 字段。

它们与 Velocity 的 `KeepAlivePacket` 没有继承关系。

两套类表示相同的线上 Packet，但对象结构和 codec 不同。

## 已否决的数据包边界

不再采用 `setPacketHandler`。

不再把 packet ID 和 payload 作为当前默认设计。

不新增 `@SubscribePacket`。

## 已确定的数据包边界

两个 Packet Event 的 `T` 使用 MCProtocolLib Packet 类型。

Packet Event 不接收 Velocity 内部 Packet 类型。

Packet Event 不提供 `InboundConnection` 或 `ServerConnection`。

KeepAlive Packet 不包含玩家标识。

因此，多玩家隔离不能依赖 Packet Event 内容。

Packet listener 必须取得准确的 `Player`，但不需要取得两个物理连接。

`T` 必须是具体类型，不能省略或使用通配符。

Patch 负责 Velocity Packet 字节流和 MCProtocolLib Packet 之间的转换。

Plugin 不打包自己的 MCProtocolLib 副本。

## 当前 Velocity Packet 流程

前端入站 Packet 使用 serverbound 方向。

后端入站 Packet 使用 clientbound 方向。

普通入站流程如下：

```text
socket bytes
-> cipher decoder
-> frame decoder
-> compression decoder
-> MinecraftDecoder
-> MinecraftConnection
-> MinecraftSessionHandler
```

`MinecraftDecoder` 读取 Packet ID。

如果 Velocity 注册了该 Packet，Decoder 创建 Velocity Packet 并解码 payload。

`MinecraftConnection` 先调用具体的 `handle(packet)`。

如果具体 handler 不处理 Packet，它调用 `handleGeneric(packet)`。

如果 Velocity 未注册 play Packet，Decoder 保留包含 Packet ID 的 `ByteBuf`。

`MinecraftConnection` 将该数据交给 `handleUnknown(ByteBuf)`。

后端 play handler 通常把 Packet 写入前端连接。

普通出站流程如下：

```text
Velocity Packet 或 Packet ByteBuf
-> MinecraftEncoder
-> compression encoder
-> frame encoder
-> cipher encoder
-> socket bytes
```

`MinecraftEncoder` 只编码 Velocity Packet。

已经包含 Packet ID 和 payload 的 `ByteBuf` 会跳过该 Encoder。

后续 handler 仍会完成压缩、分帧和加密。

## `0001` Packet 流程

已接受的 Mod 连接保留普通 Velocity Packet pipeline。

Velocity 分别解密前端连接和后端连接。

因此，`PacketEvent` 可以处理该连接的明文 Packet。

Vanilla raw tunnel 在 Login bootstrap 后移除 frame codec 和 Packet codec。

raw tunnel 保留前端和后端的 cipher codec。

每个入站 cipher decoder 先解密数据。

raw tunnel 转发解密后的无边界字节流。

对端 cipher encoder 在写入 socket 前重新加密数据。

因此，Vanilla raw tunnel 不产生 `PacketEvent`。

## 延迟 MCProtocolLib 解码

`MinecraftDecoder` 已在 `tryDecode()` 中读取 Packet ID。

Patch 应复用该 Packet ID，而不是增加第二个读取器。

Patch 在创建 Velocity Packet 前查询 Packet 订阅缓存。

缓存键至少包含协议状态、方向和 Packet ID。

缓存值包含匹配的 Packet 类型和 Handler 列表。

没有订阅时，Patch 不创建 MCProtocolLib Packet。

存在订阅时，Patch 使用 payload 的只读 duplicate 创建 MCProtocolLib Packet。

当前 MCProtocolLib Packet 模型不保存 payload `ByteBuf`。

因此，该解码不需要复制 payload 字节。

Event 处理完成后，原 buffer 仍可进入 Velocity 的原解码流程。

该设计只对被订阅的 Packet 增加 MCProtocolLib 解码成本。

被订阅的 Packet 可能同时经过 MCProtocolLib 解码和 Velocity 解码。

该双重解码只发生在订阅命中的 Packet。

当前 MCProtocolLib codec 固定为 Minecraft 26.2 和协议 776。

Patch 只在连接协议与共享 codec 匹配时发布 `PacketEvent`。

Vanilla raw tunnel 不受该限制影响，因为它不发布 `PacketEvent`。

## 线程约束

数据包处理器在后端连接的 event loop 中同步运行。

处理器返回后，Velocity 才继续处理该数据包。

## Velocity Result 处理

`ResultedEvent` 只保存一个可变的 Result。

Event Manager 不解释 Result。

Event Manager 按 priority 把同一个 Event 对象依次交给全部 listener。

后一个 listener 可以读取并替换前一个 listener 设置的 Result。

全部 listener 返回后，事件触发点读取最终 Result。

事件触发点根据最终 Result 继续、替换数据或停止处理。

listener 抛出异常时，Event Manager 记录异常并继续调用后续 listener。

## Packet 修改方案

三态 `PacketResult` 会为 Packet 替换创建一个额外 Result 对象。

已选择的方案让 `PacketEvent` 直接保存当前 Packet。

`getPacket()` 返回当前 Packet。

`setPacket(T packet)` 替换当前 Packet 引用。

`cancel()` 取消当前 Packet。

`isCancelled()` 返回当前取消状态。

Plugin 不使用 `allowed()` 或 `denied()` 操作 Packet。

`PacketEvent` 不实现 `ResultedEvent`。

该 API 不需要三态 `PacketResult`。

`cancel()` 只把取消状态从 false 改为 true。

后续 listener 不能恢复已取消的 Packet。

MCProtocolLib 26.2 Packet 使用只读字段。

修改 Packet 字段仍需要一个新的 MCProtocolLib Packet。

该方案只消除额外的 `PacketResult` 对象。

## 双向 Packet Event 方案

前端 `MinecraftDecoder` 接收客户端发出的 serverbound Packet。

该 Decoder 发布 `C2SPacketEvent<T>`。

后端 `MinecraftDecoder` 接收服务端发出的 clientbound Packet。

该 Decoder 发布 `S2CPacketEvent<T>`。

两个 Decoder 可以使用同一套 Packet 订阅缓存和分发流程。

客户端发包时，`Player` 是 source，`ServerConnection` 是 target。

服务端发包时，`ServerConnection` 是 source，`Player` 是 target。

这与现有 `PluginMessageEvent` 的 source 和 target 约定相同。

只在入站 Decoder 发布 Event 时，每个网络 Packet 最多产生一个 Event。

Encoder 不发布 Packet Event。

因此，同一个转发 Packet 不会在返回方向产生第二个 Packet Event。

Plugin 通过发送接口创建的 Packet 也可能再次进入 Event。

只拦截入站 Packet 可以避免重复 Event 和发送循环。

该方案不包含 Velocity 自己创建的出站 Packet。


## Velocity 创建的出站 Packet

Velocity 会在前端连接和后端连接上主动创建 Packet。

这些 Packet 不来自该连接的入站 Decoder。

前端登录流程包括 `EncryptionRequestPacket`、`SetCompressionPacket` 和 `ServerLoginSuccessPacket`。

后端登录流程包括 `HandshakePacket`、`ServerLoginPacket`、`LoginPluginResponsePacket` 和 `LoginAcknowledgedPacket`。

Velocity 也会创建登录阶段的 Plugin Message 和 Client Settings Packet。

Play 阶段包括 Velocity 创建的 KeepAlive、Disconnect、Plugin Message 和 Transfer Packet。

Velocity API 还会创建 chat、action bar、title、sound、resource pack、cookie 和 server links Packet。

服务器切换流程会创建 title reset、respawn 和 channel registration Packet。

部分出站 Packet 是对入站 Packet 的重建版本。

Cookie、Plugin Message 和 resource pack 处理包含这种重建。

只在 Decoder 发布 `PacketEvent` 时，Plugin 不会收到这些出站 Packet 的 Event。

Plugin 通过 Packet 发送接口提交的 Packet 也不会产生 `PacketEvent`。

该规则避免发送接口再次触发相同 Plugin 的 Packet listener。

## 多 Listener 缓存

Event Manager 在 listener 注册和注销时构建 Packet 订阅索引。

索引键是协议状态、方向和 Packet ID。

索引值包含 MCProtocolLib Packet 定义和排好顺序的 listener 数组。

Event Manager 在写锁内创建新的不可变索引，然后原子替换当前索引。

Decoder 对一个 Packet 只读取一次索引项。

没有索引项时，Decoder 不创建 MCProtocolLib Packet 或 `PacketEvent`。

存在索引项时，Decoder 只解码一次 Packet，并只创建一个 `PacketEvent`。

Decoder 按 priority 在连接 event loop 中依次调用索引项中的 listener。

全部 listener 共享同一个 `PacketEvent`，因此后一个 listener 可以看到前一个 listener 的修改。

listener 注册或注销不阻塞 Packet 读取。

正在分发的 Packet 可以继续使用旧索引快照。

后续 Packet 使用新索引快照。

不同连接可以在各自的 event loop 中并行发布 `PacketEvent`。

Plugin 必须保护跨连接共享的状态。

Plugin 不能在该回调中执行阻塞操作。

Plugin 从其他线程发送数据包时，Velocity 把发送任务提交到后端 event loop。

## ViaVersion 参考

ViaVersion 的 Velocity 集成把生命周期代码和网络注入代码分开。

它在网络热路径中使用同步处理器，而不是对每个数据包发布 Velocity 事件。

它的注入层依赖 Velocity 内部 pipeline 名称和 Netty 类型。

本任务用 Patch 提供稳定 API，因此 Plugin 不需要反射或 Mixin。

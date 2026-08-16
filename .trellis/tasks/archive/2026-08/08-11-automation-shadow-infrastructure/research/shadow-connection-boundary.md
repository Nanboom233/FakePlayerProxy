# Shadow Connection Boundary

## 结论

Plugin 保存 `shadow` 状态。

Plugin 通过 `DisconnectEvent.cancel()` 取消正常后端登出。

## Source Evidence

- `ConnectedPlayer.disconnect()` 在 frontend event loop 关闭 frontend connection。
- `ClientPlaySessionHandler.disconnected()` 调用 `ConnectedPlayer.teardown()`。
- `ConnectedPlayer.teardown()` 默认关闭 `connectedServer`。
- `VelocityServerConnection.isActive()` 默认依赖 `proxyPlayer.isActive()`。
- `BackendPlaySessionHandler` 已经拥有 backend connection 和 packet flow。
- `MinecraftConnection.channelRead()` 在 typed dispatch 后释放 message。

## Patch 流程

上游 `ConnectedPlayer.teardown()` 先关闭后端，再发布 `DisconnectEvent`。

0002 必须调整该顺序。

1. `teardown()` 先计算原登录状态。
2. 它暂停当前后端读取。
3. 它发布 `DisconnectEvent`。
4. Event 未取消时，它关闭后端并注销 `Player`。
5. Event 已取消时，它注销 `Player`，但不关闭后端。
6. Event 已取消时，它恢复后端读取。
7. 两条路径都完成原 `teardownFuture`。

取消结果保存在 `ConnectedPlayer`。

该值只表示本次实际登出是否取消。

该值不表示 automation 或 `shadow`。

`VelocityServerConnection.isActive()` 继续检查以下条件：

- 后端对象存在
- 后端 Channel 未关闭
- 后端没有正常关闭
- 前端在线或实际登出已取消

Patch 不增加 Retained Handler。

原 `BackendPlaySessionHandler` 继续处理 Packet。

原前端 handler 继续保存在关闭的前端 `MinecraftConnection` 中。

`sendPacket(packet, false)` 只在本地调用该 handler。

它不调用关闭的前端 Channel。

后端 Channel inactive 后，周期 tick 发现后端失活。

## Plugin 流程

1. `/player shadow` 在 Backend EventLoop 把服务的 `shadow` 设为 true。
2. Plugin 向原后端发送零输入和 Stop Sprinting。
3. Plugin kick 命令源 `Player`。
4. `DisconnectEvent` listener 查询同一个 `Player` 的服务。
5. `shadow` 为 true 时，listener 调用 `cancel()`。
6. Plugin 继续使用原后端连接。
7. 周期 tick 使用 `remove(player, service)` 清理服务。

Patch 不读取 Plugin 状态。

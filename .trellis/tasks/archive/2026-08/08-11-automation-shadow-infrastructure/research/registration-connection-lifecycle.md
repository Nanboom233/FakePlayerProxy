# Research: Automation 注册和连接生命周期

- Query: Plugin 如何在不增加 relay marker 的前提下注册服务，并在前端退出后保留原后端。
- Scope: internal
- Date: 2026-08-13

## 结论

Plugin 使用原生 `PostLoginEvent` 注册服务。

0002 不扩展 `ServerConnectedEvent`。

0002 只修改通用登出取消和配置切换能力。

## 注册时点

0001 的 Mod relay 按以下顺序运行：

1. Velocity 创建 provisional `ConnectedPlayer` 和原后端连接。
2. 后端完成在线认证并暂停读取。
3. 前端完成 Login Acknowledged。
4. Velocity 发布 `PostLoginEvent`。
5. 0001 等待该 Event 和客户端设置。
6. 0001 把原后端切换到 CONFIGURATION。
7. 0001 恢复原后端读取。

因此，`PostLoginEvent` listener 可以在首个 Registry Packet 前创建服务。

该 listener 返回原生 `EventTask`。

EventTask 把注册提交到前端 EventLoop，并在完成后恢复 Event。

0001 的 Vanilla 短登录在该 Event 前已经关闭原后端。

普通 Velocity 登录在该 Event 后才选择初始后端。

Plugin 可以用现有后端 helper 区分三种流程。

它不需要 relay marker 或新生命周期 Event。

## 后端 helper

运行时 `Player` 是 `ConnectedPlayer`。

`ConnectedPlayer.getConnection()` 返回前端 `MinecraftConnection`。

`getConnectionInFlightOrConnectedServer()` 返回当前准确的后端连接对象。

`VelocityServerConnection.getConnection()` 返回后端 `MinecraftConnection`。

Plugin 编译时使用 patched Velocity JAR。

所以 helper 可以直接调用这些方法。

Plugin 不需要反射、Mixin、Player 子类或 bridge 接口。

## 登出顺序

固定上游的 `ConnectedPlayer.teardown()` 按以下顺序运行：

1. 关闭 in-flight 后端。
2. 关闭 connected 后端。
3. 注销 `Player`。
4. 发布 `DisconnectEvent`。

该顺序不能支持取消实际登出。

0002 必须把 Event 移到 connected 后端关闭和 Player 注销之前。

它在 Event 完成前暂停后端读取。

Plugin listener 返回原生 `EventTask`。

EventTask 把服务查询和取消操作提交到当前连接 EventLoop。

Velocity 等待该任务完成。

Velocity 在同一个 EventLoop 处理取消结果。

Event 未取消时，Velocity 执行原关闭和注销流程。

Event 已取消时，Velocity 只注销真实 `Player`，并恢复后端读取。

## 后端活动条件

固定上游的 `VelocityServerConnection.isActive()` 依赖 `proxyPlayer.isActive()`。

前端关闭后，该检查会使首个后端 Packet 主动关闭后端。

0002 让该方法接受以下任一条件：

- 前端仍在线
- 本次实际登出已经取消

该方法仍检查后端 Channel 和正常关闭状态。

取消状态属于通用连接生命周期。

它不包含 automation 或 `shadow` 语义。

## 配置切换

后端收到 Start Configuration 后会暂停读取。

固定上游随后发布 `PlayerEnterConfigurationEvent`。

前端已关闭时，固定上游不会设置 `pendingConfigurationSwitch`。

0002 在登出已取消时跳过前端网络写入，但仍设置该标志。

服务的现有 20 TPS tick 等待该标志，再发送一次 Configuration Acknowledged。

该做法等待全部 `PlayerEnterConfigurationEvent` listener 完成。

它不增加新的 Event 或 task。

后端 Finish Configuration 使用原 `PlayerFinishedConfigurationEvent`。

0002 跳过关闭前端的 Finish Packet 写入，但仍发布该 Event。

Plugin listener 不在发布线程读取服务状态。

它把查询、状态判断和发送一起提交到 Backend EventLoop。

## 后端关闭

周期 tick 在连接 EventLoop 查询活动后端连接。

后端不存在时，Manager 使用 `remove(player, service)` 删除准确条目。

Manager 随后取消该周期 tick。

Fresh login 的新 `Player` 使用不同 Map key。

旧周期 tick 不能删除新服务。

## 文件证据

- `plugin/patch/0001-server-hello-marker.patch`：Mod relay、Vanilla Transfer 和 raw tunnel 分支。
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ConnectedPlayer.java:938`：上游登出顺序。
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ConnectedPlayer.java:1355`：配置开始流程。
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/VelocityServerConnection.java:342`：后端活动条件。
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/BackendPlaySessionHandler.java:135`：活动条件调用点。
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/ConfigSessionHandler.java:235`：配置结束流程。

## 已排除方案

- 扩展 `ServerConnectedEvent` 交付 Login Packet
- 在 `PlayerEnterConfigurationEvent` 创建服务
- relay marker
- Retained Handler
- Plugin 反射注入
- Player 包装类或子类
- 第二个后端连接

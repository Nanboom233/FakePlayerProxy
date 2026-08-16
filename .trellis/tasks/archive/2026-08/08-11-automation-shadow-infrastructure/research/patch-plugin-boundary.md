# Patch 与 Plugin 边界研究

## 基线

本任务以 commit `7b12227` 为代码基线。

`0001-login-relay.patch` 保存 login relay、加密、解密和 raw tunnel。

`0002-automation-extension.patch` 只保存通用 Velocity Plugin 接口。

Patch 文件只包含生产代码。

Patch 测试位于 `plugin/patch/test/`。

## Patch 责任

- 复用原生 `PostLoginEvent`
- 使 `DisconnectEvent` 支持 `cancel()`
- 发布具体类型的 C2S 和 S2C Packet Event
- 延迟执行 MCProtocolLib Packet 转换
- 发送 MCProtocolLib Packet
- 支持取消登出后的现有 Velocity handler

Patch 不依赖 Plugin。

Patch 不保存 automation 或 `shadow` 状态。

## Plugin 责任

- 保存 `AutomationManager`
- 保存每玩家 `AutomationService`
- 在 Mod relay 的 `PostLoginEvent` 注册服务
- 实现 `/player shadow`
- 使用 MCProtocolLib 维持基础玩家状态机
- 处理 Fresh Login 注册替换
- 在周期 tick 处理后端关闭清理

`AutomationManager` 使用 `Map<Player, AutomationService>`。

普通查询使用准确的 `Player`。

Fresh Login 注册才扫描相同认证 UUID。

## 连接边界

Plugin 通过 helper 取得 `MinecraftConnection`。

`AutomationService` 不保存 `Player` 或 connection。

`DisconnectEvent.cancel()` 取消此次实际登出。

真实前端 Channel 仍会关闭。

Velocity 注销旧 `Player`，但保留原后端连接。

后端关闭后，周期 tick 清理准确条目。

## Packet 边界

Packet Event 使用 MCProtocolLib Packet。

Packet listener 使用现有 `@Subscribe` 和具体泛型参数。

只有 C2S Packet Event 可以取消。

S2C Packet Event 只能读取或替换。

`ServerboundKeepAlivePacket`、三个配置态响应和 `ServerboundChatAckPacket` 必须进入 Velocity 当前前端 handler。

其他状态机响应直接写入后端连接。

直接写后端会绕过 KeepAlive 和 PLAY/CONFIG 状态处理。

## 已拒绝设计

- Patch 中的 Automation 接口
- Patch 中的 `shadow` 状态
- Retained Handler
- ownership 或 transfer 状态
- 第二个后端连接
- Plugin 专用 Mixin
- UUID 索引
- Packet callback manager
- Patch 文件中的测试

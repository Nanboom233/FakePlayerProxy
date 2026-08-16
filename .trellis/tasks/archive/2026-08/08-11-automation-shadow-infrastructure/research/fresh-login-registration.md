# Fresh Login Registration

## 结论

Fresh Login 不需要 reset operation。

新 `Player` 通过 `PostLoginEvent` 的原生 `EventTask` 取得新的 `AutomationService`。

旧 automation 状态不进入新服务。

## Minecraft 26.2 Evidence

`ServerLoginPacketListenerImpl` 使用 authenticated `GameProfile` 处理 duplicate player。

因此，registration 使用 authenticated UUID。Login Start username 不能作为 registration key。

## Registration Race

取消 `DisconnectEvent` 后，真实 `Player` 退出 Proxy 在线索引。

Fresh Login 因此可以创建新的 Velocity `Player`。

Minecraft 26.2 后端会断开相同 UUID 的旧连接。

它等待旧连接离开后继续新连接登录。

新 `PostLoginEvent` 随后执行既定注册流程。

`AutomationManager` 扫描相同认证 UUID 的旧服务。

它先用 `remove(oldPlayer, oldService)` 删除旧条目。

它把关闭操作提交到旧 Player 的连接 EventLoop。

注册 `EventTask` 等待旧服务关闭，再保存新服务。

旧服务的周期 tick 执行以下操作：

```text
automations.remove(oldPlayer, matchingAutomationService)
```

如果旧连接先关闭，Plugin 先删除旧注册。Fresh login 随后写入新注册。

如果新服务已经注册，旧周期 tick 不能删除新 `Player` 键。

旧周期 tick 开始时检查 `map.get(oldPlayer) == oldService`。

该流程不增加 `LoginEvent` hook 或 UUID 索引。

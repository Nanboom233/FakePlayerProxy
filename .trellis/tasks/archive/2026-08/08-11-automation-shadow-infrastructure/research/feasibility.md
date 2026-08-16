# 使用现有 Backend Connection 的可行性

> 状态：早期可行性记录。当前设计不使用无 Channel MCProtocolLib Session。

## 结论

automation 必须继续使用 accepted Mod connection 的现有 backend connection。
该 connection 已经保存 cipher、compression、codec 和 Play state。

plugin 不能用 AES Secret 重建该 connection。
AES-CFB8 stream state 不能从任意 packet boundary 恢复。

## Repository Evidence

`InitialLoginSessionHandler` 从 Mod response 恢复 AES Secret `K`。
`LoginSessionHandler` 用临时 `K` 完成 target server key response。

`MinecraftConnection.enableEncryption()` 把 cipher 安装到 connection pipeline。
该 method 不保存可导出的 `K` 副本。

`JavaVelocityCipher` 和 `NativeVelocityCipher` 都保存可变 cipher state。
相同 `K` 不能恢复已有 stream position。

`ConnectedPlayer` 保存当前 `VelocityServerConnection`。
`BackendPlaySessionHandler` 已经处理该 connection 的 Play packet。

MCProtocolLib `ClientNetworkSession` 会创建新的 Netty Channel。
它不能接收 Velocity 的 connection 或 cipher state。

Plugin 可以使用无 Channel 的 MCProtocolLib `Session` 适配器。

该适配器只维护协议状态并产生 Packet。

raw tunnel 删除 Minecraft codec。
Velocity 不能在该 flow 中处理解码后的 packet。

## 设计结果

patch 必须保留原 backend connection。
Patch 必须继续使用原 cipher、codec 和 Velocity handler。

patch 不复制、保存或记录 AES Secret `K`。
Plugin 不接收 cipher。

Plugin 通过已决定的 connection helper 使用 `MinecraftConnection`。

其他 `/player` action 保持当前行为。

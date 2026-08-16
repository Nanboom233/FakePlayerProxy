# 范围审查

## 结论

正式方案只保留四类工作：

1. 恢复 clean Velocity 和 `0001-login-relay.patch`。
2. 用 `0002-automation-extension.patch` 提供通用 Plugin API。
3. 在 Plugin 实现多玩家 Automation、fresh login 和 `shadow`。
4. 在 Plugin 实现基础协议和已确认的 Player 计算。

## 删除的加码要求

- 后端关闭 Event
- 移动属性和效果系统
- 载具和乘客状态
- 实体推挤
- World Border
- 特殊方块行为
- 自动 Respawn
- Resource Pack 和 Code of Conduct 处理

## 删除的冗余结构

- 第二个后端连接
- Retained Handler
- UUID 索引
- relay marker
- 临时 Login Map
- PacketRouter 和 PacketSink
- Player、World 和 Movement 子服务
- 额外配置 Event
- pending Packet Map

## 保留的最小状态

实体状态按用户要求保留。

初版实体表只保存服务端已发送的基本实体状态。

Player 计算不读取实体表。

世界状态只保存碰撞和水计算需要的 `ChunkSection[]`。

后端关闭由现有 50 ms 周期 tick 检测。

Patch 不再为清理 Map 增加 Event。

Fresh login 在旧 Player 的连接 EventLoop 关闭旧服务。

该流程复用注册 `EventTask`，不增加索引或线程锁。

## 测试范围

Patch 测试只覆盖 Patch API 和实际登出取消。

Plugin 测试只覆盖多玩家、fresh login、协议切换和五个 Player 场景。

五个场景是普通击退、爆炸、水、方块碰撞和死亡。

测试不检查类布局、字段布局、源码文本或固定延迟。

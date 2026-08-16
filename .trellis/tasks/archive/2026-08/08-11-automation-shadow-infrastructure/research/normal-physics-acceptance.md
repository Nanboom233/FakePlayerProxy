# Research: Shadow 正常受击位移验收边界

- Query: 如何把“受击后位移表现正常”收敛为最小、可观察、不过度加码的初版验收边界
- Scope: internal / mixed
- Date: 2026-08-13

## Findings

### 需求来源

用户直接要求初版维护生命、死亡、实体和世界。
用户也直接要求玩家受击后的位移表现正常。

这两个要求直接产生以下初版能力：

- Shadow 保存玩家生命和死亡状态。
- Shadow 保存服务端已发送的实体状态和世界状态。
- Shadow 把玩家自身的外部速度送入 20 TPS 物理循环。
- Shadow 用世界碰撞约束玩家轨迹。
- Shadow 把物理结果发回后端服务器。

“表现正常”不等于逐 tick 复制完整 Vanilla 客户端。
初版只保证无输入玩家在常见受击场景中产生合理轨迹。

### 可观察定义

初版用以下结果定义“正常受击位移”：

1. Shadow 接收服务端给玩家自身的外部速度。
2. Shadow 按 20 TPS 更新玩家位置和速度。
3. Shadow 应用重力、阻力、地面摩擦和方块碰撞。
4. Shadow 正确更新 `onGround` 和 `horizontalCollision`。
5. Shadow 在位置或碰撞状态变化后发送移动 Packet。
6. Shadow 不让玩家穿过已加载的实心碰撞体。
7. Shadow 以服务端位置纠正为最终结果。

初版不要求每个中间坐标与 Vanilla 完全相同。
初版要求相同输入产生相同的移动方向、碰撞结果和最终静止区域。

### 必须场景

| 场景 | 初版要求 | 需求来源 |
| --- | --- | --- |
| 普通攻击 | `ClientboundSetEntityMotionPacket` 更新玩家速度。物理循环产生连续位移。 | 用户直接要求受击位移正常。 |
| 投射物攻击 | 使用与普通攻击相同的玩家速度路径。伤害来源不改变移动处理。 | “受击”直接覆盖服务端已结算的投射物击退。 |
| 爆炸 | `ClientboundExplodePacket.playerKnockback` 累加到当前速度。后续物理处理该速度。 | 爆炸使用独立 Packet。不支持会留下明显的外力缺口。 |
| 地面 | 玩家受击后受地面摩擦。玩家不能进入地面。 | 正常位移直接需要地面碰撞。 |
| 墙 | 玩家在墙前停止或沿墙滑动。`horizontalCollision` 反映碰撞。 | 正常位移直接需要水平碰撞。 |
| 平台边缘 | 玩家离开平台后清除 `onGround`。重力继续降低玩家位置。 | 正常击退必须支持被打下平台。 |
| 落地 | 玩家接触下方碰撞体后停止下落。`onGround` 变为 `true`。 | 平台边缘场景直接产生落地行为。 |
| 水 | 玩家进入已加载的水后使用流体阻力和浮力。流动水还应用流向。 | 用户要求维护世界。正常轨迹不能把水当空气。 |
| 世界更新 | Chunk、Block Update 和 Section Update 在后续 tick 中改变碰撞查询。 | 正常轨迹必须使用当前世界状态。 |
| 生命和死亡 | `ClientboundSetHealthPacket` 更新生命。死亡后停止普通物理和移动发送。 | 用户直接要求维护生命和死亡。 |

普通攻击和投射物攻击共享同一个外部速度输入。
它们不需要两套物理逻辑或两套验收测试。

爆炸必须单独验收。
它把击退向量累加到当前速度，而普通攻击替换玩家速度。

### 实体状态与实体推挤

初版保存服务端已发送的实体状态。
该状态包括实体 ID、类型、位置、速度、姿态和移除状态。

本地实体 AABB 推挤不属于最小受击闭环。
服务端仍通过玩家速度或位置纠正表达权威结果。
Mineflayer 和 MCC 也不能提供完整的本地实体推挤参考。

因此，初版不验收玩家与附近实体的逐 tick 本地推挤。
该行为属于 Vanilla 高精度物理扩展。

### 活塞

活塞推动需要移动活塞碰撞体、Block Event 和专用位移限制。
该行为不由“受击后位移正常”直接产生。

初版仍保存相关世界更新。
初版不模拟移动活塞对玩家的逐 tick 推动。

加入活塞物理会扩大世界模型和碰撞模型。
该能力属于后续世界物理扩展。

### 死亡边界

生命降到零后，Shadow 进入死亡状态。
死亡状态停止普通物理 tick 的移动计算。
死亡状态停止普通移动 Packet。

自动 Respawn 是独立产品策略。
用户尚未决定该策略。
初版验收不能默认发送 `PERFORM_RESPAWN`。

如果后续启用自动 Respawn，Respawn 必须清理旧世界、实体和物理状态。
该流程还必须等待新位置和区块状态后恢复物理。

### 不在初版场景

以下能力超过当前可观察要求：

- 每个物理 tick 与 Minecraft 26.2 坐标完全相同。
- 本地实体 AABB 推挤。
- 活塞逐 tick 推动。
- 载具物理。
- 鞘翅、烟花和主动飞行。
- 主动行走、跳跃、疾跑和潜行输入。
- 所有特殊方块的完整行为。
- 实体 AI、寻路、动画和渲染。
- 客户端自行计算伤害值。
- 自动 Respawn。

这些能力属于完整 Vanilla 等价或独立 automation action。
初版不能通过“等等”自动加入这些能力。

### 最小测试组

只增加一组 Plugin 物理测试。
该测试组使用固定世界夹具和固定 Packet 输入。

测试组包含四个场景：

1. 外部速度场景覆盖普通地面、墙和平台边缘。
2. 爆炸场景验证速度累加和更新后世界碰撞。
3. 水场景验证流体阻力、浮力和流向。
4. 死亡场景验证状态更新并停止普通移动发送。

投射物不增加独立测试。
它与普通攻击使用同一个 `ClientboundSetEntityMotionPacket` 路径。

每个场景只检查可观察结果：

- 玩家位置随 tick 连续变化。
- 玩家不穿过世界夹具的碰撞体。
- `onGround` 和 `horizontalCollision` 在正确边界变化。
- 发出的移动 Packet 与当前物理状态一致。
- 死亡后不再发出普通移动 Packet。

测试不检查类数量、字段布局或私有方法。
测试不要求逐 tick 完全匹配 Vanilla 浮点值。

### 设计约束

Packet 状态更新和物理 tick 必须在同一后端 event loop 中运行。
这条约束保证 Packet 顺序和玩家状态顺序一致。

未知 Chunk 不能按空气处理。
物理循环应暂停相关移动，直到碰撞查询所需的 Chunk 可用。

服务端负责伤害、死亡和位置纠正。
Plugin 负责客户端外部速度、世界碰撞和移动回报。

Patch 只提供 Packet Event、Packet 发送和登出取消接口。
本研究没有产生新的 Patch 接口要求。

## Files Found

- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/vanilla-damage-physics.md`: 说明 Minecraft 26.2 的伤害、击退、碰撞和移动闭环。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/mcprotocollib-world-packets.md`: 列出世界、实体、生命和物理所需 Packet。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/research/bot-physics-world.md`: 比较 Mineflayer、MCC 和轻量 AFK 工具的物理边界。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/prd.md`: 保存当前产品范围和验收要求。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/design.md`: 保存当前 Patch、Plugin 和 Shadow 设计。
- `.trellis/tasks/08-11-automation-shadow-infrastructure/implement.md`: 保存当前实施顺序和最小验证边界。
- `.trellis/spec/backend/velocity-plugin.md`: 规定 Patch 与 Plugin 的职责边界。
- `.trellis/spec/language/java.md`: 规定 Java 实现约束。

## Code Patterns

- `net/minecraft/server/level/ServerEntity.java:221-224`: 服务端向玩家本人发送受击速度。
- `net/minecraft/client/multiplayer/ClientPacketListener.java:624-629`: 客户端把玩家速度写入实体状态。
- `net/minecraft/client/multiplayer/ClientPacketListener.java:1353-1370`: 客户端累加爆炸击退。
- `net/minecraft/client/player/LocalPlayer.java:264-299`: 客户端选择并定期发送移动 Packet。
- `net/minecraft/world/entity/Entity.java:712-820`: 实体移动解析方块碰撞和落地状态。
- `net/minecraft/world/entity/LivingEntity.java:2402-2534`: 玩家物理应用重力、阻力和流体。
- `net/minecraft/server/network/ServerGamePacketListenerImpl.java:1050-1177`: 服务端校验并接收玩家移动。

## External References

- [Mineflayer physics Plugin](https://github.com/PrismarineJS/mineflayer/blob/a89e76b7a45e790247be77b5c18e155efd89315d/lib/plugins/physics.js): 提供 20 TPS 物理和移动发送参考。
- [prismarine-physics](https://github.com/PrismarineJS/prismarine-physics/blob/a5353a922f1dee075aa797cb53be31919f9e1f46/index.js): 提供方块碰撞和流体参考。
- [MCC PlayerPhysics](https://github.com/MCCTeam/Minecraft-Console-Client/blob/d50e90d8600f28ad8a66f713317aed05c1fc885a/MinecraftClient/Physics/PlayerPhysics.cs): 提供较小的无头玩家物理参考。

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`
- `.trellis/spec/language/java.md`

## Caveats / Not Found

- 当前规划仍把世界、实体、物理和周期移动排除在初版外。主会话必须更新规划。
- 没有成熟的 Java 物理库可以直接配合 MCProtocolLib 26.2。
- Minecraft 26.2 的方块碰撞数据来源仍需单独确定。
- 服务端 Plugin 和反作弊可以改变移动校验。
- 初版不自动 Respawn。

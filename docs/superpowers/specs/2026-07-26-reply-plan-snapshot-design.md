# 群聊回复计划与轮次快照设计

## 背景

当前 `GroupReplyPlanItem` 同时承担顺序配置和执行状态，`GroupChatReplyStep`
又直接引用可修改、可删除的 PlanItem。这导致四类问题：

- 普通群聊的计划执行一次后全部变为 `completed`，下一轮无法直接复用。
- 修改计划会删除旧 PlanItem，撤回旧 Turn 时无法可靠恢复对应状态。
- 模型调用准备阶段异常时，PlanItem、ReplyStep 或消息可能永久停留在运行状态。
- 读档会重建默认 Plan，丢失探索顺序、战斗顺序和战斗结束后的探索恢复关系。

本设计将“下一轮准备如何回复”和“某一轮实际上如何执行”彻底分开。

## 目标

- Plan 只保存当前可复用的回复顺序，不保存执行状态。
- 用户发送消息时，将 Plan 固化为本轮不可变执行快照，然后开始模型调用。
- 普通群聊、探索场景和战斗共用同一套 Plan 与 Turn 执行机制。
- 撤回消息只回滚已发生的 Turn，不修改 Plan。
- 世界存档保存所有未结束群聊的 Plan，读档时恢复探索或战斗顺序。
- 不允许在存在未完成 Turn 时存档。
- 保持第一版简洁，不增加独立的 PlanSnapshot 主表和明细表。

## 非目标

- 不支持战斗中再次嵌套战斗。
- 不支持对执行到一半的流式模型调用进行存档或断点续跑。
- 不在本次改动中实现新的战斗系统；战斗仍然只是 `COMBAT` 来源的回复计划。
- 不改变普通群聊最多连续撤回三轮的现有规则。

## 核心职责

### GroupReplyPlan

`GroupReplyPlan` 是当前可编辑、可复用的顺序配置：

- `conversationId`
- `source`：`USER`、`SCENE` 或 `COMBAT`
- `contextId`：场景或战斗标识
- `resumePlanId`：战斗结束后恢复的探索 Plan
- 分组和角色顺序

`GroupReplyPlanItem` 删除 `status`。Plan 和 PlanItem 都不记录
`pending/running/completed`，也不随 Turn 的完成、失败或撤回而变化。

Plan 中的有序分组是一条待执行队列，只有第一个分组是当前分组：

- Scene Plan 的一个分组表示一个“场景—角色顺序”。
- Combat Plan 的一个分组表示一轮战斗。
- USER Plan 第一版只使用一个默认分组。

一次 Turn 只复制并执行当前分组。同一场景或同一战斗轮可以使用当前分组创建任意多个
Turn；Turn 完成不会自动推进分组。只有场景或战斗控制器明确确认当前分组结束时，
才调用 Plan 的 `advanceGroup` 删除第一个分组，使下一个分组成为当前分组。
这是显式规划变更，不是模型执行状态。

进入战斗时创建 Combat Plan，并将当前探索 Plan 记录为 `resumePlan`。探索 Plan
及其尚未推进的当前分组保持不变，战斗结束后可以准确恢复。

### GroupChatTurn

`GroupChatTurn` 同时作为一次用户轮和该轮计划快照的头部。现有 `policy` 字段重命名为
`planSource`，并保存：

- `planSource`
- `planContextId`
- Turn 自身执行状态

不要求保存原 Plan 的数据库主键。Plan 后续可能被编辑或删除，历史 Turn 必须保持自包含。

### GroupChatReplyStep

发送消息时，为本轮所有回复项预创建 ReplyStep，并复制：

- `groupKey`
- `groupName`
- `groupOrder`
- `itemOrder`
- `speakerType`
- `speakerId`
- `stepNo`
- `actionType`

ReplyStep 既是不可变的顺序快照项，也是模型调用的执行单元。只有执行字段可以变化：

- `status`
- `outputMessageId`
- `errorMessage`

删除 `planItemId`。执行、异常恢复和撤回都不再查询或修改 PlanItem。

## 数据结构调整

### group_reply_plan_item

- 删除 `status`。
- 保留分组、排序和角色字段。

### group_chat_turn

- 将现有 `policy` 重命名为 `plan_source`。
- 新增 `plan_context_id`。
- 保留现有 Turn 状态、幂等请求 ID 和撤回版本字段。

### group_chat_reply_step

- 删除 `plan_item_id`。
- 新增 `group_key`、`group_name`、`group_order`、`item_order`。
- 保留执行状态、输出消息、错误信息和角色字段。

第一版不新增 `plan_snapshot` 或 `plan_snapshot_item` 表。Turn 和预创建的 ReplyStep
已经完整表达一份轮次快照。

## 发送与执行流程

发送消息时持有群聊锁：

1. 校验会话、幂等请求和当前 Plan。
2. 读取 Plan 的第一个分组，按其中的角色顺序生成本轮 Action。
3. 在单个数据库事务内插入用户消息、Turn 和全部 ReplyStep 快照。
4. 事务提交后，按 `stepNo` 串行执行 ReplyStep。
5. 每个 Step 在模型调用准备完成后，再原子地进入 `running` 并创建流式消息。
6. 模型调用完成、失败或取消时，原子更新 Step、消息及其工具副作用。
7. 所有 Step 结束后更新 Turn 最终状态。

整个执行过程只读取 Turn 和 ReplyStep 快照。即使用户之后修改 Plan，也不会影响正在执行
或已经完成的 Turn。

## 状态与异常恢复

Turn 状态：

```text
pending -> running -> completed
                   -> failed
                   -> cancelled
completed/failed/cancelled -> withdrawn
```

ReplyStep 状态：

```text
pending -> running -> completed
                   -> failed
                   -> cancelled
pending            -> cancelled
```

模型上下文、提示词或工具配置准备失败，也必须进入统一的 Step 失败收尾逻辑。一个 Step
失败或客户端取消后，尚未执行的后续 Step 统一标记为 `cancelled`，Turn 进入对应终态。

取得群聊锁后执行惰性恢复检查。如果数据库中存在没有锁持有者的非终态 Turn：

- 将 `streaming` 消息标记为 `failed`；
- 将 `running` Step 标记为 `failed`；
- 将剩余 `pending` Step 标记为 `cancelled`；
- 将 Turn 标记为 `failed`。

Plan 不参与异常恢复，因此不会因模型调用失败而失效。

## 撤回

普通群聊撤回一整个 Turn，仍保留最多连续撤回三轮的限制。撤回负责：

- 标记或删除该 Turn 的消息；
- 回滚工具调用和好感变化；
- 清理相应向量数据及话题边界；
- 将 Turn 标记为 `withdrawn`。

撤回不读取 ReplyStep 对应的 PlanItem，也不修改当前 Plan。跑团模式继续禁止撤回。

## 世界存档

`UserWorldSaveSnapshotDTO` 新增 Plan 配置：

```text
conversationPlans[]
  conversationId
  activePlan
    source
    contextId
    groups[]
      key
      name
      order
      items[]
        order
        actorType
        actorId
    resumePlan
      ...
```

存档 JSON 不保存 Plan、PlanItem 的数据库主键、时间戳或执行状态。
分组数组只保存存档时仍在 Plan 中的当前分组和后续分组；已经通过 `advanceGroup`
移除的分组不会恢复。

存档时：

1. 获取该用户世界所有未结束群聊的锁。
2. 再次查询并确认不存在 `pending` 或 `running` Turn。
3. 保存每个未结束群聊的 Active Plan。
4. 如果当前处于战斗，同时保存一层 Resume Plan。
5. 将结构化配置写入现有 `user_world_save.snapshot` JSON。

锁用于阻止存档检查之后又开始新的 Turn。即使进程异常留下非终态 Turn，数据库检查也会
拒绝存档。

读档时：

1. 获取相关群聊锁并确认没有非终态 Turn。
2. 按现有规则回滚存档点之后的消息、Turn、ReplyStep 和派生数据。
3. 清除这些群聊当前的 Plan 和 PlanItem。
4. 对每个 Plan 快照先重建 Resume Plan，再重建 Active Plan。
5. 使用新生成的数据库主键连接 `resumePlanId`。
6. 更新 `group_conversation.activeReplyPlanId`。
7. 对存档时未结束、之后被关闭的群聊，恢复为 `ACTIVE` 并清除 `closedAt`。

由此可得到以下行为：

- 探索中存档：读档后恢复当前场景及其角色顺序。
- 战斗中存档：读档后恢复战斗顺序，同时保留战斗结束后要恢复的探索顺序。
- 同一场景内的多个 Turn：各自保留当时的 ReplyStep 顺序快照。
- 读档不会根据群聊成员重新生成默认 Plan。

第一版最多保存 Active Plan 和一层 Resume Plan。若检测到更深的引用链，存档直接拒绝，
避免静默丢失状态。

## 接口与返回

Plan CRUD 接口只返回规划配置，不再返回执行状态。

Scene/Combat 调度方通过 `advanceGroup` 显式推进当前分组。该操作持有群聊锁，只修改
Plan 和 PlanItem，不读取 Turn 状态，也不会由 Turn 完成自动触发。

发送消息后的流式事件继续返回 `turnId` 和 `replyStepId`，并从 ReplyStep 快照返回
分组、顺序和角色信息。前端展示的 `pending/running/completed` 来自本轮 ReplyStep，
而不是 PlanItem。

删除普通或 Scene Plan 后恢复默认 USER Plan；删除 Combat Plan 后恢复 Resume Plan。
这些操作只影响下一次发送消息。

## 约束与校验

- Plan 中的角色必须是当前群聊已启用成员。
- `USER` Plan 只允许用于普通群聊。
- `SCENE`、`COMBAT` Plan 只允许用于跑团群聊，且 `contextId` 必填。
- 当前分组最多包含 12 个项目；整个 Plan 仍最多包含 200 个项目。
- 同一分组中不能重复出现相同 `(actorType, actorId)`。
- Turn 非终态时不能修改 Plan、关闭群聊、存档或读档。

## 测试范围

- 同一个 USER Plan 连续创建两个 Turn，两轮顺序相同且 Plan 不发生变化。
- 修改 Plan 时，已经创建的 Turn/ReplyStep 快照不发生变化。
- 撤回普通群聊 Turn 后，Plan 不发生变化。
- 同一 Scene Plan 连续创建多个 Turn，场景顺序保持一致。
- Scene Turn 完成不会自动推进；显式 `advanceGroup` 后才使用下一场景分组。
- Combat Turn 完成不会自动推进；显式 `advanceGroup` 后才使用下一轮分组。
- 进入战斗后创建 Combat Turn，结束战斗后继续使用原 Scene Plan。
- 探索中存档并读档，恢复 Scene Plan。
- 战斗中存档并读档，同时恢复 Combat Plan 和 Resume Scene Plan。
- 存在非终态 Turn 时拒绝存档和读档。
- 模型准备阶段异常时，不留下 `running` Step 或 `streaming` 消息。
- 进程中断后的首次加锁能够将遗留非终态 Turn 收敛为失败终态。

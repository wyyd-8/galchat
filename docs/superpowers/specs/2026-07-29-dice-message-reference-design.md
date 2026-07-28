# 掷骰消息引用设计

## 目标

让 `group_chat_message` 中的 `dice_roll` 消息直接描述其公开掷骰事件，不再通过
`reply_step_id -> group_chat_tool_call` 间接推断需要展示的掷骰概要。

同时维持 `dice_roll_summary.total_result` 的逐轮换行格式，使前端与模型上下文都能按
换行拆分已完成轮次的语义结果。

## 消息格式

`message_kind = "dice_roll"` 时，`content` 保存 JSON：

```json
{
  "summaryId": 501,
  "roundNos": [2, 3]
}
```

- `summaryId` 是 `dice_roll_summary.id`。
- `roundNos` 是本条消息所代表的轮次，去重后按升序保存。
- `roundNos` 不允许为空。
- 普通对话消息的 `content` 格式不变。

一条掷骰消息对应一次公开掷骰事件。一次 KP 工具调用可能生成一个或多个连续或非连续
轮次，因此使用数组，不拆分成多条消息。

## 写入流程

### KP 工具调用

KP 工具返回 `KpDiceToolResult` 后，从 `summary.id` 和本次返回的
`results[].roundNo` 构建消息内容。消息继续使用当前 ReplyStep 已分配的
`sequence_no`。

同一概要的后续主动工具调用会创建新的消息，例如：

```text
sequence=10 -> {"summaryId":100,"roundNos":[1]}
sequence=18 -> {"summaryId":100,"roundNos":[2]}
```

### 玩家掷骰后的自动追加

玩家完成待掷结果后，规则可能自动追加临时疯狂或重伤 CON 检定轮。此时不创建新消息：

1. 查询当前 `conversation_id` 下 `message_kind = "dice_roll"` 的最近一条消息。
2. 解析其 `content`。
3. 校验消息的 `summaryId` 等于当前正在结算的概要 ID。
4. 将自动创建结果的 `roundNo` 合并进 `roundNos`，去重并升序排序。
5. 更新该消息的 `content` 和 `updated_at`。

如果没有最近的掷骰消息、内容无法解析，或概要 ID 不一致，则事务失败，避免把自动轮次
追加到另一场掷骰。

## 读取流程

### 历史接口

历史接口直接返回消息保存的掷骰引用，不再按 `reply_step_id` 批量查找
`dice_roll_summary_id`。`reply_step_id` 仅保留执行审计、工具记录、好感记录和整轮撤回
等职责。

### 模型上下文

上下文组装器解析 `dice_roll` 消息的 `summaryId + roundNos`，查询对应概要和指定轮次的
结果，再生成公开的 `<dice-roll>` 内容。不同消息即使引用同一个概要，也只组装各自声明
的轮次，不会重复完整概要。

`group_chat_tool_call.dice_roll_summary_id` 继续用于工具审计和定位最近一次兼容掷骰，不再
作为历史消息或公开上下文的内容来源。

## `total_result`

`CocDiceSummaryFormatter.rebuildTotalResult` 已经按轮次分组、按轮号排序，并使用换行连接
不同已完成轮次：

```text
第一轮语义结果
第二轮语义结果
```

现有实现与测试已经满足本次要求。本次只补充回归验证，不修改同一轮内部使用分号连接
多个角色结果的规则。

## 错误处理与兼容性

- 不兼容旧的 `dice_roll` 空内容；项目已明确不考虑旧数据。
- 无法构造有效 `summaryId + roundNos` 时，不把消息标记为完成。
- 自动追加更新与掷骰结算处于同一事务，避免概要已经追加轮次但消息引用未更新。

## 测试范围

- KP 单轮掷骰将概要 ID 和单个轮号写入消息内容。
- 一次工具调用生成多轮时，将全部轮号去重、排序后写入。
- 历史查询不再调用基于 ReplyStep 的概要映射。
- 同一概要的两条消息分别保留自己的轮次。
- 玩家掷骰自动生成新轮时，更新最近的同概要掷骰消息。
- 最近消息概要不匹配时回滚并报错。
- 上下文只组装消息声明的轮次。
- `total_result` 的不同轮次继续以单个换行分隔。

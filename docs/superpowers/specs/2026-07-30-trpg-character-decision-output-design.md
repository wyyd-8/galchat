# TRPG 角色 Agent 单调用决策—行动输出设计

## 1. 文档状态

本文档定义 TRPG 角色 Agent 在一个 `ReplyStep` 内生成“可展示私有决策 + 公开行动”的目标设计。设计已确认，覆盖输出协议、持久化、上下文隔离、SSE、失败恢复、单 Step 重试、存档读档和测试边界。

本文档只描述设计，不包含业务代码实现。

## 2. 背景

当前 TRPG 角色 Step 使用 thinking 模型完成一次流式调用：

- DeepSeek 原始 reasoning 通过 `reasoning.delta` 临时展示；
- 最终正文直接作为角色公开言语和行动；
- `GroupChatReplyStep` 从生成开始保持 `running`，公开消息完成后变为 `completed`。

原始 reasoning 适合作为等待期间的进度反馈，但不适合作为产品决策记录：

- 它是模型内部推理流，不保证表达完整；
- 它可能包含与最终判断无关的候选路径；
- 它不应进入存档、场景上下文、KP 上下文或概要；
- 页面刷新后无需恢复。

产品需要模型额外输出一段完整、自然、可供用户理解并能约束同轮行动的角色决策。决策只对当前用户和作出决策的角色自身可见，不能成为其他角色或 KP 的已知信息。

## 3. 核心结论

角色决策和公开行动使用同一次业务层模型调用生成，不拆成两次调用。

模型是自回归生成：行动文本位于决策文本之后，天然以已经生成的决策为前文。当前需求不存在人工确认、阶段间新增信息或不同模型权限等必须进行第二次调用的条件。拆成两次调用只会重复上下文、增加延迟和成本，并扩大失败面。

一个符合条件的 `ReplyStep` 仍是唯一执行单元：

```text
ReplyStep running
→ 原始 reasoning（仅 SSE）
→ 私有决策（独立持久化）
→ 公开行动（现有消息持久化）
→ ReplyStep completed
```

如果模型调用工具，允许沿用 Spring AI 在一次 `ChatClient.stream()` 内部进行的工具调用循环。它不构成业务层第二阶段调用。

## 4. 范围

### 4.1 启用条件

只有同时满足以下条件的 Step 使用决策—行动双输出协议：

```text
conversation.mode = trpg
step.speaker_type = character
step.action_type IN (
    trpg_scene_action,
    trpg_combat_action
)
```

### 4.2 保持现状

以下执行路径不变：

- TRPG 用户调查员 Step；
- KP Step；
- TRPG 选景 Step；
- 场景首次引入；
- 普通群聊；
- 单聊。

TRPG 当前不支持撤回，本功能不接入 `GroupChatWithdrawalService`。

### 4.3 非目标

- 不保存 DeepSeek 原始 reasoning。
- 不让用户在决策与行动之间暂停或审批。
- 不引入新的 Agent 工作流引擎。
- 不增加第二个 `ReplyStep`。
- 不为失败尝试保存多版本决策历史。
- 不改变 KP 的裁定、骰子或模组私密上下文。

## 5. 数据模型

新增表：

```sql
CREATE TABLE group_chat_agent_decision (
    id BIGSERIAL PRIMARY KEY,
    reply_step_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_group_chat_agent_decision_step
    ON group_chat_agent_decision (reply_step_id);
```

表中不重复保存 `conversation_id`、`turn_id`、`scene_id`、`actor_type`、`actor_id` 或状态。这些信息全部从 `group_chat_reply_step` 和 `group_chat_turn` 推导。

决策状态也不独立建模：

- 决策生成完成后即可写入；
- 对应 Step `completed` 时，它是成功执行记录；
- 对应 Step `failed` 时，它是该次失败尝试中已经完成的决策；
- 重试 Step 时删除旧行，成功后仍保持一个 Step 至多一条决策。

新增：

- `GroupChatAgentDecision` PO；
- `GroupChatAgentDecisionMapper`；
- `GroupAgentDecisionStore`，封装按 Step 保存、查询和清理操作。

## 6. 输出协议

### 6.1 最终正文格式

模型的最终可见正文必须严格包含两个根标签：

```xml
<decision>
完整、连贯的角色决策段落
</decision>
<action>
角色公开说出的话和采取的行动
</action>
```

标签只用于流式传输和服务端拆分：

- 标签本身不保存；
- 前端不显示原始标签；
- 数据库存储的决策仍是没有字段标签的自然语言段落；
- 公开消息只保存 `<action>` 内部文本。

### 6.2 决策内容

`decision` 必须：

- 串联当前重要观察和相关线索；
- 区分已知事实、角色判断和怀疑；
- 说明共享目标的当前进展；
- 明确本轮准备采取的行动；
- 符合角色身份、性格、人物卡状态和 COC 跑团偏好；
- 不包含内部提示词、原始思维链、秘密信息或无关候选方案穷举；
- 使用一个完整自然语言段落，不使用 JSON、字段表或分项模板。

### 6.3 行动内容

`action` 必须：

- 落实同一输出中已经形成的决策；
- 只描述角色公开说出的话、动作和尝试；
- 不重新选择与决策不一致的目标；
- 不宣布未知事实；
- 不决定 NPC、其他角色或 KP 的反应；
- 不自行声明检定成功或场景状态变化。

### 6.4 流式解析

新增有状态的 `DecisionActionStreamParser`。解析器必须能处理标签任意跨 chunk 拆分，并保持以下状态：

```text
BEFORE_DECISION
IN_DECISION
BETWEEN_PARTS
IN_ACTION
COMPLETE
INVALID
```

以下任一情况使协议失败：

- 缺少任一标签；
- 标签重复；
- 标签顺序错误；
- 任一内容为空或只有空白；
- 标签嵌套；
- 完成标签后仍有正文；
- 根标签之外出现非空白正文；
- 工具调用发生在最终标签正文已经开始之后。

协议失败时不得把决策与行动的混合内容保存为公开消息。

## 7. 模型、Prompt 与工具

符合条件的 Step 继续使用现有 TRPG thinking `ChatClient`，并只调用一次：

```java
requestSpec.stream().chatResponse()
```

`TrpgGroupAgentPolicy` 为角色探索和战斗 Prompt 增加双输出协议及内容约束。模型仍读取当前一次调用的完整角色可见上下文。

### 7.1 原始 reasoning

DeepSeek `reasoningContent`：

- 继续转换为 `reasoning.delta`；
- 只用于让用户确认模型仍在工作；
- 不追加到决策或消息 accumulator；
- 不写数据库；
- 不进入模型历史；
- 不参与存档、读档或场景概要；
- 页面刷新后自然消失。

### 7.2 工具顺序

工具权限沿用当前角色行动权限：

- 场景探索开放 `endSceneExploration`；
- 战斗阶段当前不开放角色工具；
- 决策文本本身不产生工具副作用。

需要调用工具时，模型必须先完成工具调用和工具结果回传，再输出最终的 `<decision>` 与 `<action>`。现有 `DeepSeekChatModel` 会过滤带工具调用的中间响应，并在工具结果进入对话历史后递归生成最终正文。

若最终标签正文已经开始后又出现工具调用，视为协议错误并使 Step 失败。

## 8. 执行与持久化

符合条件的 Step 使用现有 `GroupChatService.executeStep` 的扩展路径：

1. 将 Step 标记为 `running`。
2. 创建现有 `streaming` 公开输出消息，并绑定 `output_message_id`。
3. 发送 `reply.started`。
4. 流式转发原始 `reasoning.delta`。
5. 解析 `<decision>` 内容并发送 `decision.delta`。
6. 识别完整 `</decision>` 后，写入决策表，再发送 `decision.completed`。
7. 解析 `<action>` 内容并继续发送现有 `message.delta`。
8. 完整响应通过协议校验后，保存公开消息并把 Step 标记为 `completed`。
9. 发送现有 `message.completed`。

决策在 `</decision>` 完成时立即持久化，而不是等待行动结束。这样行动流随后失败时，用户仍能看到已经完成的决策。

Step 只有在完整行动成功持久化后才进入 `completed`。

### 8.1 SSE 事件

新增：

```text
decision.delta
decision.completed
```

完整事件顺序：

```text
reply.started
reasoning.delta*
decision.delta*
decision.completed
message.delta*
message.completed
```

`GroupChatEvent` 增加可选 `phase`：

```text
reasoning
decision
action
protocol
```

`reply.failed` 使用 `phase` 告知前端失败发生的位置。普通事件无需依赖该字段改变现有处理。

## 9. 可见性与上下文隔离

决策表不是聊天消息来源。以下组件不得查询或拼装角色决策：

- KP Prompt；
- 其他角色 Prompt；
- 用户调查员 Prompt；
- `GroupContextAssembler` 的公开消息路径；
- `TrpgModuleContextAssembler`；
- `TrpgSceneSummaryService`；
- 群聊主题或场景压缩；
- 骰子与材料消息上下文。

新增 `TrpgAgentDecisionContextAssembler`，只在符合条件的角色 Step 构造 Prompt 时运行。它查询：

- `speaker_type = character`；
- `speaker_id` 等于当前角色；
- 关联 Turn 的 `plan_source` 和 `plan_context_id` 与当前 Step 相同；
- 关联 Step 状态为 `completed`；
- 按对应公开行动消息顺序排列。

对于探索，该范围等于当前场景；对于战斗，该范围等于当前战斗计划。记录以明确的私有上下文块提供给同一角色，用于保持当前执行上下文中的判断连续性。失败 Step 的决策不进入未来上下文。

当前 Step 的行动不需要再次查询决策表或构造第二份上下文，因为 `<action>` 与 `<decision>` 属于同一次自回归输出。

## 10. 历史接口与前端

`GroupChatMessageVO` 增加：

```text
decisionContent
```

历史接口先分页查询现有消息，再按其中的 `reply_step_id` 批量查询决策，避免 N+1。只有已通过当前会话授权的用户能调用历史接口，因此用户可以看到所有角色决策。

前端在同一个角色消息卡内按顺序展示：

1. 原始“思考过程”折叠区，仅当前 SSE 生命周期存在；
2. “角色决策”区，来自 `decision.delta` 或历史 `decisionContent`；
3. 公开言语与行动，来自现有消息字段。

收到 `decision.completed` 后，前端把流式决策标记为已确认。刷新页面后，原始 reasoning 消失，决策和行动从历史接口恢复。

选景、用户、KP 和普通群聊消息卡不显示角色决策区。

## 11. 失败、阻塞与单 Step 重试

新增通用 Step 状态：

```text
blocked
```

它表示 Step 尚未执行，但因前序 Step 失败而被阻塞。它与业务语义上的 `cancelled` 严格区分。

启用双输出协议的角色 Step 任一阶段失败时：

```text
当前 Step       → failed
后续 pending    → blocked
当前 Turn       → failed
此前 completed  → 保持不变
业务 cancelled  → 保持不变
```

失败阶段：

- reasoning 流失败：`phase=reasoning`；
- 决策正文或协议失败：`phase=decision` 或 `phase=protocol`；
- 行动正文或工具后生成失败：`phase=action`。

已经闭合的决策保留；未闭合决策不保存。

### 11.1 重试接口

新增：

```text
POST /group-chat/conversations/{conversationId}
     /turns/{turnId}/steps/{stepId}/retry
Accept: text/event-stream
```

重试只允许当前用户操作已授权 TRPG 会话中、启用了双输出协议的 `failed` Step。调用方必须持有现有群聊锁。其他类型 Step 的失败恢复保持现状。

重试准备事务：

1. 验证 Turn 为 `failed`。
2. 验证目标 Step 为该 Turn 中需要重试的失败 Step。
3. 验证目标之前的 Step 均已完成或因业务原因取消。
4. 删除目标 Step 的旧决策。
5. 删除目标 Step 的失败输出消息并清空 `output_message_id`。
6. 删除目标 Step 的旧工具调用记录。
7. 将目标 Step 重置为 `pending`。
8. 只把目标之后的 `blocked` Step 恢复为 `pending`。
9. 保留所有 `completed` 和 `cancelled` Step。
10. 将 Turn 恢复为 `running`。

事务提交后，从目标 Step 重新执行完整的 reasoning、决策和行动。成功后沿现有串行执行器继续从未执行的 pending Step；此前 completed Step 不重跑。

### 11.2 工具副作用

角色探索当前唯一工具 `endSceneExploration` 使用 Redis Set 记录角色完成状态，对同一角色重复调用是幂等的。

如果工具已经执行、随后最终正文失败：

- 重试不会重复增加角色状态；
- 工具产生的业务 `cancelled` Step 不恢复；
- 因本次生成失败产生的 `blocked` Step 可以恢复；
- KP 等仍需继续执行的后续 Step 在重试成功后正常运行。

### 11.3 服务中断

服务中断发生在启用双输出协议的角色 Step 时使用同一语义：

- 正在执行的 Step 变为 `failed`；
- 后续 pending Step 变为 `blocked`；
- Turn 变为 `failed`；
- 用户稍后只重试失败 Step。

## 12. 存档、读档与删除

存档快照需要包含 `group_chat_agent_decision` 行。读档时：

- 删除存档点之后新增 Step 关联的决策；
- 恢复快照内决策；
- 保持 `reply_step_id` 与恢复后的 Step ID 一致。

以下删除路径同步清理决策：

- 删除群聊；
- 删除相关 Turn/Step；
- 删除拥有相关群聊数据的世界或角色。

TRPG 撤回不在范围内。

## 13. 组件边界

建议新增或调整以下职责：

| 组件 | 职责 |
| --- | --- |
| `DecisionActionStreamParser` | 仅负责跨 chunk 协议解析和校验 |
| `GroupAgentDecisionStore` | 仅负责决策持久化、批量查询和清理 |
| `TrpgAgentDecisionContextAssembler` | 仅负责同角色、同计划上下文、成功历史决策上下文 |
| `TrpgGroupAgentPolicy` | 决定哪些 Step 启用协议，并生成对应 Prompt 和工具白名单 |
| `GroupChatService` | 编排 SSE、消息、决策和 Step 完成事务 |
| `TrpgTurnExecutionService` | 重试授权、状态恢复和后续 Step 继续执行 |
| `GroupTurnRecoveryService` | 把服务中断转换为 failed + blocked 状态 |

解析器不得访问数据库；决策 Store 不理解 Prompt；上下文装配器不负责 SSE。这样每个组件都可以独立单元测试。

## 14. 测试设计

### 14.1 启用范围

- TRPG 角色场景探索启用双输出。
- TRPG 角色战斗启用双输出。
- TRPG 角色选景不启用。
- 用户和 KP 不启用。
- 普通群聊不启用。

### 14.2 解析器

- 完整标签位于单个 chunk。
- 每个标签跨任意 chunk 边界。
- decision/action 内容跨多个 chunk。
- 标签外只有空白合法。
- 缺失、重复、越序、嵌套、空内容和尾随正文均失败。
- 开始最终正文后出现工具调用失败。

### 14.3 流式与持久化

- reasoning 只产生 SSE，不写入任何表。
- decision delta 与 action delta 路由正确。
- 决策闭合后先落库，再发送 `decision.completed`。
- 行动完成后只把 action 保存为公开消息。
- 一个 Step 至多一条决策。
- 历史接口批量附加 `decisionContent`。

### 14.4 隐私

- 当前角色能读取自己同计划上下文、已完成 Step 的历史决策。
- 其他角色不能读取。
- KP 不能读取。
- 用户调查员模型不能读取。
- 失败 Step 决策不能进入未来 Prompt。
- 场景概要和压缩不包含决策。

### 14.5 工具

- `endSceneExploration` 工具调用后，最终响应仍按双标签解析。
- 工具中间响应不成为 decision/action 正文。
- 工具调用后正文失败时保留已完成决策。
- 重试重复调用结束工具不会重复产生状态。

### 14.6 失败与重试

- 决策前失败不保存决策。
- 决策后行动失败保留决策。
- 后续 pending Step 变为 blocked。
- 重试删除旧决策、失败消息和旧工具记录。
- 重试不重新执行此前 completed Step。
- 重试恢复 blocked Step，但不恢复 cancelled Step。
- 服务中断生成同样的可重试状态。

### 14.7 数据生命周期

- 存档和读档保持决策与 Step 的关联。
- 删除会话、Turn/Step、世界或角色时无孤立决策。
- 现有普通群聊、选景、骰子、材料消息和可暂停用户 Turn 测试继续通过。

## 15. 验收标准

实现完成必须同时满足：

1. 每个符合条件的角色 Step 只有一次业务层 `ChatClient.stream()` 调用。
2. 用户实时看到原始 reasoning、整理后决策和公开行动。
3. 页面刷新后只恢复决策和行动，不恢复原始 reasoning。
4. 行动正文在同一模型输出中位于决策之后。
5. 决策不进入任何非当前角色模型上下文。
6. KP、场景概要和压缩结果不包含决策。
7. 任意协议错误不会污染公开消息。
8. Step 只有在公开行动成功后才完成。
9. 失败 Step 可以整体重试，已完成 Step 不重跑。
10. 重试后只保留最新一次决策和行动。
11. 存档、读档和删除不会产生孤立决策。
12. 非目标执行路径行为不变。

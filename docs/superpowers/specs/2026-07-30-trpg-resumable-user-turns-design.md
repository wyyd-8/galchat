# TRPG 用户调查员可暂停行动轮设计

## 1. 文档状态

本文档定义用户调查员融入现有 TRPG `ReplyPlan → Turn → ReplyStep` 体系的目标设计。设计已确认，覆盖选景、探索、战斗、结束探索、场景首次引入、上下文裁剪、失败恢复和前端接口。

本文档只描述设计，不包含业务代码实现。

## 2. 背景与问题

当前 TRPG 群聊把一次用户消息请求同时当作：

1. 新 Turn 的触发消息；
2. 整个 Turn 中所有 Agent/KP Step 的执行入口。

因此一次 SSE 请求会连续执行完整行动轮。用户消息本身不是 `GroupChatReplyStep`，用户调查员也不在 `GroupReplyPlanItem` 中。这造成：

- 用户调查员不能出现在选景、探索或战斗的明确行动顺序中；
- Turn 不能在轮到用户时暂停；
- 用户消息不能可靠绑定到自己的调查员卡和当前 Step；
- 用户不能通过后端状态结束自己的场景探索；
- 全体调查员判定只统计 Agent 调查员；
- 选景只支持 Agent 按地点名称调用工具，用户没有选择入口；
- 等待用户输入与服务中断无法区分。

## 3. 目标

### 3.1 核心行动轮

用户调查员与 Agent 调查员拥有一致的 Step 语义：

```text
前序 Step
→ 用户 Step 暂停
→ 用户提交输入并完成 Step
→ 后续 Agent Step
→ KP Step
→ Turn 完成
```

该机制适用于：

- 选景；
- 探索；
- 战斗。

没有用户 Step 的行动轮仍由前端显式调用“开始/继续行动轮”接口，并在一次 SSE 请求中执行到完成。

### 3.2 产品约束

- 每个跑团只允许一名真人调查员，即当前登录用户。
- 用户提交当前 Step 后，同一次 SSE 请求自动执行后续 Step，直到遇到下一个用户 Step 或 Turn 完成。
- 普通 `chat` 模式保持现状；可暂停 Turn 接口只用于 TRPG。
- 继续使用现有 `GroupReplyPlan`、`GroupChatTurn`、`GroupChatReplyStep` 和群聊锁，不引入独立工作流引擎。

## 4. 非目标

- 不支持多名真人用户共同控制不同调查员。
- 不从 KP 普通自然语言中解析选景 JSON。
- 不允许用户替 Agent 调查员完成 Step。
- 不把用户等待状态单独维护为 Redis 中的“伪 Step”。
- 不修改 KP 的模组私有上下文和裁定权限。
- 不为本功能重建普通群聊执行链。

## 5. 参与者身份

### 5.1 Actor 表示

Plan、Turn 和 Step 使用以下 Actor：

| 参与者 | `actorType` | `actorId` |
| --- | --- | --- |
| 真人调查员 | `user` | `coc_character.id`，且人物卡为 `PLAYER` |
| Agent 调查员 | `character` | 原世界角色模板 ID，即人物卡 `participantId` |
| KP | `kp` | `null` |

`actorType` 是 ID 解释的必要组成部分。任何缓存或集合不得只保存裸 Long ID。

### 5.2 参与者解析服务

新增 `TrpgParticipantService`，负责：

- 查找当前跑团唯一的 `PLAYER` 人物卡；
- 按 `group_chat_member.position` 返回启用的 Agent 调查员；
- 解析 Agent 原世界角色和对应的 COC 人物卡；
- 返回稳定的 `GroupActorRef` 和显示信息；
- 验证每位参与者都有合法人物卡；
- 为选景、场景 Plan、战斗 Plan 和全体结束判定提供统一参与者列表。

用户不写入 `group_chat_member`。该表继续表示原世界 Agent 角色成员，避免污染世界事件可见角色、普通群聊成员和现有角色 ID 语义。

### 5.3 用户人物卡要求

开始 TRPG Turn 前必须存在且只能存在一张：

```text
run_id = conversation.userWorldId
actor_type = PLAYER
participant_id IS NULL
```

缺失或存在多张时拒绝开始行动轮，并返回明确错误。Agent 对应人物卡缺失时同样拒绝创建相关 Step。

## 6. 持久化 Turn 状态机

### 6.1 状态

新增通用状态常量：

```text
waiting_input
```

合法状态组合：

| Turn 状态 | 当前 Step 状态 | 含义 |
| --- | --- | --- |
| `running` | `running` | 模型或后端正在执行 |
| `waiting_input` | `waiting_input` | 合法等待真人用户输入 |
| `completed` | 全部终态 | Turn 已完成 |
| `failed` | 至少一个失败 | Turn 执行失败 |
| `cancelled` | 未完成 Step 已取消 | 客户端取消或上游流程终止 |

同一 Turn 最多存在一个 `waiting_input` Step。

### 6.2 Turn 快照

`group_chat_turn` 增加：

```text
plan_id BIGINT NULL
```

创建 Turn 时记录准确的活动 Plan ID。选景 Turn 没有持久化 Plan，`plan_id` 可为空，`plan_source=SCENE_SELECTION`。

`plan_id` 用于：

- 判断某个 SCENE Plan 是否已经完成首次 KP 引入；
- 区分再次访问同一地点产生的新 Plan；
- 恢复时验证 Turn 与当前活动 Plan 未发生变化。

### 6.3 执行规则

新增可恢复的 Turn 执行服务，将“创建 Turn”和“从下一 Step 继续执行”分离：

1. 前端调用开始/继续接口。
2. 若不存在未完成 Turn，则解析当前 Plan 并一次性创建完整 Step 快照。
3. 按 `groupOrder → itemOrder → stepNo` 顺序执行。
4. Agent/KP Step 调用模型或工具，完成后继续。
5. 遇到用户 Step：
   - Step 改为 `waiting_input`；
   - Turn 改为 `waiting_input`；
   - 发送等待事件；
   - 正常结束 SSE；
   - 释放群聊锁。
6. 用户提交输入：
   - 原子校验并占用当前等待 Step；
   - 持久化绑定到该 Step 的用户消息或结构化动作；
   - 完成用户 Step；
   - Turn 恢复为 `running`；
   - 同一 SSE 请求继续执行后续 Step。
7. 无剩余 Step 时完成 Turn，并调用现有阶段完成逻辑。

### 6.4 锁与重复请求

- 创建、占用用户 Step 和恢复执行均持有现有会话锁。
- Step 状态更新必须带原状态条件，只有 `waiting_input → running/completed` 的首次更新成功。
- `clientRequestId` 在同一会话内保持幂等约束。
- 重复完成、提前提交、提交错误 Step 或替其他 Actor 提交均返回业务错误。

## 7. 对外接口

### 7.1 开始或继续行动轮

```http
POST /group-chat/conversations/{conversationId}/turns/start
Accept: text/event-stream

{
  "clientRequestId": "..."
}
```

行为：

- 无未完成 Turn：创建新 Turn 并执行；
- 当前 Turn 正在等待用户：不越过用户 Step，重新返回当前等待状态；
- 当前 Turn 正在生成：返回“正在执行”；
- 没有用户 Step：执行到 Turn 完成；
- 遇到用户 Step：执行到等待点后结束 SSE。

### 7.2 提交普通行动

```http
POST /group-chat/conversations/{conversationId}/turns/{turnId}/steps/{stepId}/message
Accept: text/event-stream

{
  "clientRequestId": "...",
  "content": "我检查餐桌下面是否藏着东西"
}
```

创建的消息必须包含：

```text
replyStepId = 当前用户 Step
speakerType = user
speakerId = PLAYER 人物卡 ID
visibility = public
```

### 7.3 提交选景编号

```http
POST /group-chat/conversations/{conversationId}/turns/{turnId}/steps/{stepId}/selection
Accept: text/event-stream

{
  "clientRequestId": "...",
  "optionNo": "2"
}
```

成功记录实际选择，完成用户 Step，并继续执行后续 Agent 选景 Step。

### 7.4 用户结束自己的探索

```http
POST /group-chat/conversations/{conversationId}/turns/{turnId}/steps/{stepId}/end-exploration
Accept: text/event-stream

{
  "clientRequestId": "..."
}
```

该接口只允许当前登录用户在自己的 `SCENE` 用户 Step 为 `waiting_input` 时调用。

接口成功后由后端生成规范公开消息，说明该用户调查员已经结束当前场景探索；前端不需要额外提交一条普通消息。

### 7.5 查询当前 Turn

```http
GET /group-chat/conversations/{conversationId}/turns/current
```

用于页面刷新、SSE 断开或重新进入页面后恢复 UI。返回：

- 当前 Turn ID、Plan、阶段和状态；
- 当前 Step；
- 是否等待用户；
- 等待输入类型；
- 场景名称；
- 选景编号 Map；
- 可继续使用的 `turnId` 和 `stepId`。

## 8. 选景设计

### 8.1 Step 顺序

选景 Turn 固定为：

```text
KP 打开地点选项
→ 用户调查员选择
→ 启用的 Agent 调查员按群成员顺序选择
```

用户先选择，使所有 Agent 都能看到用户结果；后续 Agent 还能看到前面 Agent 的选择。

### 8.2 KP 结构化工具

KP 只在 `ACTION_TRPG_SCENE_SELECTION` 阶段获得：

```text
openSceneSelection(locationNames)
```

约束：

- `@Tool(returnDirect = true)`；
- KP 只传准确地点名称列表，不传编号或地点 ID；
- 后端在当前模组中准确匹配；
- 空列表、重复名称和不存在的地点均报错；
- 按输入顺序生成从 `"1"` 开始的稳定编号；
- 同一选景 Turn 再次打开时，只允许完全相同的选项。

示例结果：

```json
{
  "1": "餐厅",
  "2": "客房",
  "3": "前台"
}
```

该工具只在选景阶段注入。探索和战斗阶段不可见。

因为工具为 `returnDirect`，KP 调用后立即结束当前模型 Step。公开选项消息和 SSE 结构化事件由后端生成，不要求模型继续输出文本。

### 8.3 Agent 选择工具

Agent 只在自己的 `ACTION_TRPG_SCENE_SELECTION` Step 获得：

```text
selectExplorationScene(optionNo)
```

约束：

- `@Tool(returnDirect = true)`；
- 只提交编号；
- 工具结果返回实际编号和地点；
- 调用后立即结束模型 Step；
- 探索和战斗阶段不提供该工具。

### 8.4 选项与选择存储

复用并扩展 `TrpgSceneSelectionStore`，Redis Key 绑定 Turn：

```text
trpg:group:scene-selection:{conversationId}:{turnId}:options
trpg:group:scene-selection:{conversationId}:{turnId}:choices
```

选项保存编号、地点 ID 和地点名。选择字段使用：

```text
user:{playerCardId}
character:{characterTemplateId}
```

TTL 保持 7 天。Turn 完成并生成 SCENE Plan 后清除本次选景状态。

若合法等待超过 TTL 或 Redis 状态丢失，恢复接口必须把当前选景 Turn 标记为失败并允许重新开始选景，不能保留无法继续的 `waiting_input` Turn。

### 8.5 单地点自动分配

若 KP 只打开一个地点：

1. 后端把用户和所有启用 Agent 调查员分配到该地点；
2. 取消其余选景 Step；
3. 不等待用户输入；
4. 当前 KP Step 完成后直接生成 SCENE Plan；
5. SSE 返回 `autoSelected=true`。

这适用于集合点、夜晚旅店等没有实际分流选择的阶段。

### 8.6 前序选择上下文

后续 Agent 选择时按执行顺序看到已完成结果：

```text
用户:<调查员名>:<地点名>
<原世界角色名>:<调查员名>:<地点名>
```

例如：

```text
用户:林登·怀特:餐厅
爱丽丝:玛格丽特·斯通:客房
```

Agent 提示中给出推荐，而非强制规则：

> 如果仍有尚未被调查员选择的地点，通常应优先考虑不同地点，以扩大团队调查范围；但应结合角色性格、调查员能力、已知线索和角色关系作出符合角色的选择。存在充分角色理由时可以选择已有调查员的地点。

后端允许 Agent 主动选择已被覆盖的地点。

### 8.7 非工具文本与随机兜底

Agent 选景使用专用缓冲执行器：

- 不实时发送普通文本 delta；
- 不预先创建可进入公开上下文的模型输出消息；
- 等待模型完整结束后判断结果。

处理顺序：

1. 调用了选景 `returnDirect` 工具：采用工具结果。
2. 未调用工具，但最终文本严格匹配 `^\s*\d+\s*$`，且编号合法：采用该编号。
3. 输出为空、编号非法、包含其他文字或无法解析：随机分配。

随机分配规则：

- 仍有未被覆盖地点：只从未覆盖地点中等概率随机；
- 所有地点均已覆盖：从全部地点中等概率随机。

无论模型原始输出是否合法，公开历史只写入后端生成的规范选择消息。模型原始文本：

- 不写入公开 `group_chat_message`；
- 不进入后续 Agent/KP 上下文；
- 不通过 SSE 作为普通消息发送；
- 仅记录诊断日志。

若 Agent 没有调用工具却只输出合法编号，仍生成同样的规范公开选择消息。

### 8.8 SCENE Plan 生成

所有选择完成后按地点分组。每个地点生成独立 SCENE Plan：

```text
该地点的用户/Agent 调查员 Plan Item
→ KP Plan Item
```

多个地点继续使用现有 `nextPlanId` 链。用户只有在选择了该地点时才进入该地点的 Plan；Agent-only 场景不产生用户 Step。

## 9. 首次进入场景

### 9.1 首轮顺序

每个新 SCENE Plan 第一次创建 Turn 时，在普通 Plan Item 前插入合成 KP Step：

```text
KP 场景引入
→ 当前仍参与的调查员
→ KP 裁定/收束
```

后续 Turn：

```text
当前仍参与的调查员
→ KP 裁定/收束
```

### 9.2 动作类型

首次引入使用：

```text
actionType = trpg_scene_intro
```

KP 引入负责：

- 描述刚进入地点时可观察到的事实；
- 使用当前地点模组正文和历史场景概要；
- 不替调查员决定行动；
- 不泄露未公开内容；
- 必要时展示场景材料。

### 9.3 首次判定

通过当前 `plan_id` 下是否存在已完成的 `trpg_scene_intro` Step 判断。

- 同一 Plan 已完成引入：后续 Turn 不重复。
- 同一地点被再次访问但产生新 SCENE Plan：重新引入。
- 服务在引入消息完成后中断：新 Turn 不重复已经完成的引入。

## 10. 分阶段 Agent 上下文

本节只限制调查员 Agent。KP 仍使用完整的世界、模组、地点、线索、材料和私有状态上下文。

### 10.1 共同原则

调查员 Agent 不再调用 `buildSystemPrompt`，也不加载：

- 世界设定；
- 原世界角色 `background`；
- 好感状态；
- 用户长期信息。

原世界角色身份只提供：

```text
角色名
CharacterTemplate.personality
```

公共场景消息、已结束场景概要、当前可见事实和已公开检定结果仍按阶段需要加载。这些是跑团事实上下文，不属于被移除的世界系统提示。

### 10.2 选景上下文

选景 Agent 获得：

- 原世界角色名和 `personality`；
- 编号地点选项；
- 前序调查员选择；
- 自己的裁剪后调查员卡。

调查员卡包含：

- 姓名、年龄、职业等基本信息；
- STR、CON、SIZ、DEX、APP、INT、POW、EDU；
- HP、SAN、幸运和持续状态；
- `coc_character_profile` 中的调查员背景条目；
- `coc_character_skill` 中实际保存的技能。

调查员卡不包含：

- 武器；
- 装备；
- 从标准规则或属性别名自动展开、但没有保存在 `coc_character_skill` 中的技能。

### 10.3 探索上下文

探索 Agent 使用与选景相同的身份和调查员卡裁剪：

- 原世界角色名和 `personality`；
- 裁剪后调查员卡；
- 当前公开场景过程；
- 历史场景概要；
- 当前可见事实和状态。

同样不包含世界系统提示、原世界角色背景、武器和装备。

### 10.4 战斗上下文

战斗 Agent 获得：

- 原世界角色名和 `personality`；
- 调查员属性、HP、SAN、幸运和持续状态；
- `coc_character_skill` 中实际保存的技能；
- `coc_character_weapon` 中的武器；
- `coc_character_profile.equipmentText` 中的携带装备；
- 当前战斗公开过程和规则状态。

战斗上下文抛弃调查员背景条目，包括思想信念、重要之人、意义非凡之地、宝贵之物、特质、恐惧与躁狂背景描述以及普通背景笔记。除明确的携带装备外，不加载资产文本、现金或其他叙事背景。

### 10.5 装配边界

新增阶段化调查员上下文装配器，不复用普通群聊 `baseSystemPrompt`：

```text
TrpgInvestigatorContextAssembler
├── formatSelectionContext(...)
├── formatExplorationContext(...)
└── formatCombatContext(...)
```

装配器直接读取人物卡、Profile、Skill、Weapon 和 CharacterTemplate 的必要字段，并返回显式、可测试的阶段 DTO/文本。

## 11. 结束探索

### 11.1 单个调查员结束

Agent 调查员继续通过自己的场景工具结束探索。用户通过对外接口结束探索。两条入口最终调用同一领域方法：

```text
requestInvestigatorFinish(conversationId, turnId, stepId, actorRef)
```

领域服务验证：

- 当前是活动 SCENE Plan；
- Turn 的 `plan_id`、`planContextId` 与活动 Plan 一致；
- Step 属于调用者；
- Step 是当前可执行/等待输入 Step；
- 调用者在当前 SCENE Plan 中。

### 11.2 进度存储

场景 Ready 集合改为保存带类型 Actor Key：

```text
trpg:group:scene-progress:{conversationId}:{sceneId}:ready

user:{playerCardId}
character:{characterTemplateId}
```

场景结束请求保持：

```text
trpg:group:scene-progress:{conversationId}:{sceneId}:finish
```

后续 Turn 创建时过滤所有已 Ready 的用户和 Agent 调查员。若用户已结束，Agent-only Turn 不会等待用户。

### 11.3 最后一名调查员结束

“全部调查员”定义为当前 SCENE Plan 中除 KP 外的所有 `user` 和 `character` Item。

无论最后结束者是用户还是 Agent：

1. 标记场景 `finish`；
2. 取消当前 Turn 中其余待执行调查员 Step；
3. 保留 KP 收束 Step；
4. 完成当前调查员 Step；
5. 执行 KP 收束；
6. KP 完成后结束 Turn；
7. 生成场景总结；
8. 激活下一 SCENE Plan 或回到选景。

取消逻辑不得再使用“取消所有 Pending Step”，必须按 Actor 类型保留 KP。

### 11.4 KP 直接结束

KP 的 `finishSceneExploration()` 继续直接请求场景结算。KP 当前 Step 仍负责公开收束。该工具不改变本设计中选景工具的 `returnDirect` 约束。

## 12. 直接工具结果处理

现有群聊服务只按工具名识别直接掷骰结果。为支持选景 `returnDirect`，新增直接结果分发边界：

```text
GroupDirectToolResultHandler
├── DiceDirectToolResultHandler
├── SceneSelectionOpenResultHandler
└── SceneSelectionChoiceResultHandler
```

处理器负责：

- 识别工具名；
- 反序列化明确 DTO；
- 生成规范公开消息；
- 生成结构化 SSE 事件；
- 更新 Step 累积结果；
- 禁止工具原始 JSON 作为普通角色台词进入历史。

非直接工具和普通模型文本继续使用现有消息流。

## 13. SSE 事件

新增事件：

```text
turn.waiting_input
scene_selection.opened
scene_selection.selected
```

`scene_selection.opened` 示例：

```json
{
  "eventType": "scene_selection.opened",
  "conversationId": 7,
  "turnId": 51,
  "replyStepId": 61,
  "sceneSelection": {
    "options": {
      "1": "餐厅",
      "2": "客房",
      "3": "前台"
    },
    "autoSelected": false
  }
}
```

`turn.waiting_input` 必须包含：

- `turnId`；
- `replyStepId`；
- 当前 Actor；
- 输入类型；
- 选景时的选项 Map；
- 场景或战斗名称。

## 14. 恢复与异常

### 14.1 合法等待

`GroupTurnRecoveryService.recoverInterrupted()` 不得处理 `waiting_input` Turn。会话锁成功取得后，只恢复遗留在 `pending/running` 的中断 Turn；仍在执行的请求持有同一会话锁，不会进入这条恢复路径。

查询非终态 Turn 时必须把 `waiting_input` 视为未完成，但不能视为异常。

### 14.2 客户端断开

- 在用户等待点结束 SSE 是正常完成该 HTTP 请求，不取消 Turn。
- 模型生成期间客户端取消：沿用失败/取消恢复语义，当前执行 Step 进入终态，剩余 Step 不被错误继续。
- 前端通过 `GET /turns/current` 恢复等待 UI。

### 14.3 选景异常

- KP 未调用 `openSceneSelection` 且 Step 没有合法直接结果：选景 Turn 失败，不自动开放全部模组地点。
- KP 地点名称非法：工具报错并使当前 KP 选景 Step 失败，不创建部分选项，也不自动开放其他地点。
- Agent 非法工具参数或非法纯文本：随机兜底，不让 Turn 卡住。
- 用户非法编号：随机兜底，并返回实际选择。
- 随机选择器使用可注入接口，测试中固定结果，生产中等概率选择。

### 14.4 Plan 变化

恢复用户 Step 或结束探索前重新验证：

```text
conversation.activeReplyPlanId == turn.planId
turn.planContextId == activePlan.contextId
```

不一致时拒绝继续，并将旧 Turn 标记为失败或取消，避免把输入写入错误场景。

## 15. 验收测试

### 15.1 身份与 Plan

- 能解析唯一 PLAYER 人物卡和启用 Agent 调查员。
- 缺少或重复 PLAYER 人物卡时拒绝开始。
- SCENE Plan 能保存 `user`、`character` 和 `kp` Item。
- Agent-only 场景不产生用户 Step。
- Actor Key 在用户卡 ID 与角色模板 ID 数值相同时仍不冲突。

### 15.2 暂停与恢复

- Turn 执行到用户 Step 后变为 `waiting_input`。
- 用户消息绑定准确 `replyStepId` 和 PLAYER 卡 ID。
- 用户提交后从下一 Step 继续，不重复前序 Step。
- 没有用户 Step 的 Turn 一次执行完成。
- `GET /turns/current` 能恢复等待状态。
- `recoverInterrupted()` 不会误杀 `waiting_input`。
- 重复和并发请求不能完成同一 Step 两次。

### 15.3 选景

- 两个选景工具均为 `returnDirect`。
- KP 工具只在 KP 选景 Step 可见。
- Agent 工具只在 Agent 选景 Step 可见。
- 探索和战斗阶段均不注入选景工具。
- 后端按名称列表稳定生成编号。
- 空、重复或不存在地点被拒绝。
- 单地点自动分配全部调查员并跳过选择 Step。
- 用户先选择，后续 Agent 按顺序看到已有选择。
- 已有选择使用“控制者:调查员:地点”规范格式。
- Agent 可根据 personality 主动选择已覆盖地点。
- Agent 纯合法编号文本可完成选择。
- Agent 额外文本、空输出和非法编号触发随机兜底。
- 非法原始文本不进入消息历史、SSE 普通 delta 或后续上下文。
- 随机兜底优先未覆盖地点；全部覆盖后使用全部地点。
- 选景完成后按地点生成完整 `nextPlanId` 链。

### 15.4 上下文裁剪

- 选景和探索 Agent 能看到 CharacterTemplate `personality`。
- 选景和探索 Agent 看不到世界系统内容、CharacterTemplate `background`、好感和长期用户信息。
- 选景和探索卡包含 Profile 背景条目及数据库 Skill。
- 选景和探索卡不包含 Weapon、equipmentText 或自动展开的标准技能。
- 战斗卡不包含调查员背景条目。
- 战斗卡包含数据库 Skill、Weapon 和 equipmentText。
- KP 上下文仍包含完整模组和裁定信息。

### 15.5 场景轮次

- 新 SCENE Plan 首轮为 `KP 引入 → 调查员 → KP`。
- 同一 Plan 后续轮为 `调查员 → KP`。
- 同一地点的新 Plan 会再次引入。
- 已完成引入后服务中断不会重复引入。

### 15.6 结束探索

- 用户只能在自己的等待 Step 调用结束接口。
- Agent 只能在自己的执行 Step 调用结束工具。
- 已结束调查员从后续 Turn 中过滤。
- 用户最后结束时保留 KP 收束。
- Agent 最后结束时保留 KP 收束。
- 最后一名结束后取消其他调查员 Step，但不取消 KP。
- KP 收束完成后才总结并切换 Plan。

## 16. 实施边界

实现应按以下边界拆分，避免把所有状态判断继续堆入 `GroupChatService`：

- `TrpgParticipantService`：参与者与人物卡身份；
- `GroupTurnExecutionService`：创建、暂停、恢复和完成 Turn；
- `TrpgSceneSelectionService`：选项、选择、随机兜底和 Plan 分组；
- `TrpgInvestigatorContextAssembler`：分阶段上下文裁剪；
- `TrpgSceneLifecycleService`：调查员 Ready、KP 收束和场景结算；
- `GroupDirectToolResultHandler`：直接工具结果分发；
- Controller：参数、鉴权入口和 SSE 暴露，不承载领域状态机。

普通群聊执行路径和已有骰子领域逻辑只做必要适配，不进行无关重构。

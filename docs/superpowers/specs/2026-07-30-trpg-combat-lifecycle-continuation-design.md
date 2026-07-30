# TRPG 战斗生命周期与统一继续接口设计

## 1. 文档状态

本文档定义 GalChat TRPG 的战斗创建、战斗行动轮、KP 掷骰暂停与恢复、战斗结束回写，以及全 TRPG 统一继续接口。

设计已经确认。实现应沿用现有 `GroupReplyPlan`、`GroupChatTurn`、`GroupChatReplyStep`、用户等待输入、流式回复、工具记录、失败恢复和世界存档机制。战斗事实使用一张独立战斗表持久化，但不另建一套战斗执行器。

## 2. 目标

本次实现必须满足：

1. 只有场景中的 KP 可以创建战斗，并选择参战人物卡。
2. 外部回复计划接口不能创建、替换、推进或删除战斗计划。
3. `COMBAT plan` 继续通过 `resumePlanId` 指向原 `SCENE plan`，战斗结束后恢复原场景。
4. `COMBAT plan.contextId` 指向独立战斗记录。
5. NPC 使用既有 `coc_character` 人物卡，行动模型主体仍是 KP；每名 NPC 拥有独立攻击位和防守位。
6. 攻防流程为“攻击方选择目标并行动 → KP 判断是否插入防守步骤 → 可选防守方行动 → KP 裁定”。
7. 用户调查员和 Agent 调查员都只输出自然语言行动，不调用结构化选目标工具。
8. KP 掷骰工具继续使用 `returnDirect=true`。工具返回后暂停当前 KP step，不自动继续模型，也不推进后续角色。
9. 新增一个全 TRPG 统一继续接口，负责开始下一轮、恢复 KP 骰后暂停和重试或恢复失败行动轮。
10. 用户调查员提交行动仍使用独立接口，提交成功后自动执行后续 step。
11. 战斗结束时根据每个主动攻击位的已裁定明细生成总结，并作为 `combat_result` 写回原场景。
12. 同一跑团内所有 PLAYER、BOT、NPC 人物卡名称必须唯一。

## 3. 非目标

本次不实现：

- 独立 NPC Agent；
- 独立于群聊行动轮的战斗执行器；
- 由攻击方调用工具选择目标；
- 从自然语言直接修改人物卡状态；
- 把逐次攻击过程全部写入原场景消息；
- 战斗嵌套战斗；
- 追逐规则；
- 逐发弹药等与主流程无关的精细资源管理。

## 4. 总体结构

现有结构的职责保持不变：

- `GroupReplyPlan`：描述当前阶段和本轮可执行主体。
- `GroupChatTurn`：描述一次场景轮或战斗轮的运行状态。
- `GroupChatReplyStep`：描述一个模型或用户行动步骤。
- `GroupChatMessage`：保存公开行动、骰点和最终裁定。
- `GroupChatToolCall`：保存需要保留的工具调用及其顺序。

新增 `trpg_combat` 表保存战斗事实。二者关系为：

```text
SCENE plan
  ↑ resumePlanId
COMBAT plan ── contextId ──> trpg_combat
```

战斗期间 `COMBAT plan` 是活动计划。原 `SCENE plan` 保持不变，不删除其分组、顺序或场景链关系。战斗结束时继续调用现有 `finishActiveUnderLock` 删除活动战斗计划并恢复 `resumePlanId`。

## 5. 战斗记录

### 5.1 表结构

新增 `trpg_combat`：

| 字段 | 含义 |
| --- | --- |
| `id` | 战斗 ID，也是 `COMBAT plan.contextId` |
| `conversation_id` | 所属 TRPG 群聊 |
| `source_scene_id` | 创建战斗的原场景 ID |
| `status` | `START_REQUESTED`、`ACTIVE`、`COMPLETED` 或 `CANCELLED` |
| `order_mode` | `DEX` 或 `INVESTIGATORS_FIRST` |
| `current_round` | 当前战斗轮，从 1 开始 |
| `participants` | JSONB 参战者快照 |
| `active_turn_results` | JSONB 主动攻击位结果数组，默认 `[]` |
| `start_requested_step_id` | 发起战斗的 KP step |
| `finish_requested_step_id` | 标记结束战斗的 KP 裁定 step |
| `start_sequence` | 第一条战斗公开消息的位置 |
| `end_sequence` | 原场景中 `combat_result` 的位置 |
| `summary` | 战斗最终总结 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |
| `ended_at` | 完成时间 |

`conversation_id`、`status` 建普通索引。活动战斗唯一性由群聊锁和服务层共同保证：同一群聊只能存在一个 `START_REQUESTED` 或 `ACTIVE` 战斗。

### 5.2 参战者快照

`participants` 中每项至少保存：

- `characterId`；
- 开战时准确人物名称；
- 人物卡类型：`PLAYER`、`BOT` 或 `NPC`；
- 执行主体：`user`、`character` 或 `kp`；
- Agent 调查员对应的 `participantId`；
- 开战时 DEX；
- 开战时 HP、SAN、重伤、昏迷、濒死和死亡状态。

参战者快照用于解释历史和恢复执行主体。实际检定与是否仍可行动必须读取当前人物卡状态，不能把开战快照当成当前状态。

### 5.3 主动攻击位结果

每个攻击方完成一次完整攻防交换后，向 `active_turn_results` 追加一项。每项至少保存：

- `combatRound` 和本轮主动位次序；
- 攻击方人物卡、执行主体、攻击行动消息 ID 和公开行动；
- KP 路由结论；
- 目标人物卡；
- 是否插入防守步骤及原因；
- 防守方行动消息 ID、公开行动和闪避或反击意图；
- 本次裁定关联的全部骰点概要 ID；
- KP 最终裁定消息 ID 和公开裁定；
- HP、SAN、重伤、昏迷、濒死和死亡等前后状态差异；
- 是否有 NPC 在本主动位首次死亡；
- turn、攻击 step、防守 step、裁定 step ID；
- 完成时间。

追加结果和 KP 裁定 step 完成必须处于同一事务。结果以裁定 step ID 作为幂等键；同一裁定 step 不得重复追加。

## 6. 人物卡名称唯一性

### 6.1 规则

同一 `run_id` 内，所有 `coc_character` 共用一个名称空间：

- 用户调查员 `PLAYER`；
- Agent 调查员 `BOT`；
- 模组 NPC `NPC`。

人物名称先去除首尾空白，再进行区分大小写的精确比较。任何两张人物卡不得重名。

### 6.2 导入顺序和校验

创建 TRPG 群聊时先实例化模组 NPC，之后才允许导入用户或 Agent 调查员人物卡。调查员卡写入前查询整个 `run_id`，不能只查询相同 `actor_type`。

数据库要求 `name = btrim(name)`，并增加 `(run_id, btrim(name))` 唯一索引，防止未归一化写入和并发绕过服务层。迁移发现已有重名或未归一化名称时必须中止并报告冲突，不自动删除、改写或重命名用户数据。

模组自身包含同名 NPC 模板时，继续沿用“只实例化排序最前的一张”的规则。

人物名称唯一使 KP 可以在工具和路由提示中使用准确名称，同时仍由后端解析为人物卡 ID。

## 7. KP 发起战斗

### 7.1 工具

新增 KP 场景工具：

```text
startCombat(participantNames, orderMode)
```

- 只在活动 `SCENE plan` 的 KP step 中开放；
- `participantNames` 为准确人物卡名称列表；
- 至少包含两名人物；
- 每个名称必须属于当前 `conversation_id/run_id`；
- 不能选择已死亡人物；
- `orderMode` 只允许 `DEX` 或 `INVESTIGATORS_FIRST`；
- 工具不接受人物卡 ID；
- 活动战斗或待创建战斗存在时拒绝再次创建。

调用工具时先创建 `START_REQUESTED` 战斗记录并绑定 `start_requested_step_id`，但不立即替换活动 plan。当前 KP step 成功完成后才创建 `COMBAT plan`，把当前 `SCENE plan.id` 写入 `resumePlanId`，把战斗 ID 写入 `contextId`，并把战斗状态改为 `ACTIVE`。

如果发起战斗的 KP step 失败并执行完整重试，删除或取消该 step 创建的 `START_REQUESTED` 记录。场景 plan 在成功切换前始终保持活动状态。

### 7.2 Plan item 与人物卡主体

`group_reply_plan_item` 增加可空字段 `subject_character_id`。普通群聊和现有场景计划不依赖该字段；战斗 plan item 必须填写。

映射为：

| 人物卡 | `actorType` | `actorId` | `subjectCharacterId` |
| --- | --- | --- | --- |
| 用户调查员 | `user` | PLAYER 人物卡 ID | PLAYER 人物卡 ID |
| Agent 调查员 | `character` | `participantId` | BOT 人物卡 ID |
| NPC | `kp` | `null` | NPC 人物卡 ID |

`actorType/actorId` 表示由谁生成或提交行动，`subjectCharacterId` 表示该行动属于哪张人物卡。多名 NPC 因 `subjectCharacterId` 不同而拥有不同攻击位，但都由 KP Agent 执行。

`GroupActionSpec` 和 `GroupChatReplyStep` 同步增加 `subjectCharacterId`，使人物卡绑定在计划生成、持久化步骤、失败恢复和提示词重建过程中不丢失。

普通计划继续按 `(actorType, actorId)` 校验同组唯一性；内部创建的 COMBAT plan 按 `subjectCharacterId` 校验参战人物和攻击位唯一性，因此允许同一组内存在多条 `actorType=kp, actorId=null` 的 NPC 项。

## 8. 战斗轮序

一个 `GroupChatTurn` 对应一个完整战斗轮。活动战斗 plan 只保留一个分组，分组中的每个 item 是一个参战人物的主动攻击位。

第一轮：

- `DEX`：所有仍可行动人物按 DEX 降序；
- `INVESTIGATORS_FIRST`：所有 PLAYER/BOT 调查员按 DEX 降序，然后所有 NPC 按 DEX 降序。

第二轮及以后统一按所有人物 DEX 降序。同 DEX 时按人物卡 ID 升序，保证稳定排序。

每轮开始前读取当前人物卡状态。死亡、昏迷或其他明确不能主动行动的人物本轮跳过，但仍保留在战斗记录的参战者快照中。

本轮全部主动位完成后将 `GroupChatTurn` 标记为 `COMPLETED`，但不自动创建下一轮。用户调用统一继续接口后，服务端增加 `current_round`、重排 plan item 并创建下一轮。

## 9. 单个主动位执行

### 9.1 基本链路

每个攻击位展开为：

```text
COMBAT_ATTACK
→ COMBAT_REACTION_ROUTE
→ [可选 COMBAT_DEFENSE]
→ COMBAT_ADJUDICATE
```

攻击、防守和裁定属于同一个主动位。当前主动位完成后自动进入本轮下一个攻击位；不要求用户额外调用继续接口，除非中途出现 KP 骰后暂停或失败。

### 9.2 攻击方

- 用户调查员通过现有用户行动接口提交自然语言目标和行动；
- Agent 调查员直接生成自然语言目标和行动；
- NPC 由 KP Agent 生成行动，提示词必须包含所绑定 NPC 的人物卡信息；
- 三者都不能调用选目标工具。

用户提交攻击行动后，现有接口自动执行路由、防守、裁定和后续 step，直到遇到用户输入、KP 骰后暂停、失败或本轮完成。

### 9.3 KP 路由

攻击行动完成后执行不可见的 `COMBAT_REACTION_ROUTE`。路由读取：

- 攻击方绑定人物卡；
- 攻击方公开行动；
- 当前参战者及人物卡状态；
- 当前场景和战斗公开事实。

路由输出结构化内部结果：

- 识别出的目标人物卡；
- 是否需要插入防守；
- 防守类型范围：闪避、反击或其他合法反应；
- 不插入防守时的原因。

路由结果不保存为公开消息，不作为模型自然语言上下文长期注入，但写入本主动位最终明细。目标缺失、歧义、不是参战者或不合法时，路由 step 失败，由统一继续接口恢复或重试，不能静默猜测另一个目标。

### 9.4 防守方

需要防守时，在路由与裁定之间动态插入 `COMBAT_DEFENSE`：

- PLAYER：等待用户通过现有用户行动接口提交闪避或反击；
- BOT：由对应调查员 Agent 生成防守行动；
- NPC：由 KP Agent 生成防守行动，并绑定该 NPC 人物卡。

防守方完成后自动进入 KP 裁定。无需防守时直接进入 KP 裁定。

## 10. KP 掷骰暂停

### 10.1 核心语义

KP 掷骰工具继续使用 `returnDirect=true`。每次调用：

1. 一次模型响应最多调用一个掷骰工具，禁止并行状态工具；
2. 非用户角色由后端自动掷骰；
3. 包含用户调查员时，为用户创建待掷骰结果；
4. 工具结果立即通过 `dice_roll.created` 返回并保存；
5. 当前 KP step 不完成；
6. 后续角色和 step 不执行；
7. 必须调用统一继续接口恢复。

自动完成骰点和等待用户骰点都不自动继续。差异只在继续前置条件：

- 自动骰：立即允许继续；
- 用户骰：所有当前待掷结果完成后才允许继续。

### 10.2 Step 和消息

新增运行状态：

- `paused`：KP 已获得完整工具结果，等待继续；
- `waiting_dice`：KP 工具结果包含未完成的用户骰；

`GroupChatTurn` 和当前 `GroupChatReplyStep` 同步使用相应状态。

一个 KP reply step 可以关联：

- 零到多条 `dice_roll` 消息；
- 零到多条材料消息；
- 一条最终 `dialogue` 消息。

每次骰点立即创建独立、已完成的 `dice_roll` 消息，并分配新的 `sequenceNo`。KP 最终解释另建 `dialogue` 消息。`replyStep.outputMessageId` 只指向最终解释，不再用骰点消息覆盖解释。

同一 step 的多次工具调用继续使用 `group_chat_tool_call.tool_step_no` 排序。恢复 KP 时使用相同 `replyStepId`，工具编号继续递增。

### 10.3 恢复提示

恢复同一 KP step 时，服务端重新组装：

- 原行动和防守公开消息；
- 当前 step 已完成的工具调用；
- 每个骰点概要的最新状态和语义结果；
- 最新人物卡状态；
- 当前是继续裁定而不是重新开始裁定的明确提示。

已完成骰点不能重复掷。模型可以调用下一项必要掷骰工具并再次暂停，或输出最终公开裁定。

## 11. 全 TRPG 统一继续接口

### 11.1 API

新增：

```http
POST /group-chat/conversations/{conversationId}/turns/continue
Content-Type: application/json
Accept: text/event-stream
```

请求体沿用 `clientRequestId` 幂等语义。接口适用于场景选择、场景探索和战斗，但不代替用户调查员行动接口。

### 11.2 状态路由

接口获得群聊锁后按以下顺序判断：

1. 恢复因服务中断遗留的 `running` 状态；
2. 查找当前非终态或失败 turn；
3. 根据 turn 和 step 状态选择唯一动作。

| 状态 | 行为 |
| --- | --- |
| 没有未完成 turn | 按活动 plan 创建并执行下一轮 |
| 当前 turn 已完成 | 按活动 plan 创建并执行下一轮 |
| KP step 为 `paused` | 使用同一 `replyStepId` 恢复 KP |
| KP step 为 `waiting_dice` 且仍有待掷结果 | 拒绝继续并返回等待骰点状态 |
| KP step 为 `waiting_dice` 且骰点已完成 | 使用同一 `replyStepId` 恢复 KP |
| step 或 turn 为 `failed`、`blocked` | 清理失败片段并从准确失败 step 重试 |
| turn 遗留为 `running` | 调用现有 interrupted recovery 后重试 |
| 等待用户行动或选景 | 拒绝继续，要求调用对应用户提交接口 |

一次继续调用会持续执行后续非用户 step，直到：

- 等待用户行动；
- KP 骰后暂停；
- 发生失败；
- 当前场景轮或战斗轮完成；
- 场景、战斗或跑团生命周期切换完成。

### 11.3 现有接口

现有 `turns/start` 和 step retry 接口保留兼容，但内部委托给统一 continuation service。正常前端流程只使用 `turns/continue`。

用户调查员继续使用：

- 提交行动；
- 提交选景；
- 结束自己的场景探索；
- 提交手动骰点。

用户行动接口成功后自动继续后续 step，不需要紧接着调用 `turns/continue`。用户骰点完成后不会自动恢复 KP，仍需调用 `turns/continue`。

## 12. 战斗结束标记

### 12.1 工具位置和语义

结束判断属于 `COMBAT_ADJUDICATE`，不创建额外判断 step。

在 KP 掷骰/裁定工具集中新增：

```text
markCombatFinished()
```

工具约束：

- 只允许活动战斗的 `COMBAT_ADJUDICATE` KP step 调用；
- 是非终止工具，不使用 `returnDirect=true`；
- 描述明确要求在没有剩余检定或掷骰需求后调用；
- 调用后只把当前 step ID 写入 `finish_requested_step_id`；
- 不立即结束战斗；
- 调用后 KP 必须继续输出完整裁定和公开收束；
- 即使在掷骰前调用，也只有 step 完整成功后才生效。

工具只是当前生命周期标记：

- 不创建公开消息；
- 不保存到普通 `group_chat_tool_call`；
- 不进入后续模型上下文；
- 当前模型调用中的工具响应只用于让模型继续输出。

如果该裁定 step 失败并执行完整重试，清除属于该 step 的结束标记。骰后暂停再继续属于同一 step，不清除标记。

### 12.2 NPC 死亡检查

每个主动位完成骰点和状态更新后，服务端比较人物卡前后状态。如果有 NPC 首次进入死亡状态，恢复或继续当前 KP 裁定时明确提示“本次必须判断战斗是否结束”。

KP 决定：

- 继续战斗：不调用标记工具，输出本次完整裁定；
- 结束战斗：调用 `markCombatFinished`，随后输出完整裁定和公开收束。

NPC 投降、逃跑、调查员撤退或战斗目标已经达成时，即使无人死亡，KP 也可以调用结束标记。

## 13. 战斗总结与场景回写

KP 裁定 step 成功完成后，先追加本主动位结果，再检查 `finish_requested_step_id` 是否等于当前 step。

未请求结束时，自动进入本轮下一攻击位；整轮完成后等待统一继续接口创建下一轮。

请求结束时，在同一受控生命周期中：

1. 从 `active_turn_results` 读取所有已裁定主动位；
2. 使用非思考总结模型生成简洁战斗总结；
3. 总结必须包含胜负或结束原因、关键行动、重要状态变化、死亡或失去行动能力的人物、获得或失去的重要物品及环境变化；
4. 不根据未裁定决策或模型隐藏推理补充事实；
5. 将总结写入 `trpg_combat.summary`；
6. 创建 `message_kind=combat_result` 的公开消息；
7. 该消息 `sceneId=source_scene_id`，`sequenceNo` 使用当前会话下一位置；
8. 写入 `end_sequence`、`ended_at` 并把战斗状态设为 `COMPLETED`；
9. 调用现有 `finishActiveUnderLock` 删除活动 COMBAT plan；
10. 恢复 `resumePlanId` 对应的 SCENE plan。

`start_sequence` 在第一条战斗公开消息完成时写入。战斗详细消息本身不绑定原场景；只有最终 `combat_result` 进入原场景，因此场景概要会获得必要结果而不会被逐次攻防淹没。

总结生成失败时不得删除 COMBAT plan，也不得把战斗标记为完成。统一继续接口应能重试结束事务，且不能重复追加最后一个主动位。

## 14. 外部计划权限

公开回复计划接口：

- `GET` 可以读取活动战斗 plan；
- `PUT` 禁止传入 `source=COMBAT`；
- 活动 plan 为 COMBAT 时，`PUT`、`DELETE` 和 `advance` 均拒绝；
- SCENE 或 USER 的现有行为保持不变。

创建、重排和结束 COMBAT plan 只能由内部战斗生命周期服务执行。世界存档恢复属于受信任内部调用，不受公开接口限制。

## 15. 失败、重试与恢复

### 15.1 失败边界

- 攻击或防守 Agent 生成失败：保留已完成前序主动位，从失败 step 重试。
- KP 路由失败：不插入防守，不进入裁定，从路由 step 重试。
- KP 骰点已落库后模型暂停：骰点不可回滚，恢复同一裁定 step。
- KP 最终解释失败：保留已完成骰点，从同一裁定 step 恢复；不得重复掷骰。
- 主动位结果追加失败：裁定 step 不得标记完成。
- 总结或回写失败：战斗保持 ACTIVE，结束标记保留，由继续接口重试。

### 15.2 完整重试

完整重试失败 step 时：

- 删除失败或空输出消息；
- 删除该次未完成的私有决策；
- 删除可以安全重放的非骰点工具记录；
- 已完成骰点和人物卡状态不重放；
- 清除失败裁定 step 的战斗结束标记；
- 删除该失败 step 尚未提交的防守动态步骤；
- 从准确失败边界重新生成。

恢复逻辑必须区分“骰后暂停继续”和“失败后完整重试”。前者保留工具链和结束标记，后者清除尚未提交的控制状态。

## 16. 世界存档

世界存档增加战斗快照：

- 活动和已完成的 `trpg_combat` 记录；
- participants；
- active turn results；
- 轮数、排序模式、状态、总结和序列位置；
- 开始和结束标记 step ID。

恢复活动战斗时：

1. 先恢复战斗记录并获得新战斗 ID；
2. 恢复原 SCENE plan 链；
3. 恢复 COMBAT plan；
4. 把新战斗 ID 写入 `COMBAT plan.contextId`；
5. 把恢复后的原场景 plan ID 写入 `resumePlanId`；
6. 恢复 turn、step、消息、工具调用和骰点引用；
7. 由统一继续接口恢复暂停、等待骰点或失败状态。

恢复过程必须校验战斗的 `conversationId`、`sourceSceneId`、参战人物卡和 plan 关系，拒绝跨群聊引用、缺失战斗记录和嵌套战斗。

## 17. 事件与前端状态

现有 SSE 事件继续使用，并增加或扩展：

- `turn.waiting_input`：用户行动或选景；
- `turn.waiting_dice`：等待用户完成骰点；
- `turn.paused`：自动骰已完成，等待继续；
- `dice_roll.created`：每次骰点立即返回；
- `message.completed`：攻击、防守或 KP 最终解释完成；
- `turn.completed`：当前场景轮或战斗轮完成；
- `combat.started`：战斗 plan 成功激活；
- `combat.completed`：总结已写回原场景。

当前 turn 查询必须返回可操作状态和输入类型：

- `message`；
- `selection`；
- `dice`；
- `continue`。

前端只根据服务端状态展示“提交行动”“完成骰点”或“继续”，不自行推断 plan 或 step 状态。

## 18. 测试范围

### 18.1 人物卡

- NPC 先实例化后，重名 PLAYER/BOT 导入失败；
- PLAYER、BOT、NPC 任意组合重名失败；
- 名称首尾空白归一化；
- 不同 run 可以同名；
- 并发插入由数据库唯一索引阻止；
- 迁移不自动修改已有冲突数据。

### 18.2 创建战斗

- KP 可以在 SCENE 裁定 step 选择调查员和 NPC；
- 非 KP、非场景或战斗中再次创建均失败；
- 无效、重名、死亡或跨 run 名称失败；
- KP step 成功后才切换 plan；
- `contextId` 指向战斗表，`resumePlanId` 指向原场景；
- 发起 step 失败不会留下可执行战斗。

### 18.3 轮序和主体

- 两种第一轮排序正确；
- 第二轮统一 DEX；
- 同 DEX 按人物卡 ID；
- 无法行动人物被跳过；
- 多名 NPC 各有独立攻击位，模型主体均为 KP；
- NPC 提示使用正确 `subjectCharacterId`。

### 18.4 攻防路由

- 用户、Agent、NPC 攻击都进入同一路由；
- 合法目标插入正确防守主体；
- 不需要防守时直接裁定；
- 用户防守进入等待输入；
- Agent 防守自动继续；
- NPC 防守由 KP 生成；
- 歧义和非法目标失败而非静默猜测。

### 18.5 骰后暂停与继续

- 自动骰返回后 KP step 和 turn 为 `paused`；
- 用户骰返回后为 `waiting_dice`；
- 未完成用户骰时继续被拒绝；
- 用户骰完成后继续恢复相同 replyStepId；
- 同一 step 可以串行保存多个骰点；
- 骰点消息与最终解释分别持久化且顺序正确；
- 恢复后不重复已完成骰点；
- 用户行动提交后自动继续后续 step。

### 18.6 统一继续

- 无活动 turn 时创建下一轮；
- 场景轮和战斗轮都适用；
- paused、waiting_dice、failed、blocked、遗留 running 分支正确；
- 等待用户行动时拒绝继续；
- clientRequestId 防止重复继续；
- 一次继续执行到下一个明确暂停点。

### 18.7 结束和回写

- 结束标记只能由战斗裁定 KP 调用；
- 标记工具不终止模型、不写普通工具历史、不进入上下文；
- 标记后 step 失败不会结束战斗；
- 完整重试清除旧标记；
- NPC 首次死亡触发当前裁定中的强制结束判断提示；
- 不死亡时也可因投降、逃跑或目标完成结束；
- 主动位明细完整且幂等追加；
- 总结只使用主动位明细；
- `combat_result` 绑定原 scene；
- 总结失败时不恢复场景；
- 成功后恢复原 plan 的未完成顺序。

### 18.8 外部接口和存档

- 外部不能创建、替换、推进或删除 COMBAT plan；
- GET 仍可读取活动 plan；
- 存档恢复后战斗 ID 正确重映射；
- paused、waiting_dice 和失败 step 可由统一继续接口恢复；
- 不允许跨群聊战斗或嵌套恢复链。

## 19. 验收标准

- KP 能从场景中选择调查员和 NPC 发起战斗。
- 外部调用不能主动创建或操纵战斗 plan。
- 每名 NPC 使用独立人物卡行动位，但由 KP Agent 生成行动。
- 攻击目标从自然语言行动中由 KP 路由判断，用户和 Agent 不调用不同的目标工具。
- KP 每次掷骰都立即返回并暂停，自动骰和用户骰均不自动继续。
- 全 TRPG 使用一个继续接口处理下一轮、KP 恢复和失败重试。
- 用户调查员行动接口仍自动推进后续 step。
- 一个主动位只有在 KP 完整裁定后才追加战斗明细。
- `markCombatFinished` 只做延迟生效标记，不终止 step，不进入上下文。
- 战斗总结从主动位明细生成并写回原场景。
- 战斗结束后恢复原 SCENE plan 的准确状态。
- 同一跑团内 PLAYER、BOT、NPC 人物卡名称全局唯一。

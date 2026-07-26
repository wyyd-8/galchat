# KP 掷骰工具与多轮结算设计

日期：2026-07-27

## 1. 背景与目标

TRPG 群聊中的 KP 是一个只参与跑团流程、平时不可见的系统 Agent。KP 需要通过工具发起检定、伤害和理智损失等掷骰，并在工具调用结束后立即把结构化掷骰事件返回前端，不继续生成自然语言回复。

本设计的目标是：

- 用一个掷骰概要承载一次完整事件，用多个掷骰结果承载其中的角色、轮次和具体骰子。
- 同时支持单人、群体、对抗和多轮掷骰的不同 UI/UX。
- 用户角色通过前端点击完成掷骰；玩家 Agent 的骰子由后端自动投掷。
- KP 只提供掷骰原因和规则输入，所有实际骰子结果、语义结论及角色卡修改均由后端计算。
- 多步流程在同一概要内追加轮次，并允许前端以“上一轮左移、下一轮从右侧进入”的方式展示递进关系。
- 保证用户重试、工具失败或并发请求不会重复投骰、重复扣除属性或重复创建后续轮。

本期不考虑：

- 幕间成长。
- 幸运调整。
- 暗骰。
- 为 `display_type` 定义正式枚举及动画协议；该字段仅预留。
- 新建独立的疯狂记录表。

## 2. 核心原则

### 2.1 一个事件只有一个概要

一次完整事件只创建一条 `dice_roll_summary`。后续轮次通过更新该概要的 `round_count` 并插入新的 `dice_roll_result` 实现，不创建新的概要。

例如：

1. SAN Check 是第一轮。
2. 理智损失是第二轮。
3. 临时疯狂的类型和持续时间是第三轮。

三轮都属于同一概要，`total_result` 随每轮完成而重新构建并累加展示。

### 2.2 公式和实际结果属于具体结果

表达式不存放在概要中。每个 `dice_roll_result` 都保存自己的 `DiceRollResultVO`，其中包括：

- 实际执行的 `formula`。
- 解析后的 `modules`。
- 最终 `result`。

用户尚未点击时，`formula` 和仅包含骰子结构的 `modules` 已经填充，`result` 为 `null`，并且 `modules` 中没有任何投掷结果值。

因此前端始终以每条结果中的实际表达式展示骰子，能够正确表现诸如 SAN Check 成功后使用 `0`、失败后使用 `1D6` 的差异。

### 2.3 KP 不提供结论

KP 不传入以下内容：

- 实际骰点。
- `total_result`。
- 成功或失败。
- 伤害或理智的实际扣除值。
- `summaryId`。
- `conversationId`。

后端负责投骰、解释规则、生成语义结论、修改角色卡并重建概要总结果。

## 3. 掷骰分类与前端表现

分类主要服务于 UI/UX，而不是拆分成不同的持久化模型。

### 3.1 单人检定

一名角色完成一次检定。前端展示一个角色和一组骰子。

### 3.2 群体检定

多名角色在同一轮分别完成相同或相关检定。前端同时展示多个角色的结果，并明确谁成功、谁失败。

### 3.3 对抗检定

多名角色的结果需要共同决定胜者。前端使用独立的对抗动画和布局，最终只向 KP 暴露胜者或平局。

内部可以计算用于比较的成功等级，但普通检定对 KP 的公开语义只有：

- `CRITICAL_SUCCESS`
- `SUCCESS`
- `FAILURE`
- `FUMBLE`

困难成功和极难成功不会作为普通检定的最终语义返回给 KP。

对抗检定允许 KP 在调用时指定“平局时获胜角色”。后端按骰子计算对抗结果：

- 有明确胜者时返回该角色获胜。
- 计算结果平局且提供了平局胜者时，返回该角色获胜。
- 计算结果平局且未提供平局胜者时，返回平局。

### 3.4 数值结算

伤害、理智损失等数值掷骰在返回实际数值的同时修改角色卡。前端展示骰子和属性变化，不需要 KP 再解释数字的含义。

### 3.5 多轮递进

孤注一掷、SAN Check 后续损失、伤害后的重伤 CON 检定等都表现为同一概要中的新轮次。前端按 `round_no` 分组：

- 当前轮完成后向左移动。
- 右侧出现下一轮窗口。
- 已完成轮仍可查看。
- 当前需要用户投掷的位置由 `result == null` 的用户结果占位表示。

## 4. 数据模型

本设计继续使用两张掷骰业务表，不新增第三张掷骰表。

### 4.1 `dice_roll_summary`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGINT | PK | 掷骰概要 ID |
| `conversation_id` | BIGINT | NOT NULL | 所属群聊 ID，由 `ToolContext` 提供 |
| `reason` | VARCHAR/TEXT | NOT NULL | KP 发起掷骰的原因，可直接进入上下文 |
| `total_result` | TEXT | NULL | 后端根据所有已完成轮次重新构建的累计语义结果 |
| `round_count` | INTEGER | NOT NULL | 当前总轮数，首次创建为 1 |
| `status` | VARCHAR | NOT NULL | `PENDING` 或 `COMPLETED` |
| `created_at` | TIMESTAMP | NOT NULL | 创建时间 |
| `updated_at` | TIMESTAMP | NOT NULL | 更新时间 |

`status` 只表示当前是否还有用户需要完成的骰子：

- 当前存在 `character_id IS NULL AND resolved_at IS NULL` 的真实骰子结果时为 `PENDING`。
- 不存在上述结果时为 `COMPLETED`。
- 用户完成当前轮后先完成结算；若系统随后追加了包含用户占位的新一轮，概要再次变为 `PENDING`。
- 常量表达式，例如实际分支为 `0`，由后端立即解析，不创建需要点击的用户占位。

`total_result` 不采用字符串追加。每次轮次完成后，后端按 `round_no`、`display_order` 从所有“整轮均已结算”的结果重新构建；尚有用户占位的当前轮不会提前写入累计文本。这样既符合逐轮累加展示，也能避免重试导致重复文本。

### 4.2 `dice_roll_result`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGINT | PK | 结果 ID |
| `summary_id` | BIGINT | NOT NULL | 所属概要的逻辑关联 |
| `character_id` | BIGINT | NULL | `NULL` 表示用户角色；非空表示玩家 Agent 角色 |
| `round_no` | INTEGER | NOT NULL | 所属轮次 |
| `display_order` | INTEGER | NOT NULL | 同轮展示顺序 |
| `display_type` | VARCHAR | NULL | 保留字段，本期不定义行为 |
| `reason` | VARCHAR/TEXT | NOT NULL | 本条结果的具体掷骰原因 |
| `result_data` | JSONB | NOT NULL | `DiceRollResultVO` |
| `resolution_data` | JSONB | NOT NULL | 规则快照、语义结果、角色卡影响和来源关系 |
| `resolved_at` | TIMESTAMP | NULL | 完成语义结算的时间；为空表示仍待结算 |
| `created_at` | TIMESTAMP | NOT NULL | 创建时间 |
| `updated_at` | TIMESTAMP | NOT NULL | 更新时间 |

`result_data` 的统一结构为：

```json
{
  "formula": "1D100",
  "modules": [
    {
      "expression": "1D100",
      "diceCount": 1,
      "diceSides": 100,
      "modifier": "NORMAL",
      "dice": [
        {
          "sides": 10,
          "value": null,
          "role": "PERCENTILE_ONES",
          "selected": true
        },
        {
          "sides": 10,
          "value": null,
          "role": "PERCENTILE_TENS",
          "selected": true
        }
      ],
      "result": null
    }
  ],
  "result": null
}
```

用户占位的 `modules` 已完成无随机数的公式解析，只缺少与实际投掷相关的数据；Agent 结果和常量结果在创建时已完整填充。

`resolution_data` 是后端规则数据，不允许 KP 直接构造。第一版使用 JSONB，避免为各类规则增加大量 nullable 列。采用统一外壳加规则专用内容：

```json
{
  "version": 1,
  "type": "DAMAGE",
  "sourceResultId": null,
  "rule": {},
  "outcome": null,
  "effect": null
}
```

- `rule` 在创建结果时填充，保存目标值、成功/失败分支或其他结算快照。
- `outcome` 在结算后填充后端生成的语义结果。
- `effect` 在结算后填充实际应用的 HP、SAN、重伤、昏迷或疯狂变化。
- 不同 `type` 使用各自的小型规则记录，不设计一个包含所有可空字段的巨型 Java 类。

该结构至少能够表达：

- 规则类型，例如 `CHECK`、`OPPOSED_CHECK`、`SAN_LOSS`、`DAMAGE`、`TEMPORARY_INSANITY_TYPE`、`TEMPORARY_INSANITY_DURATION`、`MAJOR_WOUND_CON`。
- 结算所需的目标值或角色卡快照。
- 已生成的公开语义结果。
- 已应用的角色卡变化。
- 多轮来源 `sourceResultId`。

公开接口返回经过整理的语义 DTO，不直接暴露只用于后端校验的角色卡内部 ID 和规则快照。

### 4.3 用户骰子皮肤

用户资料增加一个可空的骰子皮肤字段 `dice_skin`。空值按默认皮肤处理。该字段通过现有用户资料 DTO 返回，由前端决定使用哪套骰子皮肤；不复制到掷骰概要或结果表。

### 4.4 临时疯狂复用现有角色卡字段

不修改角色卡表结构，复用以下字段：

- `temporary_insanity = true`
- `temporary_insanity_phase` 保存稳定疯狂编号
- 数据库列 `temporary_insanity_remaining_rounds` 保存剩余小时数

Java 实体可以将属性名调整为 `temporaryInsanityRemainingHours`，继续用 `@TableField("temporary_insanity_remaining_rounds")` 映射旧列。

疯狂编号格式：

- `1` 至 `8`：固定疯狂类型。
- `9:037`：恐惧症表中的第 37 项。
- `10:042`：躁狂症表中的第 42 项。

读取角色卡进入上下文时，由固定的 `InsanityCatalog` 将编号拼接为具体名称与描述。解除疯狂时将布尔值设为 `false`，编号和剩余小时数均置空。

第一版将疯狂类型、100 项恐惧症和 100 项躁狂症全文保存在代码中的 `InsanityCatalog` 常量类，不新增数据库目录表。该目录属于固定规则数据，无运行时编辑需求；代码常量便于随规则版本审查、测试和发布。只有未来出现后台编辑、多规则版本或自定义目录需求时，才迁移为数据库表。

## 5. KP 身份与群聊结构

每个 `group_conversation` 至多只有一个隐式 KP Agent。KP 不创建角色卡，也不创建群成员记录。

统一身份表示为：

- `actor_type = KP`
- `actor_id = NULL`

需要同步调整：

- 回复计划项允许 `actor_id` 为空，但只允许 `actor_type=KP` 使用空值。
- 只有 TRPG 群聊可以出现 KP 计划项。
- 普通角色仍必须通过现有群成员校验。
- 群聊消息和回复步骤沿用 `actor_type=KP, actor_id=NULL`。
- `GroupToolContext` 增加 actor type；KP 上下文中没有 `characterId`。
- 工具历史的所有权判断使用 `(actorType, actorId)`，不能只比较 nullable 的 `actorId`。
- KP 的私有工具调用只对 KP 后续上下文可见；掷骰事件以公共的合成 `<dice-roll>` 消息进入所有参与者的历史。

## 6. 鉴权与上下文来源

### 6.1 KP 工具调用

`conversationId` 来自 `ToolContext`，不能由模型传入。

创建新概要时，服务使用上下文中的：

- `conversationId`
- 当前回复步骤 ID
- `actorType=KP`

后续工具所需的 `summaryId` 从当前群聊最近一次兼容且成功的掷骰工具调用记录中解析。模型不能传入 `summaryId`。解析后还必须验证：

- 工具调用属于同一个 `conversationId`。
- 已绑定有效的 `dice_roll_summary`。
- 前一事件类型与当前后续工具兼容。
- 当前概要仍允许追加该轮。

### 6.2 对外查询

查询概要或结果时，直接读取 `dice_roll_summary.conversation_id`，再使用现有群聊权限服务验证当前用户能够访问该群聊。`group_chat_tool_call` 中的概要绑定用于追踪来源和定位后续工具所需的前一次概要，不是概要归属关系的唯一来源。

查询结果详情只返回结果列表，不附带概要对象。

### 6.3 用户投骰

用户为指定 `resultId` 投骰时必须同时满足：

- 结果存在。
- 结果的 `character_id` 为 `NULL`。
- 结果所属概要绑定的群聊允许当前用户访问。
- 结果尚未结算；若已经结算则按幂等规则返回已保存结果。

后续如果 `CharacterCardService` 提供按群聊查询用户角色的接口，可将“该空角色结果是否确实属于当前用户角色”的校验收口到该服务，不改变对外接口。

## 7. 服务分层

调用链为：

```text
KpDiceTools
  -> CocDiceOrchestrationService
       -> CocDiceRules
       -> DiceRollService
       -> CharacterCardService
       -> GroupToolCallStore
```

职责如下：

### 7.1 `KpDiceTools`

- 定义提供给 KP Agent 的工具及参数描述。
- 从 `ToolContext` 获取群聊和调用者信息。
- 不实现规则、不直接访问 Mapper。
- 所有状态型掷骰工具使用 `returnDirect=true`。

### 7.2 `CocDiceOrchestrationService`

- 编排新概要和后续轮。
- 定位角色卡及前一次兼容工具调用。
- 调用规则服务解析结果。
- 组织事务、行锁、状态更新、概要总结果重建和条件后续轮。
- 生成返回给 KP 和前端的结构化 DTO。

### 7.3 `CocDiceRules`

- 纯规则计算，不访问数据库。
- 计算检定语义、对抗胜者、SAN 损失、伤害、重伤条件、CON 结果和疯狂目录映射。
- 内部保留必要的成功等级用于对抗比较，但只输出约定的公开语义。

### 7.4 `DiceRollService`

- 解析公式。
- 提供“只解析骰子结构、不生成随机结果”的方法，用于用户占位。
- 完成实际投骰。
- 创建、查询和更新概要及结果。

### 7.5 `CharacterCardService`

- 按 `runId` 和参与者定位角色卡。
- 提供工具所需的角色查询和鉴权入口。
- 在编排服务控制的事务中应用 HP、SAN、重伤、昏迷和临时疯狂变化。

## 8. KP 工具集合

工具参数只描述“为什么投、谁投、按什么规则投”，不接受任何结果字段。

所有目标都使用 KP 上下文中可见的角色名定位，不让模型传数据库 ID。后端通过 `CharacterCardService` 在当前 `runId` 和群聊参与者范围内解析角色；角色名不存在或不唯一时拒绝调用。

百分骰检定可以接受语义化的奖励/惩罚骰枚举 `NORMAL`、`BONUS_1`、`BONUS_2`、`PENALTY_1`、`PENALTY_2`，由后端转换为骰子公式。模型不直接拼接百分骰修饰符。

### 8.1 `requestCheck`

最小输入为：

- `reason`
- `difficulty`：`REGULAR`、`HARD` 或 `EXTREME`
- `targets[]`：每项包含 `characterName`、`checkName` 和可选百分骰修饰

发起单人或群体检定。目标数量为一时是单人检定，多于一时是群体检定，无需拆成两个工具。检定目标值从角色卡中的属性或技能取得，不由 KP 提供。难度只参与后端判断，最终公开语义仍折叠为大成功、成功、失败或大失败。

后端为每个目标生成 `1D100` 结果，并直接形成“谁成功、谁失败”的语义结果。

### 8.2 `requestOpposedCheck`

最小输入为：

- `reason`
- `targets[]`：每项包含 `characterName`、`checkName` 和可选百分骰修饰
- `tieWinnerCharacterName`：可空

发起对抗检定。至少包含两名参与者。

后端实际投骰并返回：

- `<角色>获胜`
- `平局`

不把内部成功等级列表作为最终结论交给 KP。

### 8.3 `requestPushedCheck`

最小输入为：

- `reason`
- `characterNames[]`：需要进行孤注一掷的角色

为最近一次兼容检定追加孤注一掷轮。该工具由 KP 主动调用，不由系统在第一次失败后自动触发。后端沿用前一轮相应角色的检定项、目标值和百分骰修饰快照，KP 不重复提供数值。

服务从群聊工具调用历史解析概要，增加 `round_count` 并创建新一轮结果。

### 8.4 `requestSanCheck`

最小输入为：

- `reason`
- `characterNames[]`

发起理智检定，只结算成功或失败，目标值来自角色卡当前 SAN。理智检定不接受奖励骰或惩罚骰。本工具不自动创建 SAN 损失轮。

### 8.5 `rollSanLoss`

最小输入为：

- `reason`
- `successFormula`
- `failureFormula`

由 KP 在 SAN Check 后主动调用，为最近一次兼容概要追加理智损失轮。`0/1D6` 在工具参数中表示为成功表达式 `0` 和失败表达式 `1D6`；后端根据前一轮每个角色的实际检定结果选择其真实公式。

本工具完成：

- 实际投骰或用户占位。
- SAN 扣除。
- 语义结果生成。
- 满足条件时自动创建临时疯狂轮。

### 8.6 `rollDamage`

最小输入为：

- `reason`
- `sourceMode`：`STANDALONE` 或 `FOLLOW_UP`
- `targets[]`：每项包含 `characterName` 和 `formula`

同时支持：

- `STANDALONE`：坠落、陷阱等独立伤害，创建新概要和第一轮。
- `FOLLOW_UP`：作为攻击/反击、攻击/闪避或远程武器检定的后续，自动找到最近一次兼容概要并追加轮次。

KP 可以为不同目标提供不同的伤害表达式。后端投骰、扣除 HP、判断重伤并在必要时自动创建 CON 检定轮。

### 8.7 不提供的工具

不提供：

- `requestMajorWoundConCheck`
- 单独的疯狂类型或疯狂持续时间工具
- 单独的疯狂明细 `1D100` 工具

这些都是确定性规则链中的系统后续轮，不需要 KP 再次判断。

### 8.8 直接返回结构

所有工具统一返回 `KpDiceToolResult`，至少包含：

- `summary`：概要 ID、原因、累计结果、轮数和状态。
- `results`：本次创建或已自动结算的结果列表。
- `semanticResult`：当前已经确定的公开语义；用户尚未投骰的部分为空。

该对象既作为 `returnDirect` 的结构化来源，也保存到工具调用记录。前端接收完整结果，其中 Agent 结果已经填充，用户结果保留待点击占位。

## 9. 详细结算流程

### 9.1 创建首轮

1. KP 调用工具。
2. 服务从 `ToolContext` 取得 `conversationId` 和回复步骤。
3. 后端创建概要，`round_count=1`。
4. 为每个目标创建结果：
   - 用户真实骰子：公式和空结果模块已填充，`result=null`、`resolved_at=null`。
   - Agent：立即投骰、完成语义结算并填写 `resolved_at`。
   - 常量公式：立即结算，不要求点击。
5. Agent 的角色卡影响在同一事务中应用。
6. 后端重建 `total_result` 和 `status`。
7. 工具调用记录绑定 `summaryId`。
8. `returnDirect` 终止本次 KP 模型输出，群聊发送掷骰事件。

### 9.2 用户投骰

1. 锁定概要、指定结果及需要修改的角色卡。
2. 若 `resolved_at` 已存在，返回已保存结果，不重新生成随机数。
3. 对保存的公式投骰。
4. 根据保存的规则快照生成语义结论。
5. 应用角色卡变化。
6. 保存完整 `result_data`、`resolution_data` 和 `resolved_at`。
7. 当前轮已完成时重建概要结果。
8. 检查是否应自动创建疯狂轮或重伤 CON 轮。
9. 重新计算 `round_count` 和 `status`。
10. 返回本次结果及条件创建的新结果。

推荐的返回结构为：

```json
{
  "summary": {},
  "rolledResult": {},
  "createdResults": []
}
```

`createdResults` 让前端无需额外轮询即可显示刚出现的下一轮；为空表示没有条件后续轮。

### 9.3 SAN 损失与临时疯狂

`rollSanLoss` 根据每个角色上一轮的 SAN Check 结果，从 `0/1D6` 中选择实际表达式并保存到该角色的结果中。

实际 SAN 损失大于等于 5 时进入临时疯狂流程，不再进行 INT 检定。

疯狂轮的创建时机：

- SAN 损失轮存在用户真实骰子占位时，即使 Agent 已经损失至少 5 点 SAN，也先等待用户完成该轮。
- 用户完成后，扫描本轮所有已结算角色；只要有人损失至少 5 点 SAN，立即创建疯狂轮。
- SAN 损失轮没有用户真实骰子占位时，在 `rollSanLoss` 工具事务内直接创建疯狂轮。
- 不等待其他调查员；非用户角色本来就由后端自动结算。

疯狂轮为每个受影响角色创建两条标准结果：

1. `1D10`：疯狂类型。
2. `1D10`：持续时间，单位为小时。

用户角色需要分别点击这两个骰子，Agent 自动投掷。两个骰子不是 `additionalRolls`，而是具有正常轮次、顺序、公式和结果的 `dice_roll_result`。

疯狂类型为 9 或 10 时：

- 不创建 `1D100` 结果。
- 后端直接生成 `1..100` 的随机数。
- 分别从固定恐惧症表或躁狂症表中选择具体条目。
- 随机数、目录编号和最终文本记录在 `resolution_data` 中以便审计。

最终累计文本示例：

```text
理智-6；进入临时疯狂：恐惧症（昆虫恐惧症：害怕昆虫），持续4小时
```

### 9.4 伤害与重伤 CON 检定

`rollDamage` 完成实际伤害投掷并立即修改 HP。单次伤害达到角色卡规则规定的重伤条件时：

- 立即设置 `major_wound=true`。
- 系统在同一概要中自动追加一轮 `MAJOR_WOUND_CON` 检定。
- 不要求 KP 再调用工具。

CON 轮的创建时机与疯狂轮一致：

- 伤害轮存在用户真实骰子占位时，先等待用户完成伤害轮，再扫描本轮所有重伤角色。
- 伤害轮没有用户真实骰子占位时，在 `rollDamage` 工具事务内立即创建。

每个需要检定的角色创建一条 `1D100` 结果：

- 用户角色等待点击。
- Agent 自动投掷。
- CON 成功时保持清醒。
- CON 失败时自动设置 `unconscious=true`。
- 本轮不产生更多自动后续轮。

疯狂轮和重伤 CON 轮的 `resolution_data` 均保存 `sourceResultId`。服务在概要行锁内按规则类型和来源结果检查已有后续项，从而保证重复请求不会创建第二份后续轮。

## 10. 语义结果与上下文

KP 需要理解规则结论，而不是读取无语义数字。

示例：

- 普通检定：`林恩成功；陈默失败`
- 对抗检定：`林恩获胜`
- 对抗平局：`平局`
- 伤害：`陈默生命-4；受到重伤；CON检定失败，陷入昏迷`
- SAN：`林恩理智-6；进入临时疯狂：恐惧症（昆虫恐惧症：害怕昆虫），持续4小时`

概要的 `total_result` 和公开结果 DTO 由后端生成。原始公式、骰点、随机目录编号仍保留在具体结果中，用于前端动画、复盘和审计。

## 11. `returnDirect` 与消息输出

所有状态型 KP 掷骰工具使用 `returnDirect=true`，优于让模型自行输出 JSON，原因是：

- 结构由后端 DTO 决定，不依赖模型遵守格式。
- 工具创建骰子后模型不会继续叙述或重复结论。
- 能够明确限制一次 KP 回复最多调用一个状态型掷骰工具。

群聊层需要识别直接返回的掷骰结果：

- 不把工具 JSON 当作普通 assistant 文本发送或存储。
- 当前消息记录为掷骰消息，例如 `message_kind=DICE_ROLL`、`content=NULL`。
- 通过 `dice_roll.created` 事件把概要和结果发送给前端。
- 正常完成当前回复步骤。

直接返回的掷骰消息作为公共事件进入后续上下文；KP 工具的内部参数和私有执行记录仍按角色所有权过滤。

## 12. 事务、幂等与错误处理

### 12.1 用户投骰事务

以下操作必须在一个事务中完成：

- 行锁。
- 随机投骰。
- 语义解析。
- 角色卡修改。
- 结果写入。
- 后续轮创建。
- 概要状态和总结果更新。

任一步失败时全部回滚。已经存在 `resolved_at` 的结果按幂等读取处理，绝不重新投骰或重复修改角色卡。

### 12.2 KP 工具事务

状态型 KP 掷骰工具的以下操作应整体提交：

- 工具业务执行。
- 概要和结果写入。
- 角色卡影响。
- `group_chat_tool_call` 执行记录写入。
- 工具调用与 `summaryId` 的绑定。

现有 `RecordingGroupToolCallingManager` 在 delegate 返回后才写记录，需要为状态型 KP 掷骰工具提供受控的事务边界，使业务变化和调用记录要么同时成功，要么同时回滚。该边界只覆盖本设计中的状态型 KP 掷骰工具，不扩大到无关工具。

### 12.3 错误返回

以下情况返回明确的业务错误，不创建部分数据：

- 角色不存在或不属于当前跑团。
- 找不到兼容的前一次掷骰概要。
- 公式非法。
- 后续工具与前一事件类型不兼容。
- 用户尝试投掷 Agent 结果。
- 概要不属于当前用户可访问的群聊。
- 角色卡已经处于不能接受该变化的终态。

对已经成功处理的重复请求返回保存结果，不视为错误。

## 13. 对外与内部接口

### 13.1 对外接口

1. 查询给定 ID 的掷骰概要。
2. 查询给定概要 ID 的掷骰详情，仅返回按轮次和展示顺序排列的结果列表。
3. 用户为指定结果 ID 掷骰，返回概要、本次结果及条件创建的新结果。

三者都在应用服务层完成群聊归属和当前用户权限校验，Controller 不直接访问 Mapper。

### 13.2 内部接口

1. 创建新的掷骰概要。
2. 修改给定 ID 的掷骰概要。
3. 批量创建多个角色的掷骰结果。

规则编排还需要内部能力：

- 按概要加锁读取结果。
- 按来源查询是否已有自动后续轮。
- 批量结算 Agent 结果。
- 重建概要 `total_result`、`round_count` 和 `status`。

这些能力属于服务内部接口，不对 Controller 或 KP 模型直接暴露。

现有 `appendDiceRollRound(..., totalResult, ...)` 需要移除调用方提供的 `totalResult` 参数；追加轮次后由编排服务根据已结算结果统一重建。概要更新接口也不能让 KP 或 Controller 写入自行生成的累计结果。

## 14. 测试范围

### 14.1 规则单元测试

- 普通检定只输出四类公开结果。
- 对抗胜者、平局和指定平局胜者。
- SAN 成功与失败选择不同实际公式。
- SAN 损失至少 5 点直接触发临时疯狂，不进行 INT 检定。
- 疯狂类型 9/10 使用后端随机目录，不创建 `1D100` 结果。
- 重伤条件和 CON 成败后的昏迷变化。
- `total_result` 按所有完成轮次稳定重建。

### 14.2 服务测试

- 用户占位包含公式和无结果模块。
- Agent 创建时自动投骰和修改角色卡。
- 常量 `0` 不产生待点击状态。
- 用户重复投骰返回相同结果且不重复扣属性。
- 用户完成 SAN 损失后条件创建疯狂轮。
- 无用户 SAN 损失时在工具事务中立即创建疯狂轮。
- 用户完成伤害后条件创建重伤 CON 轮。
- 无用户伤害时在工具事务中立即创建重伤 CON 轮。
- 同一来源不会重复创建疯狂或 CON 后续轮。
- 后续轮增加原概要的 `round_count`，不创建新概要。

### 14.3 鉴权测试

- 无群聊访问权的用户不能查询概要或结果。
- 用户不能投掷 `character_id` 非空的 Agent 结果。
- KP 的 `actor_id=NULL` 只在 TRPG 群聊合法。
- 工具历史按 `(actorType, actorId)` 正确区分 KP 和普通角色。
- 后续工具不能跨群聊解析概要。

### 14.4 消息与集成测试

- `returnDirect` 后不产生额外 KP 文本。
- 工具 JSON 不作为普通对话内容保存。
- 前端收到 `dice_roll.created`。
- 用户投骰响应包含条件创建的 `createdResults`。
- 工具业务失败时调用记录、骰子数据和角色卡变化全部回滚。

## 15. 验收标准

设计实现后应满足：

- KP 能用明确、有限的工具发起所有当前需要的掷骰。
- 模型不需要根据裸数字推断检定、伤害或理智结论。
- 用户只需点击当前结果为空的占位。
- Agent 的骰子自动出现完整结果。
- 多轮事件始终复用同一概要并可递进展示。
- SAN 损失、临时疯狂、伤害、重伤和昏迷均由后端原子地修改角色卡。
- 临时疯狂和重伤 CON 检定在满足条件时自动成为新一轮。
- 重试不会改变骰点、重复扣除属性或重复创建轮次。
- KP 工具完成后直接返回掷骰事件，不继续输出自然语言。

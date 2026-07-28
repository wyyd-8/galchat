# COC 模组内容、检索工具与 KP 上下文设计

日期：2026-07-29

## 1. 背景与目标

GalChat 需要把 COC 模组保存为可复用的静态内容，并在具体跑团中为 KP Agent 组装完整但不过量的上下文。调查员的核心探索结构是“地点—人物”，地点同时承担场景调度、当前上下文和场景总结边界；NPC 与调查员数据由现有角色系统提供，不重复存入模组内容表。

本设计依据《克苏鲁的呼唤》第十五章中的模组组织方式，并使用《太阳与九英镑》Markdown 验证字段和检索能力是否足以承载实际模组。第一版优先保留作者原文和稳定的章节级解析，不把自然语言强行转换成不稳定的条件、事件或效果 JSON。

目标如下：

- 使用五张关系表保存除调查员、NPC 和具体跑团进度之外的全部静态模组上下文。
- 对作者明确划分的事件真相、调查员导入、时间线、特殊规则和结局进行低风险拆分。
- 把地点作为场景切换、场景全文载入和场景总结的基本边界。
- 使用独立线索表保存跨地点复用的事实与重要证据。
- 使用独立材料表支持“人类玩家看到图片、Agent 调查员看到文字介绍”的展示行为。
- 通过固定上下文自动装配和按需检索结合，避免每轮把整本模组放入提示词。

本期不包含：

- 调查员和 NPC 表结构设计。
- 具体跑团中的线索获得状态表。
- 将地点原文进一步解析为触发条件、场景事件、NPC 引用、效果或规则 JSON。
- 多级向量检索编排、复杂重排模型和知识图谱。
- 材料与地点、线索之间的强制关联。

## 2. 核心边界

### 2.1 五张表只负责静态模组内容

五张表覆盖模组名称、时代、导入、真相、时间线、特殊规则、结局、地点、事件原文、线索和展示材料。

下列内容不属于五张表：

- 调查员和 NPC 的身份、属性、技能、武器、生命值与理智值。
- 当前所在地点、已经发生的行动和已经获得的信息。
- 聊天原文、场景总结、掷骰结果和战斗状态。
- 已展示材料集合。

这些运行时信息分别来自角色系统、现有群聊消息、场景总结、掷骰系统和 Redis。

### 2.2 章节级解析与原文保留并存

作者通常会明确划分守秘人信息、调查员导入、时间线、特殊机制和结局，因此这些内容可以稳定进入全局上下文表的独立字段。

地点内部的检定方式、人物反应、可选事件和剧情分支通常混在叙事原文中。第一版不继续拆解，直接保存在地点 `content` 中，由 KP 阅读和裁定。

### 2.3 地点是场景和调度边界

地点承担三项职责：

- KP 通过地点索引判断调查员可以前往何处。
- 进入地点后，地点完整原文进入 KP 上下文。
- 当前地点对应现有消息和场景总结中的 `scene_id`。

第一版令 `scene_id` 直接使用 `coc_module_location.id`。重新访问同一地点时可以再次生成新的场景总结；总结仍通过消息序号范围区分，不要求为每次访问创建新的静态地点。

### 2.4 线索与材料职责分离

线索回答“KP需要掌握什么事实”，材料回答“此刻向玩家展示什么”。

- 线索可以没有图片。
- 材料可以只是地图、照片、报纸或氛围图，不一定对应一条线索。
- 材料不使用线索表的 `content_type` 表达。
- 两者都不保存具体跑团中的已获得状态。

## 3. 数据模型

### 3.1 `coc_module`

保存模组选择页面需要的内容，以及构建上下文时需要的基础信息。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGINT | PK | 模组 ID |
| `name` | VARCHAR | NOT NULL | 模组名称 |
| `author` | VARCHAR | NULL | 作者 |
| `era` | VARCHAR | NULL | 时代与地区 |
| `introduction` | TEXT | NOT NULL | 玩家可见简介 |
| `investigator_creation` | TEXT | NULL | 调查员职业、背景与关系创建建议 |
| `cover_url` | TEXT | NULL | 封面地址 |
| `player_count` | VARCHAR | NULL | 推荐人数，保留区间表达 |
| `estimated_duration` | VARCHAR | NULL | 预计时长 |
| `visible` | BOOLEAN | NOT NULL | 是否可在前端选择 |
| `created_at` | TIMESTAMP | NOT NULL | 创建时间 |
| `updated_at` | TIMESTAMP | NOT NULL | 更新时间 |

`investigator_creation` 只保存创建建议，不保存实际调查员。

### 3.2 `coc_module_context`

每个模组最多一条全局上下文记录。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGINT | PK | 记录 ID |
| `module_id` | BIGINT | UNIQUE, NOT NULL | 所属模组 |
| `truth_background` | TEXT | NULL | 事件真相、幕后原因和历史背景 |
| `investigator_intro` | TEXT | NULL | 开场、委托和调查入口 |
| `timeline` | TEXT | NULL | 作者提供的历史时间线 |
| `special_rules` | TEXT | NULL | 模组专属规则和状态机制 |
| `keeper_guidance` | TEXT | NULL | 整体主持建议和关键方向 |
| `ending_content` | TEXT | NULL | 结局、奖励和收束方式 |
| `extra_content` | TEXT | NULL | 无法归类的全局原文 |
| `created_at` | TIMESTAMP | NOT NULL | 创建时间 |
| `updated_at` | TIMESTAMP | NOT NULL | 更新时间 |

所有正文均保存 Markdown/Text 原文。字段允许为空，不要求不同作者采用完全相同的章节结构。无法可靠分类的全局内容必须进入 `extra_content`，不能在导入时丢弃。

### 3.3 `coc_module_location`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGINT | PK | 地点 ID，同时作为场景 ID |
| `module_id` | BIGINT | NOT NULL | 所属模组 |
| `parent_location_id` | BIGINT | NULL | 父地点 |
| `name` | VARCHAR | NOT NULL | 地点名称 |
| `summary` | TEXT | NOT NULL | 地点索引和检索摘要 |
| `content` | TEXT | NOT NULL | 地点完整原文 |
| `created_at` | TIMESTAMP | NOT NULL | 创建时间 |
| `updated_at` | TIMESTAMP | NOT NULL | 更新时间 |

`content` 可以包含：

- 初始环境描述。
- 地点内的区域和物件。
- 可选事件。
- NPC 在该地点的表现和可提供信息。
- 检定建议及成功、失败信息。
- 与时间或调查进展有关的自然语言说明。
- 地点内可能发生的战斗、理智检定和结局分支。

第一版不增加 `entry_condition`、`scene_events_json`、`npc_refs_json`、`sort_order` 等字段。

### 3.4 `coc_module_clue`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGINT | PK | 线索 ID |
| `module_id` | BIGINT | NOT NULL | 所属模组 |
| `title` | VARCHAR | NOT NULL | 便于检索和提醒的名称 |
| `content` | TEXT | NOT NULL | 线索完整原文 |
| `important` | BOOLEAN | NOT NULL | 是否为必须保障获得的重要证据 |
| `created_at` | TIMESTAMP | NOT NULL | 创建时间 |
| `updated_at` | TIMESTAMP | NOT NULL | 更新时间 |

`important=true` 只表达作者意图：KP 应设法确保调查员最终得到此证据。它不表示证据已经被获得，也不规定证据必须从哪个地点或通过哪种检定获得。

第一版不增加 `content_type`、`kp_content`、`player_content`、`source_rules_json`、`effects_json` 和 `sort_order`。KP 读取完整 `content` 后自行决定如何向调查员公开。

### 3.5 `coc_module_material`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGINT | PK | 材料 ID |
| `module_id` | BIGINT | NOT NULL | 所属模组 |
| `title` | VARCHAR | NOT NULL | 展示标题 |
| `description` | TEXT | NOT NULL | Agent 可理解的完整语义介绍 |
| `image_url` | TEXT | NOT NULL | 人类玩家看到的图片 |
| `created_at` | TIMESTAMP | NOT NULL | 创建时间 |
| `updated_at` | TIMESTAMP | NOT NULL | 更新时间 |

`description` 必须覆盖图片中用于调查和推理的文字、符号及关键视觉信息，不能只写外观说明。它是 Agent 调查员对材料的无障碍语义表示。

材料不强制关联地点或线索。KP 根据当前场景和调查员行动决定何时展示。

## 4. 《太阳与九英镑》覆盖验证

验证文件：

`/Users/wyyd/Downloads/MinerU_markdown_太阳与九英镑_2081824310157156352.md`

实际内容映射如下：

| 模组内容 | 保存位置 |
|---|---|
| 名称、简介、时代背景、调查员创建建议 | `coc_module` |
| 守秘人信息中的事件真相 | `coc_module_context.truth_background` |
| 1888 年历史时间线 | `coc_module_context.timeline` |
| 侦探事务所、卫生管理局导入 | `coc_module_context.investigator_intro` |
| 蠕臭症、感染进度 | `coc_module_context.special_rules` |
| 点燃街区、放任或阻止莉斯伊尔等结局 | `coc_module_context.ending_content` |
| 医院、酒店、药剂铺、薰衣草街区等全部场景 | `coc_module_location` |
| 医院的“活死鸦”等可选事件 | 对应地点的 `content` |
| 感染源、九英镑交易、法术核心等跨地点事实 | `coc_module_clue` |
| 玛德琳照片、信件、报纸、解剖研究文件 | `coc_module_material` |
| 迈德、莉斯伊尔、康拉德等人物 | 现有角色系统 |

模组中的书籍、法术、药剂和战斗说明如果只在单一场景中出现，保留在地点 `content`；如果它们是跨场景推理需要反复查询的事实，则额外建立线索记录。无需为物品、法术或事件增加新表。

## 5. 检索与上下文总体策略

### 5.1 方案比较

#### 方案 A：每轮载入五张表的全部正文

优点是实现简单，KP 不会漏读内容。缺点是地点和普通线索会迅速占满上下文，不适合完整模组。

#### 方案 B：除当前场景外全部通过工具查询

优点是上下文最小。缺点是 KP 可能不知道模组中存在什么地点、重要证据和材料，也就不会主动检索。

#### 方案 C：固定核心自动装配，详细正文按需载入

自动载入模组基础信息、全局上下文、地点索引、重要线索、当前地点全文和材料目录；其他地点与普通线索通过工具检索。

本设计采用方案 C。它能够让 KP 保持全局方向，同时避免每轮重复注入整本模组。

### 5.2 派生向量索引

地点和线索可以使用现有向量能力建立派生索引，但向量存储不是第六张权威内容表。

索引文本：

```text
地点 = name + summary + content
线索 = title + content
材料 = title + description
```

向量元数据至少包含：

```text
moduleId
recordType
recordId
```

检索必须先限制当前 `moduleId`。向量结果只用于定位记录，最终正文始终按 `recordId` 回到关系表读取，避免索引旧内容成为权威结果。

第一版不实现复杂重排；精确 ID、精确名称匹配优先，其余使用向量 Top-K。

## 6. 内部查询接口

这些方法由上下文装配器和 KP 工具调用，不直接暴露给模型。

### 6.1 模组和全局上下文

```java
ModuleOverview getModuleOverview(Long moduleId)
```

返回：

- 名称、作者、时代、简介。
- 调查员创建建议。
- 推荐人数和时长。

不存在或不可用时抛出受控的模组不存在异常。

```java
ModuleGlobalContext getGlobalContext(Long moduleId)
```

按固定标题返回非空的全局上下文字段。该方法不执行相似度检索，也不改变作者原文。

### 6.2 地点

```java
List<LocationIndexItem> listLocationIndex(Long moduleId)
```

返回当前模组全部地点的：

```text
locationId
parentLocationId
name
summary
```

不返回 `content`。

```java
LocationDetail getLocation(Long moduleId, Long locationId)
```

按 `moduleId + locationId` 查询并返回地点完整记录。组合条件是必要的跨模组访问防线。

```java
List<LocationSearchHit> searchLocations(Long moduleId, String query, int limit)
```

行为：

1. 查询为空时拒绝执行。
2. 优先匹配地点 ID 和名称。
3. 未命中时对当前模组地点做向量检索。
4. 最多返回五条 `id/name/summary`。
5. 不返回地点完整原文，不切换场景。

### 6.3 线索

```java
List<ModuleClue> listImportantClues(Long moduleId)
```

返回当前模组所有 `important=true` 的线索标题和完整 `content`。第一版面向小型模组，重要线索全文每轮进入 KP 上下文，以确保 KP 不会忘记必须提供的证据。

```java
List<ClueSearchHit> searchClues(Long moduleId, String query, int limit)
```

行为：

1. 优先匹配线索标题。
2. 其余使用当前模组内的向量检索。
3. 最多返回五条标题、完整 `content` 和 `important`。
4. 没有结果时返回空列表，不生成推测性答案。

检索结果已经包含完整正文，因此第一版不提供额外的 `getClue`。

### 6.4 材料

```java
List<MaterialIndexItem> listMaterials(Long moduleId, Long conversationId)
```

先查询材料表，再读取群聊的 Redis Set，返回：

```text
materialId
title
description
shown
```

不返回 `image_url` 给模型。

```java
ModuleMaterial getMaterial(Long moduleId, Long materialId)
```

按当前模组校验并读取完整材料，只供展示服务内部使用。

```java
Set<Long> getShownMaterialIds(Long conversationId)
```

从 Redis 读取已经展示的材料 ID。Redis Key 为：

```text
trpg:group:{conversationId}:shown-materials
```

类型为 Set，成员使用十进制材料 ID 字符串。

## 7. KP 模组工具

模型不能传入 `moduleId`、`conversationId` 或跑团 ID。这些值通过现有 `ToolContext` 取得，并由当前跑团解析出所属模组。

### 7.1 `searchLocations`

```java
List<LocationSearchResult> searchLocations(String query, ToolContext context)
```

用途：KP 不确定应该载入哪个地点时搜索地点索引。

结果示例：

```json
[
  {
    "locationId": 204,
    "name": "皇家阿尔伯特·爱德华医院",
    "summary": "蠕臭症患者集中隔离的公共医院。"
  }
]
```

本工具只查询，不改变当前场景。

### 7.2 `enterLocation`

```java
LocationContent enterLocation(Long locationId, ToolContext context)
```

用途：调查员实际前往地点时载入地点原文并切换场景。

执行过程：

1. 验证调用者是当前群聊的 KP。
2. 验证地点属于当前模组。
3. 完成上一地点的场景总结；仍有未完成检定或战斗时拒绝切换。
4. 将 `locationId` 设置为随后消息和场景总结使用的 `scene_id`。
5. 返回地点 `name/summary/content` 给 KP。
6. KP 根据原文生成该地点的初始可观察描述。

重新进入同一地点是允许的。新的消息序号区间形成新的场景过程和总结。

### 7.3 `searchClues`

```java
List<ClueSearchResult> searchClues(String query, ToolContext context)
```

用途：KP 需要确认事实、证据、传播方式或跨地点关系时查询线索。

结果示例：

```json
[
  {
    "title": "蠕臭症真正的爆发地",
    "content": "最早的感染者来自薰衣草街区，拉曼律师并非零号病人……",
    "important": true
  }
]
```

该结果只进入 KP 的工具上下文，不直接对调查员公开。

### 7.4 `searchMaterials`

```java
List<MaterialSearchResult> searchMaterials(String query, ToolContext context)
```

用途：KP 查找可以展示的材料。

结果示例：

```json
[
  {
    "materialId": 301,
    "title": "玛德琳寄给康拉德的信",
    "description": "信中提到玛德琳住在皇家橡树酒店，并正在调查一种奇怪药剂。",
    "shown": false
  }
]
```

工具不返回图片 URL。

### 7.5 `showMaterial`

```java
void showMaterial(Long materialId, ToolContext context)
```

这是无返回值的副作用工具。成功调用后必须立即结束当前 KP 响应；工具生成的材料消息就是本次公开输出，KP 不再补充自然语言或 JSON。

成功流程：

1. 从 `ToolContext` 获取 KP 身份、群聊 ID、当前回复步骤和跑团 ID。
2. 解析当前模组并验证材料归属。
3. 获取现有群聊世界变更锁，防止同一材料并发重复展示。
4. 检查 Redis Set；已经展示时直接结束，不插入重复消息。
5. 创建一条现有 `group_chat_message`：
   - `speaker_type = KP`
   - `speaker_id = NULL`
   - `message_kind = MATERIAL`
   - `visibility = public`
   - `scene_id = 当前地点 ID`
   - `turn_id/reply_step_id = 当前工具上下文`
6. 把标题、介绍和图片 URL 全部序列化进现有 `content`。
7. 数据库消息提交成功后尝试执行 `SADD`，把材料 ID 写入 Redis Set；Redis 写入失败只记录错误，不中止后续广播。
8. 通过现有群聊事件向前端推送材料消息。
9. 方法以 `void` 正常结束，不产生供 KP 继续叙事的工具结果。

`content` 固定为：

```json
{
  "title": "玛德琳的照片",
  "description": "照片中是一名约25岁的金发女性，脸上有雀斑，穿着偏中性的深色服装。",
  "imageUrl": "https://oss.example.com/materials/madeline.jpg"
}
```

不修改 `group_chat_message` 表和 `GroupChatMessageVO` 的字段结构。前端根据已有 `message_kind` 解析 `content`。

Redis 只承担展示去重和状态提示，不是材料知识的权威来源。数据库消息先于 Redis 写入，保证 Redis 故障时最多允许以后重复展示，而不会出现“Redis 标记已展示但玩家没有看到消息”的永久丢失。

## 8. 材料消息的双重呈现

### 8.1 人类玩家

前端收到 `MATERIAL` 消息后解析 `content`，展示：

- 标题。
- 图片。
- 文字介绍。

刷新页面时，历史接口仍返回相同 `message_kind + content`，因此可以恢复图片展示，不需要材料 ID 字段。

### 8.2 调查员 Agent

`GroupContextAssembler` 读取到 `MATERIAL` 消息时不能把原始 JSON 直接发送给 Agent，而应转换为：

```xml
<shown-material title="玛德琳的照片">
照片中是一名约25岁的金发女性，脸上有雀斑，穿着偏中性的深色服装。
</shown-material>
```

Agent 上下文中不包含 `imageUrl`。这样既满足 Agent 无法直接理解材料图片的限制，也避免无意义 URL 占用上下文。

场景总结必须记录材料的标题和 `description` 所表达的信息。材料消息被历史摘要覆盖后，调查员仍能依据总结继续推理。

### 8.3 KP 后续上下文

材料目录通过 Redis Set 标记 `shown=true`。已展示材料可以在 KP 上下文中显示标题和介绍，帮助 KP 判断哪些信息已经公开；图片 URL 仍不进入模型。

## 9. KP 上下文装配

每次 KP 行动按固定顺序装配：

1. 系统规则与 KP 职责。
2. 模组基础信息。
3. 模组全局上下文。
4. 地点索引。
5. 重要线索。
6. 当前地点完整原文。
7. 材料目录与展示状态。
8. 调查员和 NPC 信息。
9. 所有已结束场景总结。
10. 当前场景公开消息。
11. 当前待处理检定、战斗和本轮行动。

### 9.1 模组基础信息

```xml
<module>
名称：太阳与九英镑
时代：1888年，英国维根市
简介：调查员受托寻找失踪的玛德琳，并调查正在蔓延的蠕臭症。
调查员创建：支持侦探事务所和卫生管理局两种导入背景。
</module>
```

### 9.2 全局上下文

只输出非空字段，并保留固定标题：

```xml
<module-global-context>
  <truth-background>……</truth-background>
  <investigator-intro>……</investigator-intro>
  <timeline>……</timeline>
  <special-rules>……</special-rules>
  <keeper-guidance>……</keeper-guidance>
  <ending-content>……</ending-content>
  <extra-content>……</extra-content>
</module-global-context>
```

第一版不对这些字段进行相似度裁剪。小型模组默认全部进入 KP 上下文。

### 9.3 地点索引

```xml
<location-index>
[201] 维根市卫生管理局：调查蠕臭症的政府委托入口。
[202] 卡夫拉私人药剂铺：迈德出售异常药剂的地点。
[203] 皇家橡树酒店：玛德琳和拉曼先后居住的酒店。
[204] 皇家阿尔伯特·爱德华医院：患者隔离、爱德华医生和解剖调查。
[205] 圣玛丽街43号：玛德琳研究莉斯伊尔的住所。
[206] 薰衣草街区98号：迈德住处及法术核心所在地。
</location-index>
```

### 9.4 重要线索

```xml
<important-clues>
[401] 蠕臭症最早在薰衣草街区出现，拉曼律师不是零号病人。
[402] 玛德琳曾在皇家橡树酒店236号房居住。
[403] 爱德华医生曾支付九英镑，预订莉斯伊尔死后的尸体。
[404] 蠕虫污染水源，并通过接触皮肤造成感染。
[405] 薰衣草街区98号地下室存在维持转化的法术核心。
</important-clues>
```

### 9.5 当前地点

```xml
<current-location id="204">
名称：皇家阿尔伯特·爱德华医院

医院由红砖砌成，中央有一座黑色塔楼。大量渡鸦停留在附近建筑上。
所有蠕臭症患者被集中隔离在中央塔楼……

可选事件：活死鸦
受蠕臭症感染的渡鸦可能袭击室外的调查员……

调查内容：
- 接待人员可以说明所有患者都在中央塔楼。
- 爱德华医生知道病症传播方式。
- 医学生调查员可以获知莉斯伊尔及九英镑交易。
- 拉曼律师的病房可以进行进一步观察。
</current-location>
```

实际上下文使用地点 `content` 原文，不使用此示意中的人工缩写。

### 9.6 材料目录

```xml
<materials>
[301] 玛德琳的照片｜已展示
介绍：25岁左右的金发女性，面有雀斑，穿着偏中性。

[302] 玛德琳的信｜未展示
介绍：信中提到她住在皇家橡树酒店，并正在追查来源可疑的药剂。

[303] 蠕臭症解剖研究文件｜未展示
介绍：文件要求分别解剖头部、四肢和腹部，并测试火焰与水对蠕虫的效果。
</materials>
```

### 9.7 完整效果示意

```xml
<module>
名称：太阳与九英镑
时代：1888年，英国维根市
简介：调查员受托寻找失踪的玛德琳，并调查蠕臭症。
</module>

<module-global-context>
  <truth-background>
  迈德·霍西试图用万应灵药挽回家族悲剧，并以九英镑定金购买材料。
  莉斯伊尔与迈德同时施法，造成蠕行者转化和法术核心异常。
  从莉斯伊尔身体散落的蠕虫污染了薰衣草街区的水源。
  </truth-background>
  <investigator-intro>
  1888年12月23日，康拉德委托调查员寻找玛德琳；
  卫生管理局则要求调查蠕臭症的感染源。
  </investigator-intro>
  <timeline>
  11月8日：迈德杀死莉斯伊尔，法术发生冲突。
  11月14日：薰衣草街区出现第一名患者。
  12月3日：玛德琳感染。
  12月18日：拉曼律师住进医院。
  12月23日：模组开始。
  12月24日晚：警方计划清扫薰衣草街区。
  </timeline>
  <special-rules>
  蠕臭症通过受污染水源、虫媒以及蠕虫接触皮肤传播。
  感染程度随接触蠕虫数量和时间发展。
  </special-rules>
</module-global-context>

<location-index>
[201] 维根市卫生管理局：政府委托入口。
[202] 卡夫拉私人药剂铺：异常药剂来源。
[203] 皇家橡树酒店：玛德琳和拉曼的住宿地点。
[204] 皇家阿尔伯特·爱德华医院：患者隔离与医学调查。
[205] 圣玛丽街43号：玛德琳研究莉斯伊尔的住所。
[206] 薰衣草街区98号：迈德住处和法术核心。
</location-index>

<important-clues>
[401] 蠕臭症最早在薰衣草街区出现。
[403] 爱德华医生曾支付九英镑预订莉斯伊尔的尸体。
[405] 薰衣草街区98号地下室存在法术核心。
</important-clues>

<current-location id="204">
皇家阿尔伯特·爱德华医院的完整模组原文……
</current-location>

<materials>
[301] 玛德琳的照片｜已展示｜照片中是一名约25岁的金发女性……
[302] 玛德琳的信｜未展示｜信中提到皇家橡树酒店和奇怪药剂……
</materials>

<characters>
调查员和NPC数据由现有角色系统装配……
</characters>

<scene-summaries>
调查员此前从卫生管理局得知拉曼律师因蠕臭症住院……
</scene-summaries>

<current-scene-messages>
当前医院场景的公开消息和已经展示材料的文字语义……
</current-scene-messages>
```

调查员 Agent 不接收 `<module-global-context>`、地点隐藏原文、未公开线索或未展示材料，只接收自身角色信息、公开场景总结、当前公开消息和已经展示材料的文字介绍。

## 10. 错误、安全与并发处理

### 10.1 跨模组访问

所有按 ID 查询的方法都必须同时限制当前 `moduleId`。模型提供的地点、线索或材料 ID 不能单独作为查询条件。

### 10.2 检索无结果

地点、线索和材料搜索没有命中时返回空列表。工具不得补造地点、线索或材料。

### 10.3 场景切换失败

地点不存在、属于其他模组、存在未完成检定或战斗时，`enterLocation` 返回受控工具错误，当前地点保持不变。

### 10.4 材料展示失败

- 材料不存在或属于其他模组：拒绝展示。
- 图片 URL 为空或不满足现有图片安全规则：拒绝展示。
- 已经展示：无操作并正常结束。
- 数据库消息写入失败：不写 Redis、不广播。
- Redis 写入失败：数据库消息仍保留并广播；记录错误，允许后续重复展示。
- 广播失败：数据库历史仍可在刷新后恢复材料。

### 10.5 并发展示

使用现有群聊世界变更锁串行执行“检查 Set—写消息—写 Set—广播”，避免同一群聊中的两个 KP 工具步骤重复展示同一材料。

## 11. 测试与验收

### 11.1 数据模型

- 一个模组只能有一条全局上下文。
- 地点父节点必须属于同一模组。
- 线索 `important` 和材料 `description/image_url` 不允许为空。
- 不存在 `content_type`、条件 JSON、事件 JSON 和材料强关联字段。

### 11.2 检索

- 所有搜索结果只能来自当前模组。
- 精确名称优先于向量结果。
- 地点搜索只返回摘要，进入地点才返回完整原文。
- 重要线索自动进入 KP 上下文。
- 普通线索只有检索后才进入本轮工具上下文。
- 没有命中时返回空列表，不产生幻觉结果。

### 11.3 材料

- `showMaterial` 的 Java 返回类型为 `void`。
- 成功调用只产生一条 `MATERIAL` 消息，KP 不追加叙事。
- 消息表和消息 VO 不增加材料字段。
- 消息 `content` 同时包含 `title/description/imageUrl`。
- 前端能够从历史消息恢复图片。
- Agent 上下文包含标题和介绍，但不包含图片 URL。
- Redis Set 保存材料 ID，并能在材料目录中产生 `shown=true`。
- 并发调用不会生成两条材料消息。

### 11.4 上下文

- KP 每轮获得模组基础信息、全局上下文、地点索引、重要线索、当前地点全文和材料目录。
- 调查员 Agent 看不到模组真相、未公开线索和未展示材料。
- 场景总结包含已经展示材料的语义信息。
- 使用《太阳与九英镑》数据时，KP 能主持委托导入、医院调查、酒店调查、药剂铺调查、薰衣草街区终局及不同结局，不需要读取五表之外的静态模组正文。

## 12. 第一版完成标准

满足以下条件即可认为第一版设计实现完整：

1. 《太阳与九英镑》的全部非人物内容可以无丢失地导入五张表。
2. KP 不调用工具也能知道模组真相、时间线、当前地点、重要证据和可展示材料。
3. KP 能通过地点、线索和材料工具取得其余详细内容。
4. 地点切换能形成正确的场景上下文与总结边界。
5. 材料展示不修改聊天消息结构，成功方法无返回值。
6. 人类玩家看到图片和介绍，Agent 调查员只看到等价的文字语义。
7. 五张表之外只依赖角色系统和具体跑团运行状态，不需要新增第六张静态模组内容表。

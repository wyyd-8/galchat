# COC 模组人物卡 JSONB 模板与跑团置入设计

## 1. 文档状态

本文档定义模组人物卡的 JSONB 存储方式，以及创建绑定模组的 TRPG 跑团时，将模组人物卡复制到 `coc_character`、`coc_character_skill`、`coc_character_weapon` 和 `coc_character_profile` 的目标设计。

设计已经确认。本次只实现通用存储和复制能力，不录入《第15章 模组》中的实际 NPC 数据。

## 2. 目标

- 为每个 COC 模组提供独立的人物卡模板存储位置。
- 使用 JSONB 保存一张完整人物卡，不为模板复制运行时四张关联表。
- 创建绑定模组的 TRPG 跑团时，自动把该模组已有的人物卡置入当前 `run_id`。
- 为复制得到的人物卡重新生成主键和关联关系，使不同 `run_id` 独立维护 HP、SAN、MP、弹药和状态。
- 当前跑团已经存在同名人物卡时跳过模组中的同名人物卡，不增加模板来源字段。

## 3. 非目标

- 本次不录入或转换 `output/pdf/第15章-模组.md` 的 NPC 数据。
- 不补全第 15 章中只引用第 14 章、没有完整属性的 NPC。
- 不校验模组人物卡的属性范围、必填字段、技能点、技能名称、武器规则或 profile 内容。
- 不设计 NPC 在战斗、探索、群聊成员或 Agent 回复计划中的用途。
- 不增加模组人物卡管理页面或单独的增删改查接口。
- 玩家人物卡仍沿用既有导入和校验流程，但其 `runId` 必须指向
  `GroupConversation.id`，不再接受 `userWorldId`。
- 不把模组人物卡的后续修改同步到已经创建的跑团。

## 4. 第 15 章 NPC 表兼容性

第 15 章的三个模组末尾分别包含“非玩家角色”或“非玩家角色与怪物”章节。现有运行时人物卡表可以承载其中的核心属性、技能和武器：

- `coc_character` 保存基础属性、派生属性、HP、SAN、MP、护甲和状态。
- `coc_character_skill` 保存技能名称与数值。
- `coc_character_weapon` 保存攻击或武器名称、关联技能、伤害、射程、攻击次数和补充说明。
- `coc_character_profile.notes` 保存法术、理智损失、动态护甲、特殊能力和其他不能结构化的规则原文。

实际整理第 15 章数据时需要在数据侧处理以下情况，但这些处理不属于本次实现：

- 多名 NPC 共用一套属性时，按最终需要置入的角色拆成多张人物卡。
- 同组 NPC 持有不同武器时，为各自的人物卡配置不同的武器列表。
- 怪物缺少 APP、EDU、SAN 等运行时必填字段时，由数据制作者补充目标表可接受的值。
- 只有外部章节引用而没有完整属性的 NPC 暂不形成模组人物卡。
- 动态护甲、法术和特殊攻击等规则保存在 profile 或 weapon 的 notes 中。

因此，JSONB 保存的是“已经整理为运行时目标结构的人物卡快照”，不是未经转换的 Markdown 表格原文。

## 5. 数据模型

新增表：

```sql
CREATE TABLE coc_module_character (
    id BIGSERIAL PRIMARY KEY,
    module_id BIGINT NOT NULL,
    sort_order INT NOT NULL,
    card_data JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_coc_module_character_module_order
    ON coc_module_character (module_id, sort_order, id);
```

项目现有模组表不使用数据库外键，本表沿用相同约定，由服务层维护模组聚合的创建和删除。

`card_data` 的约定结构镜像现有 `CharacterCardVO`：

```json
{
  "character": {
    "name": "沃尔特·柯比特",
    "str": 90,
    "con": 115,
    "siz": 55,
    "dex": 35,
    "app": 5,
    "intValue": 80,
    "pow": 90,
    "edu": 80,
    "damageBonus": "+1D4",
    "build": 1,
    "mov": 8,
    "hpCurrent": 9,
    "hpMax": 9,
    "sanCurrent": 0,
    "sanMax": 0,
    "mpCurrent": 18,
    "mpMax": 18,
    "armor": 0
  },
  "skills": [],
  "weapons": [],
  "profile": {
    "notes": "法术、动态护甲、特殊攻击和其他无法结构化的原文"
  }
}
```

表中不单独保存人物名称，也不增加内容级唯一索引。人物名称只来自 `card_data.character.name`。

## 6. 模组聚合生命周期

`CocModuleCreateDTO` 增加 `characters` 列表。列表项以原始 JSON 对象接收，使模组创建阶段不执行人物卡业务校验。

创建模组时：

1. 先保存现有模组基础信息、全局上下文、地点、线索和材料。
2. 按 `characters` 的列表顺序写入 `coc_module_character`。
3. `sort_order` 从 0 开始递增。
4. `card_data` 原样保存为 JSONB。
5. `characters` 为空或缺省时不写人物卡记录。

删除模组时，在删除 `coc_module` 之前删除该模组全部 `coc_module_character`。现有“只要有历史跑团引用就禁止删除模组”的规则保持不变。

## 7. 创建跑团时置入人物卡

### 7.1 触发条件

只有创建满足以下条件的会话时执行复制：

```text
mode = trpg
module_id IS NOT NULL
```

普通群聊不查询或复制模组人物卡。

`coc_character.run_id` 使用 `GroupConversation.id`。创建跑团时必须先写入会话并取得 ID，再以该 ID 置入模组人物卡；不同跑团不会共享人物卡状态。

该字段此前错误使用 `userWorldId`。切换前必须检查已有
`coc_character`：若存在旧数据，需要根据实际跑团归属迁移后再部署；
同一用户世界可以有多个跑团，不能仅凭 `userWorldId` 自动且无歧义地
改写。当前开发数据库检查结果为空，因此本次不执行有损或猜测性的
历史数据迁移。

### 7.2 事务与锁

复制过程加入现有 `GroupConversationService.create` 事务，并复用：

- 当前用户世界锁；
- 当前模组读锁。

会话、群聊成员和复制产生的运行时人物卡必须在同一事务内成功。任何人物卡转换或数据库写入失败都会使整个跑团创建回滚。

### 7.3 同名跳过

复制开始时查询当前 `run_id` 下已有的全部 `coc_character.name`，建立名称集合。

对模组人物卡按 `sort_order, id` 依次处理：

1. 读取 `card_data.character.name`。
2. 使用去除首尾空白后的名称进行区分大小写的精确比较。
3. 名称已经存在于当前 `run_id` 时，跳过整张人物卡，不写技能、武器或 profile。
4. 成功写入人物卡后，立即把名称加入集合。

因此，同一模组内存在多个同名模板时，只复制排序最靠前的一张。重复创建绑定相同模组的会话时，已有同名人物卡不会重复生成。

本设计不向 `coc_character` 增加 `module_character_id`、`module_id` 或其他模板来源字段，也不增加新的唯一索引。

### 7.4 主表复制

把 JSONB 中的 `character` 转换为新的 `CocCharacter`，忽略模板中可能携带的以下运行时字段：

- `id`
- `runId`
- `actorType`
- `participantId`
- `creationMethod`
- `createdAt`
- `updatedAt`

系统覆盖设置：

```text
run_id = 当前 GroupConversation.id
actor_type = NPC
participant_id = NULL
creation_method = MODULE
created_at = 当前时间
updated_at = 当前时间
```

其他人物属性按 JSONB 内容复制。人物名称使用已去除首尾空白的值写入。

### 7.5 关联表复制

主表插入并获得新 `coc_character.id` 后：

- 对每个 skill 清空原 `id` 和 `characterId`，使用新人物卡 ID 写入 `coc_character_skill`。
- 对每个 weapon 清空原 `id` 和 `characterId`，使用新人物卡 ID 写入 `coc_character_weapon`。
- profile 存在时清空原 `id` 和 `characterId`，使用新人物卡 ID 写入 `coc_character_profile`。
- skills 或 weapons 缺省、为 `null` 或为空列表时按空列表处理。
- profile 缺省或为 `null` 时不写 profile。

模板 JSONB 和运行时记录之间不存在后续同步关系。

## 8. 内容校验与错误处理

模组创建阶段不检查 `card_data` 内部字段。PostgreSQL 只保证它是合法 JSONB。

创建跑团时必须把数据写入现有运行时表，因此以下问题会在转换或插入阶段自然失败：

- 根结构无法转换成人物卡结构；
- `character` 或人物名称缺失；
- 字段类型与 Java 类型不兼容；
- 缺少 `coc_character` 的数据库必填字段；
- 技能、武器或 profile 违反现有数据库约束。

服务层不为错误数据猜测默认属性，也不只复制人物卡的一部分。转换或插入异常统一包装为 `UserRequestException("模组人物卡数据无法置入")`，底层异常保留为 cause 供日志定位。事务回滚后不保留会话、人物主表或关联表的半成品。

同名跳过是唯一的数据级容错规则，不属于内容有效性校验。

## 9. 组件边界

新增：

- `CocModuleCharacter` PO；
- `CocModuleCharacterMapper`；
- 模组人物卡 JSONB 类型映射；
- 负责从模组模板实例化运行时人物卡的独立服务。

修改：

- `CocModuleCreateDTO`：接收可选 `characters`；
- `CocModuleService`：聚合创建和删除模组人物卡；
- `GroupConversationService`：在创建 TRPG 跑团时调用实例化服务；
- 初始化 SQL 和独立迁移 SQL；
- 对应单元测试。

实例化服务只负责：

1. 查询模组人物卡；
2. 查询当前 `run_id` 已有名称；
3. 跳过同名人物卡；
4. 重建人物主表和关联表 ID；
5. 写入四张运行时表。

它不负责读取 Markdown、补全怪物数据或决定人物卡的业务用途。

## 10. 测试设计

### 10.1 模组生命周期

- 创建模组时按输入顺序保存 JSONB 人物卡。
- `characters` 缺省或为空时不写人物卡。
- 模组人物卡内容不经过技能点或字段完整性校验。
- 删除无跑团引用的模组时删除其人物卡。
- 模组有历史跑团引用时仍拒绝删除，且不提前删除人物卡。

### 10.2 实例化服务

- 模组人物表为空时不写任何运行时人物卡。
- 完整人物卡正确写入主表、技能、武器和 profile。
- 模板中的旧 ID 和关联 ID 不进入运行时表。
- `runId`、`actorType`、`participantId` 和 `creationMethod` 使用系统值覆盖。
- skills、weapons 或 profile 为空时只写存在的数据。
- 当前 `run_id` 已有同名人物卡时整张跳过。
- 同一模组内重名时只复制排序最靠前的一张。
- 当前 `run_id` 中的其他名称不影响复制。
- 无效 JSON 结构或数据库必填字段缺失时抛出异常。

### 10.3 跑团创建

- 创建绑定模组的 TRPG 跑团时调用实例化服务。
- 创建普通群聊时不调用实例化服务。
- 模组人物卡复制失败时跑团创建失败。
- 重复创建绑定相同模组的 TRPG 会话时，同名人物卡不重复写入。

## 11. 验收标准

- 模组可以持有零张或多张 JSONB 人物卡。
- 创建模组不对人物卡 JSON 内容执行业务校验。
- 创建绑定模组的 TRPG 跑团时，非同名人物卡完整复制到四张运行时表。
- 已有同名人物卡和模组内后续同名模板被跳过。
- 不向 `coc_character` 增加模板来源字段。
- 复制失败不会留下半成品跑团或人物卡。
- 普通群聊、现有玩家人物卡流程及角色模板人物卡流程保持不变。

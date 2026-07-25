# 群聊第一版 API

## 数据库

项目当前未启用 Flyway。部署前手动执行：

`docs/sql/V20260711__group_chat.sql`

已有环境还需要执行：

`docs/sql/V20260725__favor_binding_type.sql`

## 创建群聊

`POST /group-chat/conversations`

```json
{
  "userWorldId": 1,
  "mode": "chat",
  "title": "调查废弃医院",
  "characterIds": [11, 12]
}
```

`mode` 支持 `chat` 和 `trpg`。同一用户世界可以同时存在多个未结束群聊。
`characterIds` 只能包含已经创建到当前用户世界的角色；角色所在群聊关闭前不能删除该角色。

```http
GET  /group-chat/conversations?userWorldId=1&status=active
GET  /group-chat/conversations/{conversationId}
POST /group-chat/conversations/{conversationId}/close
```

创建和关闭接口都不接收开场、过程或结束说明。系统会基于完整公开聊天记录重写最终概要，
保存到 conversation 和 `group_context_summary`，关闭群聊，并写入世界事件记录。对已关闭群聊
再次调用 close 会重新生成概要。

列表和详情额外返回 `lastChatContent` 与 `lastChatTime`。新建后还没有已完成消息时，这两个字段为
`null`。

## 发送消息

`POST /group-chat/conversations/{conversationId}/messages`

响应类型为 `text/event-stream`。

```json
{
  "clientRequestId": "客户端生成的唯一请求id",
  "content": "我们进入地下室。"
}
```

- 发送消息时消费当前活动回复计划中所有 `pending` 人物。
- 同一计划执行完成后不会自动重置；下一轮开始前由用户、场景或战斗逻辑更新计划。
- 同一 conversation 的 turn 串行执行；后一个角色能看到前一个角色刚完成的回复。

主要事件：

- `turn.accepted`
- `reply.started`
- `reasoning.delta`
- `message.delta`
- `message.completed`
- `reply.failed`
- `turn.completed`

带回复步骤的事件还会返回：

- `replyStepId`：本次实际执行步骤
- `planItemId`：对应的当前回复计划项
- `actionType`：`chat_reply`、`trpg_scene_action` 或 `trpg_combat_action`

`reasoning.delta` 只用于当前前端展示，不写入消息表、思考表或下一次模型上下文。

## 回复计划

```http
GET    /group-chat/conversations/{conversationId}/reply-plan
PUT    /group-chat/conversations/{conversationId}/reply-plan
DELETE /group-chat/conversations/{conversationId}/reply-plan
```

`PUT` 使用当前内容覆盖活动计划。普通群聊来源为 `USER`，探索为 `SCENE`，战斗为
`COMBAT`：

```json
{
  "source": "COMBAT",
  "contextId": 200,
  "groups": [
    {
      "key": "round:1",
      "name": "第1轮",
      "order": 1,
      "items": [
        {"order": 1, "actorType": "character", "actorId": 12},
        {"order": 2, "actorType": "character", "actorId": 11}
      ]
    }
  ]
}
```

计划项状态由执行器维护：`pending`、`running`、`completed`。失败或客户端取消时恢复为
`pending`。

当活动计划不是战斗计划时，写入 `COMBAT` 会自动记录当前计划作为恢复目标。战斗结束后
调用 `DELETE`，系统删除战斗计划并恢复原探索计划及其未完成顺序。第一版不支持战斗内
再次嵌套战斗。删除普通或探索计划时，系统会按当前启用的群聊成员顺序重建默认 `USER`
计划。

NPC 的战斗行动统一由 KP 负责。KP 在群聊中是一个特殊的 `character`，因此直接使用其
角色 id 参与排序，不增加独立的 `npc` 或 `kp` 执行分支。

## 查询历史

`GET /group-chat/conversations/{conversationId}/messages?beforeId=&size=50`

历史只返回公开消息内容。模型思考通过实时 `reasoning.delta` 事件返回前端，不写入数据库，
也不会进入后续模型上下文。

## 撤回

`POST /group-chat/conversations/{conversationId}/withdraw`

撤回会删除普通群聊最新一整轮中的用户消息、所有角色回复、回复步骤和工具调用，并回滚该轮的
好感变化与已完成计划项。`group_chat_turn` 保留为 `withdrawn` 占位，用于限制最多连续撤回
三轮。接口只允许用于仍为 `active` 的 `chat` 会话；TRPG 会话不支持撤回。

如果被撤回轮创建了新话题边界，系统会同步移除边界，并删除重新进入两个热话题窗口的精确向量
文档。普通群聊数据库保留完整话题边界，运行时只使用最近五个边界支持三轮回退。

## 上下文策略

每个 mode 注册一套 `GroupModeRuntime`，并同时绑定三个可替换插槽：

- `GroupTurnPolicy`：把当前回复计划转换为行动步骤
- `GroupContextPolicy`：维护边界并组装本次可见上下文
- `GroupAgentPolicy`：选择 `ChatClient`、系统提示词和允许使用的工具

公共 `GroupChatService` 只负责锁、事务、消息/步骤持久化、串行流式执行和状态收尾，不判断
`chat`/`trpg` 的具体业务规则。

普通群聊以共享语义话题为边界。模型保留“上一话题 + 当前话题”的公开原文；第三个话题开始时，
离开窗口的旧话题会重写到 `group_topic_vector_store`，后续按当前 conversation 定向检索并作为
`retrieved-group-memory` 注入。语义判断失败时保持当前话题，单话题超过容量上限时强制切分。

TRPG 使用独立上下文策略插槽，当前第一版不复用普通群聊的话题切分；后续场景概要、战斗概况
只需在该插槽内实现，不需要修改公共执行器。

## 当前工具边界

群聊使用基于独立 DeepSeek thinking/non-thinking 模型构建的专属 `ChatClient`。回复、话题判断、
旧话题重写和关闭概要都不经过 `TopicAwareMessageChatMemoryAdvisor`、`UserChatMemory` 或
`RecordingToolCallingManager`。

群聊模型调用固定携带以下 `ToolContext` 字段，不需要工具再次查询这些基础关联：

- `worldId`
- `userWorldId`
- `groupConversationId`
- `characterId`
- `groupReplyStepId`
- `favorSystemStatus`（世界启用好感系统时）

工具调用写入独立的 `group_chat_tool_call`，绑定 `group_chat_reply_step.id`，不写入单聊的
`user_chat_tool_call`。普通工具调用和结果只会重建到调用角色自己的模型上下文；绑定了
`dice_roll_summary_id` 的调用不会还原为原始工具消息，而会转换为公开的 `<dice-roll>` 消息，
供群聊中所有角色看到。

好感日志通过 `binding_type` 区分 `SINGLE_MESSAGE` 与 `GROUP_REPLY_STEP`，并使用
`(user_world_id, character_id, binding_type, binding_chat)` 组合索引，避免单聊消息 id 与群聊
步骤 id 冲突。

普通群聊的 `ChatGroupAgentPolicy` 当前开放 `VectorTools` 和 `UserCharacterFavorTools`；
`UserCharacterInfoTools` 仍仅供单聊使用。TRPG 工具白名单保持为空。群聊 `ChatClient` 不配置
默认工具，工具范围继续由各模式的 Agent 插槽负责。

`VectorTools` 不区分调用来自单聊还是群聊。同一 `userWorldId + characterId` 会统一检索该角色
的单聊历史、其参与过的群聊归档话题、世界详情和可见世界事件，再经过同一个 reranker 排序。
群聊话题向量使用归档时写入的 `visibleCharacters` 过滤，因此不会返回该角色未参与的群聊内容；
查询过程不需要再次读取群聊成员表。已有群聊话题向量未包含该字段时不会命中新查询，本版不提供
旧向量回填。

为覆盖尚未写入向量库的最近内容，`VectorTools` 还会直接读取该角色单聊最近三个话题，以及该
角色仍启用参与的所有 `active + chat` 群聊各自最近两个话题，并与向量结果进入同一次 rerank。
热原文只包含单聊可见消息或群聊中 `public + completed` 的消息；closed 群聊在关闭前已经刷入
最后两个话题，因此不会再进入热原文查询。TRPG 群聊也不参与该查询。

## 存档

存档格式版本为 `3`。单聊边界保存最多六个有序 `startIds`，并保存最近三个逻辑轮（包括主动
助手消息与撤回占位）；每个活跃普通群聊保存最近三个完整群聊轮及最近五个话题边界。读档会恢复
这些轮次，按单聊三个热话题、普通群聊两个热话题重新校准向量冷热区，并在删除未来回复步骤前
清理其工具调用。不兼容旧版存档。

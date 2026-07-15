# 群聊第一版 API

## 数据库

项目当前未启用 Flyway。部署前手动执行：

`docs/sql/V20260711__group_chat.sql`

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

## 上下文策略

上下文组装器通过 mode 路由到独立策略：`chat` 使用滚动压缩，`trpg` 不复用滚动压缩，
当前保留独立策略插槽，后续按场景边界实现。模型调用不直接依赖具体压缩实现。

## 当前工具边界

群聊使用基于独立 DeepSeek thinking/non-thinking 模型构建的专属 `ChatClient`。当前不经过
`TopicAwareMessageChatMemoryAdvisor`、`UserChatMemory` 或 `RecordingToolCallingManager`；后续向量检索和工具
应配置在群聊专属 Client 上。

单聊的好感、用户资料和向量工具当前仍使用 `user_chat_history.id` 作为关联键，不能安全地直接用于群聊。
为群聊增加工具时，应以 `group_chat_reply_step.id` / `group_chat_message.id` 作为记录键，使用独立的工具调用记录器。

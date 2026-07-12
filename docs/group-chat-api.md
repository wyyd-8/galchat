# 群聊第一版 API

## 数据库

项目当前未启用 Flyway。部署前手动执行：

`docs/sql/V20260711__group_chat.sql`

## 创建普通群聊

`POST /group-chat/conversations`

```json
{
  "userWorldId": 1,
  "mode": "story",
  "characterIds": [11, 12]
}
```

故事模式调用 `POST /worldevent/story/start` 时会自动创建群聊；返回的
`storyEvent.conversationId` 是后续群聊 API 使用的会话 id。

## 发送消息

`POST /group-chat/conversations/{conversationId}/messages`

响应类型为 `text/event-stream`。

```json
{
  "clientRequestId": "客户端生成的唯一请求id",
  "content": "我们进入地下室。",
  "replyPlan": [
    {"speakerId": 12},
    {"speakerId": 11, "force": true}
  ]
}
```

- `replyPlan == null`：按成员列表顺序回复。
- `replyPlan == []`：只保存用户消息，不触发角色回复。
- 显式顺序中不允许同一角色重复出现。
- `force=true` 可以让被禁用的群聊成员回复。
- 同一 conversation 的 turn 串行执行；后一个角色能看到前一个角色刚完成的回复。

主要事件：

- `turn.accepted`
- `reply.started`
- `reasoning.delta`
- `message.delta`
- `message.completed`
- `reply.failed`
- `turn.completed`

## 查询历史

`GET /group-chat/conversations/{conversationId}/messages?beforeId=&size=50`

每条角色消息同时返回 `content` 和 `thinkingContent`。思考内容来自独立的
`group_chat_thinking` 表，只用于前端展示；群聊上下文组装器不依赖该表，不会把思考内容发送给任何后续模型调用。

## 当前工具边界

群聊使用独立的 DeepSeek thinking/non-thinking 模型 bean，不经过
`TopicAwareMessageChatMemoryAdvisor`、`UserChatMemory` 或 `RecordingToolCallingManager`。

单聊的好感、用户资料和向量工具当前仍使用 `user_chat_history.id` 作为关联键，不能安全地直接用于群聊。
为群聊增加工具时，应以 `group_chat_reply_step.id` / `group_chat_message.id` 作为记录键，使用独立的工具调用记录器。

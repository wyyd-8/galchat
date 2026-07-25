# 单聊与普通群聊共享工具和向量记忆设计

## 目标

普通群聊开放现有的 `VectorTools` 和 `UserCharacterFavorTools`。
`UserCharacterInfoTools` 继续仅供单聊使用。`VectorTools` 不判断调用来自单聊还是群聊；
同一 `userWorldId + characterId` 在两种对话中共享长期记忆检索范围。

TRPG 群聊本次不开放上述工具。

## 工具接入

`ChatGroupAgentPolicy` 注入两个现有工具，并通过 `GroupModelInvocation.tools`
返回。群聊 `ChatClient` 不设置默认工具，具体模式仍由 Agent 插槽决定白名单。

工具调用继续使用现有群聊工具框架：

- 调用记录绑定 `group_chat_reply_step.id`；
- 普通工具调用与结果只重建给调用角色；
- 不写入 `UserChatMemory` 或 `user_chat_tool_call`。

## 统一向量检索

`MutiSearchService.searchInfo` 无论调用来源，都并行查询：

1. 当前角色的单聊历史；
2. 当前角色参与过的群聊归档话题；
3. 当前世界的世界详情；
4. 当前角色可见的世界事件。

四类向量文档与尚未向量化的热原文统一进入现有 reranker，再输出固定数量的最高相关结果。

热原文只读取：

- 当前角色单聊最近三个话题；
- 当前角色仍启用参与的所有 `active + chat` 群聊各自最近两个话题。

热原文按话题组装为 `Document`，沿用 `chat_history` / `group_topic` 来源名称，不写入向量库。
`VectorTools` 不根据调用来自单聊还是群聊排除当前会话；调用模型自行结合已有上下文忽略重复候选。
已关闭群聊不进入热原文查询，因为关闭流程会先把最后两个话题写入向量库。
TRPG 群聊、非公开消息和未完成消息不进入热原文。

## 群聊向量可见性

归档群聊话题写入向量库时增加：

- `userWorldId`
- `visibleCharacters`

`visibleCharacters` 取归档当时群聊中的已启用角色。查询群聊话题时固定使用
`userWorldId + visibleCharacters 包含 characterId` 过滤，不根据调用来源或当前
`groupConversationId` 过滤，也不在查询时读取成员表。

因此：

- 单聊能够检索该角色参与过的群聊内容；
- 一个普通群聊能够检索该角色的单聊内容和其参加过的其他群聊内容；
- 不会检索同一世界中该角色未参与的群聊；
- 群聊关闭或角色退出后，归档时已对该角色可见的内容仍属于其长期记忆。

## 数据与兼容边界

本项目当前不要求兼容旧数据。已有的群聊话题向量没有 `visibleCharacters` 时不会命中新查询；
需要时由外部流程重建向量，不在本次实现中增加回填任务。

## 验证

- Agent 单元测试确认普通群聊恰好开放两个工具且不包含 `UserCharacterInfoTools`，
  TRPG 工具列表保持为空。
- 向量服务测试确认归档文档写入角色可见性元数据。
- 查询测试确认单聊和群聊共用的 `searchInfo` 都会包含群聊话题来源，并按角色过滤。
- 热原文测试确认单聊只读取最近三个话题，群聊只读取角色仍启用参与的
  `active + chat` 会话最近两个公开且已完成的话题。
- 共享检索测试确认热原文与四类向量文档进入同一次 rerank。
- 群聊工具记录和私有上下文重建测试保持通过。
- 执行完整 Maven 测试套件。

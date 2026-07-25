# Topic Window Withdrawal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为单聊与普通群聊实现最多三轮撤回，并让话题边界、向量冷热区和存读档保持一致。

**Architecture:** 用纯计算的 `TopicWindowPolicy` 复用窗口算术；单聊继续用 Redis 边界，群聊继续用数据库边界。撤回服务分别处理各自的数据模型，向量服务仅暴露确定性区间的增删能力。

**Tech Stack:** Java 21、Spring Boot、Spring AI、MyBatis-Plus、PostgreSQL/pgvector、Redis、JUnit 5、Mockito、AssertJ。

## Global Constraints

- 单聊模型上下文为最近 3 个话题，Redis 最多保存 6 个话题起点。
- 普通群聊模型上下文为最近 2 个话题，回滚计算使用最近 5 个话题起点。
- 单聊与普通群聊最多连续撤回 3 轮。
- 群聊撤回只允许 `active` 的 `chat` 模式，TRPG 不支持撤回。
- 存档格式不兼容旧数据，必须保存完整单聊边界队列。
- 向量删除必须按确定性文档 ID 对精确区间执行。

---

### Task 1: 统一窗口算术与单聊边界

**Files:**
- Create: `src/main/java/com/me/galchat/memory/TopicWindowPolicy.java`
- Create: `src/test/java/com/me/galchat/memory/TopicWindowPolicyTest.java`
- Modify: `src/main/java/com/me/galchat/memory/TopicBoundary.java`
- Modify: `src/main/java/com/me/galchat/memory/TopicBoundaryService.java`
- Modify: `src/main/java/com/me/galchat/memory/TopicAwareMessageChatMemoryAdvisor.java`
- Modify: `src/main/java/com/me/galchat/constant/ChatConstant.java`
- Modify: `src/main/java/com/me/galchat/vector/ChatHistoryVectorService.java`
- Modify: `src/test/java/com/me/galchat/memory/TopicBoundaryServiceTest.java`

**Interfaces:**
- Produces: `TopicWindowPolicy(int contextTopicCount, int maxWithdrawRounds)`.
- Produces: `Optional<TopicInterval> intervalToArchiveAfterAppend(List<Long>)`.
- Produces: `Optional<TopicInterval> intervalToDeleteAfterPop(List<Long>)`.
- Produces: `Long contextStart(List<Long>)`.
- Produces: `TopicBoundary(List<Long> startIds, Long lastCheckedMessageId)`.

- [ ] **Step 1: Write failing window-policy and six-boundary Redis tests**

测试字面量序列 `[1,2,3,4,5,6,7]`：单聊追加后归档 `[4,5)`、保留 `[2,3,4,5,6,7]`、上下文从 `5` 开始；弹出 `7` 后删除 `[4,5)`。

- [ ] **Step 2: Run the focused tests and verify the missing API failures**

Run: `./mvnw test -Dtest=TopicWindowPolicyTest,TopicBoundaryServiceTest`

- [ ] **Step 3: Implement the pure policy, list-based boundary JSON, and exact vector deletion**

`TopicBoundaryService` 在新边界追加后调用策略决定归档区间；撤回时仅在移除边界后调用策略决定删除区间。`TopicAwareMessageChatMemoryAdvisor` 使用倒数第三个边界作为窗口起点，检索查询使用最后一个边界作为当前话题起点。

- [ ] **Step 4: Run the focused tests**

Run: `./mvnw test -Dtest=TopicWindowPolicyTest,TopicBoundaryServiceTest`

### Task 2: 单聊逻辑轮撤回

**Files:**
- Modify: `src/main/java/com/me/galchat/service/impl/UserChatHistoryServiceImpl.java`
- Create or modify: `src/test/java/com/me/galchat/service/impl/UserChatHistoryServiceImplTest.java`

**Interfaces:**
- Consumes: `TopicBoundaryService.rollbackAfterWithdraw(..., lastRemainingMessageId)`.
- Produces: `withdrawLatestUserMessage` 对用户轮、主动助手轮和撤回占位统一倒序处理。

- [ ] **Step 1: Write failing tests for proactive-message precedence and non-boundary withdrawal**

测试最新主动助手消息被单独标记 `withdrawn`；其存在时不会越过它撤回用户轮；未命中边界时不会删除向量。

- [ ] **Step 2: Run the focused test and verify failure**

Run: `./mvnw test -Dtest=UserChatHistoryServiceImplTest`

- [ ] **Step 3: Implement logical marker selection and per-kind cleanup**

候选查询包含普通用户消息、未关联主动助手消息和 `withdrawn` 占位。用户轮删除关联数据，主动助手轮只清空自身，二者都保留锚点占位并更新最近聊天信息。

- [ ] **Step 4: Run the focused tests**

Run: `./mvnw test -Dtest=UserChatHistoryServiceImplTest`

### Task 3: 普通群聊整轮撤回

**Files:**
- Create: `src/main/java/com/me/galchat/service/impl/GroupChatWithdrawalService.java`
- Create: `src/test/java/com/me/galchat/service/impl/GroupChatWithdrawalServiceTest.java`
- Modify: `src/main/java/com/me/galchat/controller/GroupChatController.java`
- Modify: `src/main/java/com/me/galchat/constant/GroupChatConstant.java`
- Modify: `src/main/java/com/me/galchat/groupchat/context/GroupTopicService.java`
- Modify: `src/main/java/com/me/galchat/vector/GroupTopicVectorService.java`
- Modify: `src/test/java/com/me/galchat/groupchat/context/GroupTopicServiceTest.java`

**Interfaces:**
- Produces: `POST /group-chat/conversations/{conversationId}/withdraw`.
- Produces: `GroupTopicService.rollbackTurnBoundary(conversation, triggerSequence)`.

- [ ] **Step 1: Write failing tests for mode guard, three-round limit, whole-turn cleanup, and boundary rollback**

测试 TRPG 被拒绝；连续三个 `withdrawn` 后被拒绝；普通群聊删除消息/步骤/工具与好感日志、重置计划项、保留 `withdrawn` turn；删除触发序列边界时精确删除重新进入热窗口的向量。

- [ ] **Step 2: Run the focused tests and verify failure**

Run: `./mvnw test -Dtest=GroupChatWithdrawalServiceTest,GroupTopicServiceTest`

- [ ] **Step 3: Implement the withdrawal transaction and five-boundary calculations**

服务先获取群聊锁，再在事务内清理整轮。`group_chat_topic` 保留完整历史，读取最近五个边界用于回滚，窗口起点仍取最近两个中的较旧者。

- [ ] **Step 4: Run the focused tests**

Run: `./mvnw test -Dtest=GroupChatWithdrawalServiceTest,GroupTopicServiceTest`

### Task 4: 关闭群聊前冲刷尾部话题

**Files:**
- Modify: `src/main/java/com/me/galchat/groupchat/context/GroupTopicService.java`
- Modify: `src/main/java/com/me/galchat/service/impl/GroupConversationLifecycleService.java`
- Modify: `src/test/java/com/me/galchat/service/impl/GroupConversationLifecycleServiceTest.java`

**Interfaces:**
- Produces: `GroupTopicService.flushOpenTopics(GroupConversation)`.

- [ ] **Step 1: Write a failing test that close archives both hot topics before setting closed**

使用两个尾部边界和最后一条完成消息，断言归档 `[previous,current)` 与 `[current,lastSequence+1)`。

- [ ] **Step 2: Run the focused test and verify failure**

Run: `./mvnw test -Dtest=GroupConversationLifecycleServiceTest`

- [ ] **Step 3: Invoke the tail flush while holding the existing conversation lock**

仅普通群聊执行话题冲刷；空会话不写向量。

- [ ] **Step 4: Run the focused test**

Run: `./mvnw test -Dtest=GroupConversationLifecycleServiceTest`

### Task 5: 存档边界与最近逻辑轮

**Files:**
- Modify: `src/main/java/com/me/galchat/domain/dto/UserWorldSaveSnapshotDTO.java`
- Modify: `src/main/java/com/me/galchat/service/impl/UserWorldSaveServiceImpl.java`
- Modify: `src/main/java/com/me/galchat/mapper/UserWorldSaveRestoreMapper.java`
- Modify: `src/main/resources/mapper/UserWorldSaveRestoreMapper.xml`
- Create or modify: `src/test/java/com/me/galchat/service/impl/UserWorldSaveServiceImplTest.java`

**Interfaces:**
- Produces: `TopicBoundarySnapshot.startIds`.
- Produces: recent three single logical-round snapshots.
- Produces: recent three ordinary-group-turn snapshots plus recent five topic rows.

- [ ] **Step 1: Write failing serialization and restore tests**

断言快照只包含 `startIds`；最近轮包含主动助手与撤回占位；群聊快照包含 turn、message、step、tool、favor 和 topic 行；读档可恢复被撤回的旧 ID 行。

- [ ] **Step 2: Run the focused tests and verify failure**

Run: `./mvnw test -Dtest=UserWorldSaveServiceImplTest`

- [ ] **Step 3: Upgrade format version and implement snapshot/restore SQL**

恢复顺序为 turn、step、message、tool、favor、topic；删除顺序相反。读档后单聊以倒数第三边界为热区起点清理向量，群聊以倒数第二边界清理并补写最近五边界中的冷区间。

- [ ] **Step 4: Run the focused tests**

Run: `./mvnw test -Dtest=UserWorldSaveServiceImplTest`

### Task 6: 回归验证

**Files:**
- Modify: `docs/group-chat-api.md`

- [ ] **Step 1: Document the group withdrawal endpoint and constraints**

记录路径、仅普通群聊、整轮语义和最多连续三轮限制。

- [ ] **Step 2: Run all tests**

Run: `./mvnw test`

- [ ] **Step 3: Inspect residual two-boundary names and vector cleanup paths**

Run: `grep -R "previousStartId\\|currentStartId\\|TOPIC_PREVIOUS_START_ID_KEY\\|TOPIC_CURRENT_START_ID_KEY" -n src/main src/test`

Expected: no old persisted-boundary fields remain; derived `currentStartId()` helper references are allowed only if intentionally retained.

- [ ] **Step 4: Review the final diff**

Run: `git diff --check && git status --short`

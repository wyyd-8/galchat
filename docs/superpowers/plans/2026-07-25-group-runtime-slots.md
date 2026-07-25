# Group Runtime Slots Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将群聊模型调用、上下文和行动轮从公共执行服务中剥离，并按 `chat`/`trpg` 绑定成两套可替换 Runtime。

**Architecture:** `GroupChatService` 只保留锁、持久化、串行流式执行和状态处理。`GroupRuntimeRegistry` 按会话 mode 返回一个 `GroupModeRuntime`，Runtime 同时绑定 `GroupTurnPolicy`、`GroupContextPolicy` 和 `GroupAgentPolicy`，从结构上禁止跨模式策略误配。

**Tech Stack:** Java 21、Spring Boot 4、Spring AI `ChatClient`、MyBatis-Plus、Reactor、JUnit 5、Mockito、AssertJ。

## Global Constraints

- 普通群聊现有 SSE 事件顺序和 reasoning 不落库行为保持不变。
- 回复顺序继续由 `GroupReplyPlanService` 提供；战斗继续复用 `COMBAT` 计划，不增加战斗执行器。
- 群聊 Runtime 不依赖 `UserChatMemory`、`TopicAwareMessageChatMemoryAdvisor` 或单聊工具调用记录器。
- 第一版只增加必要策略契约，不引入工作流 DAG、动态插件配置或数据库兼容层。

---

### Task 1: Runtime registry and action plans

**Files:**
- Create: `src/main/java/com/me/galchat/groupchat/runtime/GroupModeRuntime.java`
- Create: `src/main/java/com/me/galchat/groupchat/runtime/GroupRuntimeRegistry.java`
- Create: `src/main/java/com/me/galchat/groupchat/runtime/GroupTurnPolicy.java`
- Create: `src/main/java/com/me/galchat/groupchat/runtime/GroupActionSpec.java`
- Create: `src/test/java/com/me/galchat/groupchat/runtime/GroupRuntimeRegistryTest.java`
- Create: `src/test/java/com/me/galchat/groupchat/runtime/GroupTurnPolicyTest.java`

**Interfaces:**
- Produces: `GroupRuntimeRegistry.require(String mode)`
- Produces: `GroupTurnPolicy.plan(GroupConversation, String source, List<GroupReplyPlanItem>)`

- [x] **Step 1: Write failing registry and action-plan tests**
- [x] **Step 2: Run focused tests and verify compilation fails because the contracts do not exist**
- [x] **Step 3: Implement the contracts and minimal chat/TRPG turn policies**
- [x] **Step 4: Run focused tests and verify they pass**

### Task 2: Context and agent invocation slots

**Files:**
- Create: `src/main/java/com/me/galchat/groupchat/runtime/GroupContextPolicy.java`
- Create: `src/main/java/com/me/galchat/groupchat/runtime/GroupContextMaterial.java`
- Create: `src/main/java/com/me/galchat/groupchat/runtime/GroupAgentPolicy.java`
- Create: `src/main/java/com/me/galchat/groupchat/runtime/GroupModelInvocation.java`
- Modify: `src/main/java/com/me/galchat/service/impl/GroupContextAssembler.java`
- Test: `src/test/java/com/me/galchat/groupchat/runtime/GroupModeRuntimeTest.java`

**Interfaces:**
- Consumes: `GroupActionSpec`
- Produces: mode-specific context material and `ChatClient`/`Prompt` invocation

- [x] **Step 1: Write failing tests proving chat and TRPG bind different slot implementations**
- [x] **Step 2: Verify RED**
- [x] **Step 3: Implement minimal policies and prompt composition**
- [x] **Step 4: Verify GREEN**

### Task 3: Shared engine migration

**Files:**
- Modify: `src/main/java/com/me/galchat/service/impl/GroupChatService.java`
- Modify: `src/main/java/com/me/galchat/domain/po/GroupChatReplyStep.java`
- Modify: `src/test/java/com/me/galchat/service/impl/GroupChatServiceTest.java`
- Modify: `docs/sql/V20260711__group_chat.sql`
- Modify: `src/test/java/com/me/galchat/init/console.sql`

**Interfaces:**
- Consumes: `GroupRuntimeRegistry`, `GroupActionSpec`, `GroupModelInvocation`
- Produces: persisted action type/plan item association while preserving current SSE behavior

- [x] **Step 1: Rewrite the service test against Runtime contracts and verify RED**
- [x] **Step 2: Move action planning and invocation selection out of `GroupChatService`**
- [x] **Step 3: Add `action_type` and `plan_item_id` persistence**
- [x] **Step 4: Verify focused and full tests**

### Task 4: Documentation and final verification

**Files:**
- Modify: `docs/group-chat-api.md`
- Delete when unused: old context router classes and tests

- [x] **Step 1: Document Runtime responsibilities and current TRPG extension boundary**
- [x] **Step 2: Run `./mvnw test`**
- [x] **Step 3: Run `git diff --check` and inspect the final diff**

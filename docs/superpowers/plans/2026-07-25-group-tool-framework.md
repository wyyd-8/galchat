# Group Tool Framework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Build the group-chat tool context, recording, visibility-aware history reconstruction, and favor-log binding framework without registering any concrete tools.

**Architecture:** `GroupChatService` attaches a small trusted `ToolContext` to each model request. A group-only `RecordingGroupToolCallingManager` records standard tool calls against `group_chat_reply_step`, while `GroupToolHistoryAssembler` reconstructs private standard tool messages only for the caller and renders dice references as public synthetic context. Existing group agent policies continue returning empty tool lists.

**Tech Stack:** Java 21, Spring Boot 4, Spring AI 2.0.0-M4, MyBatis-Plus, PostgreSQL, JUnit 5, Mockito, AssertJ

## Global Constraints

- Do not register `VectorTools`, `UserCharacterFavorTools`, `UserCharacterInfoTools`, dice tools, or quick-reference tools in either group `ChatClient` or `GroupAgentPolicy`.
- Group tool execution must not depend on `UserChatMemory`, `TopicAwareMessageChatMemoryAdvisor`, or `RecordingToolCallingManager`.
- Private standard tool results are reconstructed only for the character that owns the reply step.
- Rows linked to `dice_roll_summary.id` are rendered in a public special format instead of standard tool-call/tool-response messages.
- Keep `ToolContext` limited to `worldId`, `userWorldId`, `conversationId`, `characterId`, `replyStepId`, optional `favorSystemStatus`, and optional infrastructure listeners.
- Favor-log lookups must include `bindingType`; the binding index must include both `binding_type` and `binding_chat`.

---

### Task 1: Trusted group ToolContext

**Files:**
- Modify: `src/main/java/com/me/galchat/constant/ChatToolContextConstant.java`
- Create: `src/main/java/com/me/galchat/groupchat/tool/GroupToolContextFactory.java`
- Modify: `src/main/java/com/me/galchat/service/impl/GroupChatService.java`
- Test: `src/test/java/com/me/galchat/groupchat/tool/GroupToolContextFactoryTest.java`
- Test: `src/test/java/com/me/galchat/service/impl/GroupChatServiceTest.java`

**Interfaces:**
- Produces: `Map<String, Object> GroupToolContextFactory.create(GroupConversation, GroupActionSpec, Long replyStepId)`
- Produces constants: `WORLD_ID_KEY`, `GROUP_CONVERSATION_ID_KEY`, `GROUP_REPLY_STEP_ID_KEY`

- [x] **Step 1: Write failing factory and request tests**

```java
Map<String, Object> context = factory.create(conversation, action, 41L);
assertThat(context).containsEntry(ChatToolContextConstant.WORLD_ID_KEY, 2L)
        .containsEntry(ChatToolContextConstant.USER_WORLD_ID_KEY, 1L)
        .containsEntry(ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY, 8L)
        .containsEntry(ChatToolContextConstant.CHARACTER_ID_KEY, 11L)
        .containsEntry(ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 41L)
        .containsEntry(ChatToolContextConstant.FAVOR_SYSTEM_STATUS_KEY, "NORMAL");
```

Also capture the final `Prompt` options in `GroupChatServiceTest` and assert the group context entries are present while `GroupModelInvocation.tools()` remains empty.

- [x] **Step 2: Run tests and verify RED**

Run:

```bash
./mvnw -Dtest=GroupToolContextFactoryTest,GroupChatServiceTest test
```

Expected: compilation/test failure because the factory and constants do not exist.

- [x] **Step 3: Implement the minimal factory and attach it**

`GroupToolContextFactory` copies IDs already available from the conversation/action/step and loads `favorSystemStatus` once while constructing the request context. `GroupChatService.executeStep` calls `.toolContext(...)` on the request spec. Do not change either agent policy's empty tool list.

- [x] **Step 4: Run focused tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=GroupToolContextFactoryTest,GroupChatServiceTest test
```

Expected: PASS.

### Task 2: Independent group tool-call recording

**Files:**
- Create: `src/main/java/com/me/galchat/domain/po/GroupChatToolCall.java`
- Create: `src/main/java/com/me/galchat/mapper/GroupChatToolCallMapper.java`
- Create: `src/main/java/com/me/galchat/groupchat/tool/GroupToolCallStore.java`
- Create: `src/main/java/com/me/galchat/groupchat/tool/RecordingGroupToolCallingManager.java`
- Modify: `src/main/java/com/me/galchat/config/DeepSeekModelConfiguration.java`
- Modify: `docs/sql/V20260711__group_chat.sql`
- Modify: `src/test/java/com/me/galchat/init/console.sql`
- Test: `src/test/java/com/me/galchat/groupchat/tool/RecordingGroupToolCallingManagerTest.java`

**Interfaces:**
- Produces: `void GroupToolCallStore.saveExecution(Long replyStepId, ChatResponse, ToolExecutionResult)`
- Produces: `void GroupToolCallStore.bindDiceSummary(Long replyStepId, String toolCallId, Long diceRollSummaryId)`
- Produces: group model beans using `RecordingGroupToolCallingManager`

- [x] **Step 1: Write a failing recorder test**

Construct a `Prompt` whose `ToolCallingChatOptions.toolContext` contains `GROUP_REPLY_STEP_ID_KEY=41L`, execute the wrapper, and verify:

```java
verify(store).saveExecution(41L, response, executionResult);
verify(delegate).executeToolCalls(prompt, response);
```

Also verify a prompt without the group step ID delegates without recording.

- [x] **Step 2: Run the test and verify RED**

Run:

```bash
./mvnw -Dtest=RecordingGroupToolCallingManagerTest test
```

Expected: compilation failure because the recorder does not exist.

- [x] **Step 3: Implement the table, store, and recorder**

Create `group_chat_tool_call` with:

```text
id, reply_step_id, tool_step_no, tool_call_id, tool_name,
tool_arguments, tool_result, dice_roll_summary_id
```

Use `(reply_step_id, tool_call_id)` as the unique key. Persist assistant tool calls in model-step order and update results from `ToolResponseMessage`. Dice binding is an explicit store method only; no dice tool is implemented.

- [x] **Step 4: Wire only the group manager**

Wrap the base `ToolCallingManager` for `groupDeepSeekThinkingChatModel` and `groupDeepSeekNonThinkingChatModel`. Leave single-chat model wiring unchanged and do not configure tools on group clients.

- [x] **Step 5: Run focused tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=RecordingGroupToolCallingManagerTest test
```

Expected: PASS.

### Task 3: Visibility-aware group tool history

**Files:**
- Create: `src/main/java/com/me/galchat/groupchat/tool/GroupToolHistoryAssembler.java`
- Modify: `src/main/java/com/me/galchat/service/impl/GroupContextAssembler.java`
- Test: `src/test/java/com/me/galchat/groupchat/tool/GroupToolHistoryAssemblerTest.java`
- Modify: `src/test/java/com/me/galchat/service/impl/GroupContextAssemblerTest.java`

**Interfaces:**
- Produces: `Map<Long, List<Message>> GroupToolHistoryAssembler.beforeMessages(List<GroupChatMessage>, Long currentCharacterId)`
- Consumes: `GroupChatToolCallMapper`, `DiceRollSummaryMapper`, `DiceRollResultMapper`

- [x] **Step 1: Write failing private/public visibility tests**

For a reply step owned by character `11`:

```java
assertThat(assembler.beforeMessages(messages, 11L).get(stepId))
        .anyMatch(AssistantMessage.class::isInstance)
        .anyMatch(ToolResponseMessage.class::isInstance);
assertThat(assembler.beforeMessages(messages, 12L).get(stepId)).isEmpty();
```

For a row with `diceRollSummaryId`, assert both characters receive a `UserMessage` containing:

```xml
<dice-roll summary-id="501" status="COMPLETED">
```

and neither receives a standard tool-call message for that row.

- [x] **Step 2: Run tests and verify RED**

Run:

```bash
./mvnw -Dtest=GroupToolHistoryAssemblerTest,GroupContextAssemblerTest test
```

Expected: compilation failure because the assembler does not exist.

- [x] **Step 3: Implement batched reconstruction**

Load all tool rows for the selected messages' non-null reply-step IDs in one query. Group by reply step and `toolStepNo`. Standard rows become `AssistantMessage(toolCalls)` followed by `ToolResponseMessage` only for the owning character. Dice rows load their summary/result records and become public `UserMessage` XML-like blocks.

- [x] **Step 4: Insert reconstructed messages before final visible output**

`GroupContextAssembler.assembleContextFrom` inserts `beforeMessages.get(replyStepId)` immediately before converting each completed public group message. Existing same-speaker role mapping remains unchanged.

- [x] **Step 5: Run focused tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=GroupToolHistoryAssemblerTest,GroupContextAssemblerTest test
```

Expected: PASS.

### Task 4: Favor-log binding type and index

**Files:**
- Modify: `src/main/java/com/me/galchat/domain/po/UserCharacterFavorLog.java`
- Create: `src/main/java/com/me/galchat/constant/FavorBindingType.java`
- Modify: `src/main/java/com/me/galchat/service/IUserCharacterInfoService.java`
- Modify: `src/main/java/com/me/galchat/service/impl/UserCharacterInfoServiceImpl.java`
- Modify: `src/main/java/com/me/galchat/tool/UserCharacterFavorTools.java`
- Modify: `src/main/java/com/me/galchat/service/impl/UserChatHistoryServiceImpl.java`
- Modify: `src/main/java/com/me/galchat/service/impl/UserWorldSaveServiceImpl.java`
- Modify: `src/main/java/com/me/galchat/mapper/UserWorldSaveRestoreMapper.java`
- Modify: `src/main/resources/mapper/UserWorldSaveRestoreMapper.xml`
- Create: `docs/sql/V20260725__favor_binding_type.sql`
- Modify: `src/test/java/com/me/galchat/init/console.sql`
- Test: `src/test/java/com/me/galchat/tool/UserCharacterFavorToolsTest.java`

**Interfaces:**
- Produces: `FavorBindingType.SINGLE_MESSAGE`, `FavorBindingType.GROUP_REPLY_STEP`
- Changes: `updateFavorValue(userWorldId, characterId, favorChange, bindingType, bindingChat)`

- [x] **Step 1: Write a failing favor-tool binding test**

Call the existing favor tool with a single-chat `ToolContext` and verify:

```java
verify(characterService).updateFavorValue(
        1L, 11L, 2, FavorBindingType.SINGLE_MESSAGE, 91L);
```

The group tool is not registered, but a second unit case may provide `GROUP_REPLY_STEP_ID_KEY` and verify the resolver selects `GROUP_REPLY_STEP`.

- [x] **Step 2: Run the test and verify RED**

Run:

```bash
./mvnw -Dtest=UserCharacterFavorToolsTest test
```

Expected: compilation failure because the binding type/signature does not exist.

- [x] **Step 3: Add binding type through the write path**

Add `bindingType` to the entity and service method. `UserCharacterFavorTools` chooses `GROUP_REPLY_STEP` when a group step ID exists; otherwise it uses `SINGLE_MESSAGE` and the existing user-message ID.

- [x] **Step 4: Add binding type to every single-chat read/delete path**

Add `binding_type = SINGLE_MESSAGE` to single-message favor rollback, snapshot collection, and restore deletion. Include `binding_type` in save/restore inserts.

- [x] **Step 5: Replace the binding index**

Use:

```sql
CREATE INDEX idx_user_character_favor_log_binding
    ON user_character_favor_log
       (user_world_id, character_id, binding_type, binding_chat);
```

The previous world/character and single-column binding indexes are replaced because the new composite index covers their current lookup prefixes.

- [x] **Step 6: Run focused tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=UserCharacterFavorToolsTest test
```

Expected: PASS.

### Task 5: Integration verification and documentation

**Files:**
- Modify: `docs/group-chat-api.md`

**Interfaces:**
- Verifies all earlier tasks together.

- [x] **Step 1: Document the dormant framework**

Document the context keys, table association, visibility rules, favor binding index, and explicitly state that both group agent policies still expose empty tool lists.

- [x] **Step 2: Run stale-coupling checks**

Run searches proving group tool/runtime classes do not reference `UserChatMemory`, `TopicAwareMessageChatMemoryAdvisor`, or the single-chat `RecordingToolCallingManager`.

- [x] **Step 3: Run formatting checks**

Run:

```bash
git diff --check
```

Expected: no output.

- [x] **Step 4: Run the complete test suite**

Run:

```bash
./mvnw test
```

Expected: all tests pass.

- [x] **Step 5: Inspect the final diff**

Confirm group clients and both `GroupAgentPolicy` implementations still have no registered/default tools and that unrelated dirty-worktree files were not modified.

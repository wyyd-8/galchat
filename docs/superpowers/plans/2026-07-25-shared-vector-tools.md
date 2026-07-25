# Shared Vector Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Expose the existing vector and favor tools to ordinary group chat and make every `VectorTools` call retrieve both single-chat and role-visible group-chat memories.

**Architecture:** `ChatGroupAgentPolicy` owns the ordinary-group tool whitelist. `GroupTopicVectorService` writes participant visibility into archived topic metadata and provides a role-filtered document query. `MutiSearchService` always adds those group-topic documents to the existing single-chat, world-detail, and world-event retrieval pipeline before one shared rerank.

**Tech Stack:** Java 21, Spring Boot 4, Spring AI 2.0.0-M4, MyBatis-Plus, PostgreSQL/pgvector, JUnit 5, Mockito, AssertJ

## Global Constraints

- Ordinary group chat exposes only `VectorTools` and `UserCharacterFavorTools`.
- `UserCharacterInfoTools` remains single-chat-only.
- TRPG group chat exposes none of these tools.
- `VectorTools` does not branch on call origin; both single and group calls search the same sources.
- Group topic retrieval is limited to `userWorldId` and archived visibility containing `characterId`.
- Do not query group membership during retrieval and do not reconnect group tools to `UserChatMemory`.
- Do not add old-vector backfill or compatibility code.
- Do not commit or stage files because the current `main` worktree already contains user changes.

---

### Task 1: Ordinary group tool whitelist

**Files:**
- Modify: `src/main/java/com/me/galchat/groupchat/runtime/chat/ChatGroupAgentPolicy.java`
- Modify: `src/test/java/com/me/galchat/groupchat/runtime/GroupModeRuntimeTest.java`

**Interfaces:**
- Consumes: existing Spring beans `VectorTools`, `UserCharacterFavorTools`
- Produces: `GroupModelInvocation.tools()` containing exactly those two instances for chat mode

- [x] **Step 1: Write the failing whitelist test**

Update `agentPoliciesOwnModelPromptAndToolSet` to construct the chat policy with tool mocks:

```java
VectorTools vectorTools = mock(VectorTools.class);
UserCharacterFavorTools favorTools = mock(UserCharacterFavorTools.class);
UserCharacterInfoTools infoTools = mock(UserCharacterInfoTools.class);

GroupModelInvocation chat = new ChatGroupAgentPolicy(
        client, assembler, vectorTools, favorTools).prepare(...);

assertThat(chat.tools()).containsExactly(vectorTools, favorTools);
assertThat(chat.tools()).doesNotContain(infoTools);
assertThat(trpg.tools()).isEmpty();
```

- [x] **Step 2: Run the focused test and verify RED**

Run:

```bash
./mvnw -Dtest=GroupModeRuntimeTest test
```

Expected: compilation failure because `ChatGroupAgentPolicy` does not accept the two tools.

- [x] **Step 3: Implement the minimal whitelist**

Add the two tool dependencies to `ChatGroupAgentPolicy` and return:

```java
return new GroupModelInvocation(
        chatClient, new Prompt(messages), List.of(vectorTools, favorTools));
```

Do not modify `TrpgGroupAgentPolicy` or configure default tools on group `ChatClient`.

- [x] **Step 4: Run the focused test and verify GREEN**

Run:

```bash
./mvnw -Dtest=GroupModeRuntimeTest test
```

Expected: PASS.

### Task 2: Persist group-topic participant visibility

**Files:**
- Modify: `src/main/java/com/me/galchat/vector/GroupTopicVectorService.java`
- Create: `src/test/java/com/me/galchat/vector/GroupTopicVectorServiceTest.java`

**Interfaces:**
- Consumes: `GroupChatMemberMapper.selectList(...)`
- Produces: group-topic metadata containing `visibleCharacters: List<Long>`

- [x] **Step 1: Write a failing metadata test**

Create a service test that supplies enabled character members `11` and `12`, archives one topic, captures the document passed to `VectorStore.add`, and asserts:

```java
assertThat(document.getMetadata())
        .containsEntry(VectorConstant.USER_WORLD_ID_METADATA_KEY, 3L)
        .containsEntry(VectorConstant.VISIBLE_CHARACTERS_METADATA_KEY, List.of(11L, 12L));
```

The fixture must mock the existing rewrite `ChatClient` chain, `GroupChatMessageMapper`,
`GroupChatMemberMapper`, `VectorStore`, `DocumentRetriever`, `EmbeddingModel`, and `JdbcTemplate`.

- [x] **Step 2: Run the metadata test and verify RED**

Run:

```bash
./mvnw -Dtest=GroupTopicVectorServiceTest test
```

Expected: compilation failure because the service constructor has no member mapper and written metadata lacks `visibleCharacters`.

- [x] **Step 3: Add visibility metadata**

Inject `GroupChatMemberMapper`. During `addTopic`, select enabled character members for the conversation:

```java
List<Long> visibleCharacters = memberMapper.selectList(
        new LambdaQueryWrapper<GroupChatMember>()
                .eq(GroupChatMember::getConversationId, conversation.getId())
                .eq(GroupChatMember::getActorType, GroupChatConstant.ACTOR_CHARACTER)
                .eq(GroupChatMember::getEnabled, true)
                .orderByAsc(GroupChatMember::getPosition)
                .orderByAsc(GroupChatMember::getId))
        .stream()
        .map(GroupChatMember::getActorId)
        .distinct()
        .toList();
```

Return without writing a vector when the list is empty. Otherwise store it under
`VectorConstant.VISIBLE_CHARACTERS_METADATA_KEY`.

- [x] **Step 4: Run the metadata test and verify GREEN**

Run:

```bash
./mvnw -Dtest=GroupTopicVectorServiceTest test
```

Expected: PASS.

### Task 3: Query group-topic memory by role

**Files:**
- Modify: `src/main/java/com/me/galchat/constant/VectorConstant.java`
- Modify: `src/main/java/com/me/galchat/vector/GroupTopicVectorService.java`
- Modify: `src/test/java/com/me/galchat/vector/GroupTopicVectorServiceTest.java`

**Interfaces:**
- Produces: `List<Document> GroupTopicVectorService.queryGroupTopics(Long userWorldId, Long characterId, String question)`
- Produces constants: `GROUP_TOPIC_TOP_K`, `GROUP_TOPIC_DISTANCE_THRESHOLD`, `GROUP_TOPIC_SOURCE`

- [x] **Step 1: Write a failing role-filter query test**

Mock `EmbeddingModel.embed("仓库钥匙")` and `JdbcTemplate.query`. Capture the SQL and arguments passed by:

```java
service.queryGroupTopics(3L, 11L, "仓库钥匙");
```

Assert the SQL contains both JSONB filters and arguments contain:

```text
{"userWorldId":3}
[11]
```

- [x] **Step 2: Run the query test and verify RED**

Run:

```bash
./mvnw -Dtest=GroupTopicVectorServiceTest test
```

Expected: compilation failure because `queryGroupTopics` does not exist.

- [x] **Step 3: Implement the PostgreSQL role-filtered query**

Inject `EmbeddingModel` and `JdbcTemplate`, then implement the same pgvector/JSONB pattern used by
`WorldEventVectorService`:

```sql
SELECT *, embedding <=> ? AS distance
FROM public.group_topic_vector_store
WHERE embedding <=> ? < ?
  AND metadata::jsonb @> ?::jsonb
  AND metadata::jsonb -> 'visibleCharacters' @> ?::jsonb
ORDER BY distance
LIMIT ?
```

Map rows back to `Document`, preserving metadata and distance score. Validate non-null IDs and nonblank query with `Assert`.

- [x] **Step 4: Run the query test and verify GREEN**

Run:

```bash
./mvnw -Dtest=GroupTopicVectorServiceTest test
```

Expected: PASS.

### Task 4: Add group topics to the shared vector pipeline

**Files:**
- Modify: `src/main/java/com/me/galchat/vector/MutiSearchService.java`
- Modify: `src/test/java/com/me/galchat/vector/MutiSearchServiceTest.java`

**Interfaces:**
- Consumes: `GroupTopicVectorService.queryGroupTopics(userWorldId, characterId, query)`
- Produces: shared `searchInfo` results reranked across four vector sources

- [x] **Step 1: Write a failing shared-search test**

Add a `searchInfoIncludesSingleAndGroupChatMemoriesForTheSameCharacter` test. Return one document from
each source, make the reranker return all documents, and assert:

```java
assertThat(result)
        .contains("来源: " + VectorConstant.CHAT_HISTORY_SOURCE)
        .contains("来源: " + VectorConstant.GROUP_TOPIC_SOURCE)
        .contains("single memory")
        .contains("group memory");
verify(groupTopicVectorService).queryGroupTopics(1L, 2L, "query");
```

Use `ToolContext` only for the existing `searchInfo(String, ToolContext)` extraction test if needed;
the core test calls `searchInfo(1L, 2L, "query")`, proving behavior is independent of call origin.

- [x] **Step 2: Run the shared-search test and verify RED**

Run:

```bash
./mvnw -Dtest=MutiSearchServiceTest test
```

Expected: assertion/verification failure because group topics are not queried.

- [x] **Step 3: Add the fourth asynchronous source**

Inject `GroupTopicVectorService` and add:

```java
CompletableFuture<List<Document>> groupTopicFuture = CompletableFuture.supplyAsync(() ->
        queryWithSource(
                () -> groupTopicVectorService.queryGroupTopics(userWorldId, characterId, query),
                VectorConstant.GROUP_TOPIC_SOURCE));
```

Add its documents before the existing shared rerank. Do not inspect `groupConversationId` or change
`VectorTools`.

- [x] **Step 4: Run the shared-search test and verify GREEN**

Run:

```bash
./mvnw -Dtest=MutiSearchServiceTest test
```

Expected: PASS.

### Task 5: Documentation and full verification

**Files:**
- Modify: `docs/group-chat-api.md`

**Interfaces:**
- Verifies all earlier deliverables together.

- [x] **Step 1: Update the tool boundary documentation**

Document that ordinary group chat now exposes vector and favor tools, not user-info tools; vector
retrieval is shared between single and group chat and filters group topics by archived role visibility.
Keep TRPG documented as having no tools in this version.

- [x] **Step 2: Run stale-coupling and whitelist checks**

Run:

```bash
grep -R -nE "UserChatMemory|RecordingToolCallingManager" \
  src/main/java/com/me/galchat/groupchat \
  src/main/java/com/me/galchat/vector/GroupTopicVectorService.java
grep -R -n "UserCharacterInfoTools" \
  src/main/java/com/me/galchat/groupchat
```

Expected: no output.

- [x] **Step 3: Run formatting checks**

Run:

```bash
git diff --check
```

Expected: no whitespace errors.

- [x] **Step 4: Run the complete test suite**

Run:

```bash
./mvnw test
```

Expected: all tests pass.

- [x] **Step 5: Inspect the final diff**

Confirm:

- chat mode exposes exactly two tools;
- TRPG exposes none;
- every `searchInfo` call queries group topics;
- group topic queries filter both `userWorldId` and `visibleCharacters`;
- unrelated dirty-worktree files were not edited or removed.

### Task 6: Add active hot source text to VectorTools retrieval

**Files:**
- Create: `src/main/java/com/me/galchat/vector/RecentChatMemoryService.java`
- Create: `src/test/java/com/me/galchat/vector/RecentChatMemoryServiceTest.java`
- Modify: `src/main/java/com/me/galchat/mapper/GroupConversationMapper.java`
- Modify: `src/main/java/com/me/galchat/vector/MutiSearchService.java`
- Modify: `src/test/java/com/me/galchat/vector/MutiSearchServiceTest.java`

**Interfaces:**
- Produces: `List<Document> RecentChatMemoryService.queryRecentMemories(Long userWorldId, Long characterId)`
- Consumes: the single-chat Redis boundary queue, raw single-chat rows, active ordinary-group membership,
  the latest two group-topic rows, and public completed group messages

- [x] **Step 1: Write failing hot-memory tests**

Add tests proving that the service returns one raw candidate per single-chat topic from the newest three
boundaries, and one candidate per group topic from the newest two boundaries of conversations returned by
`selectActiveChatByCharacter`.

- [x] **Step 2: Run the focused tests and verify RED**

Run:

```bash
./mvnw -Dtest=RecentChatMemoryServiceTest,MutiSearchServiceTest test
```

Expected: compilation fails because `RecentChatMemoryService` and
`GroupConversationMapper.selectActiveChatByCharacter` do not exist.

- [x] **Step 3: Implement raw topic assembly**

Read the single boundary queue through `TopicBoundaryService`, select visible single-chat rows from the
newest three topics, and split them at their exact boundary IDs. Select only `active + chat` group
conversations where the character member is enabled, then assemble public completed messages from their
newest two topic boundaries. Attach the existing source and range metadata to every raw `Document`.

- [x] **Step 4: Merge raw candidates into the shared rerank**

Inject `RecentChatMemoryService` into `MutiSearchService`. `searchInfo` adds its candidates before the
single shared rerank; `searchBeforeChat` remains unchanged.

- [x] **Step 5: Run focused and full verification**

Run:

```bash
./mvnw -Dtest=RecentChatMemoryServiceTest,MutiSearchServiceTest test
./mvnw test
git diff --check
```

Expected: all tests pass and no whitespace errors are introduced.

# Reply Plan Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将群聊回复顺序改造成可复用的无状态 Plan，并在每次发送消息时固化为可存档、可恢复、与撤回解耦的 Turn/ReplyStep 执行快照。

**Architecture:** `GroupReplyPlan` 保存有序分组队列，只由用户或场景/战斗控制器修改；一次 Turn 只读取第一个分组。`GroupChatTurn` 和预创建的 `GroupChatReplyStep` 保存当轮完整顺序及执行状态，模型调用之后不再访问 Plan。`GroupReplyPlanSnapshotService` 将 Active Plan 和最多一层 Resume Plan序列化进 `user_world_save.snapshot`，读档时使用新数据库 ID 重建。

**Tech Stack:** Java 21、Spring Boot 4.0.5、Spring AI 2.0.0-M4、Project Reactor、MyBatis-Plus 3.5.x、PostgreSQL、Redis/Redisson、JUnit 5、Mockito、AssertJ。

## Global Constraints

- 不兼容旧数据，直接更新当前建表 SQL 和测试数据库结构。
- Plan 和 PlanItem 不保存 `pending/running/completed` 等执行状态。
- 一次 Turn 只执行 Plan 的第一个分组；Turn 完成不自动推进。
- Scene/Combat 只能由显式 `advanceGroup` 推进；USER Plan 不允许推进。
- 第一版最多保存 Active Plan 和一层 Resume Plan，不支持嵌套战斗。
- 当前分组最多 12 个项目，整个 Plan 最多 200 个项目。
- 跑团模式不支持撤回；普通群聊最多连续撤回三轮。
- 存档和读档前必须持有全部未结束群聊锁，并确认不存在非终态 Turn。
- 不允许对执行到一半的流式回复存档或断点续跑。
- 思考内容只通过流式事件返回，不写入后端。

---

## File Structure

### Domain and runtime contracts

- Modify `src/main/java/com/me/galchat/domain/po/GroupReplyPlanItem.java`: 删除执行状态。
- Modify `src/main/java/com/me/galchat/domain/po/GroupChatTurn.java`: 将 `policy` 改为 `planSource`，增加 `planContextId`。
- Modify `src/main/java/com/me/galchat/domain/po/GroupChatReplyStep.java`: 删除 PlanItem 外键，增加分组与顺序快照字段。
- Modify `src/main/java/com/me/galchat/domain/vo/GroupReplyPlanVO.java`: Plan 项不再返回状态。
- Modify `src/main/java/com/me/galchat/domain/vo/GroupChatEvent.java`: 返回 ReplyStep 的分组与顺序，不再返回 `planItemId`。
- Modify `src/main/java/com/me/galchat/groupchat/runtime/GroupActionSpec.java`: Action 自包含分组快照数据。
- Create `src/main/java/com/me/galchat/groupchat/runtime/GroupReplyPlanSelection.java`: 封装 Active Plan 来源、上下文和当前分组。

### Services

- Modify `src/main/java/com/me/galchat/service/impl/GroupReplyPlanService.java`: 无状态 Plan CRUD、当前分组选择、显式推进。
- Modify `src/main/java/com/me/galchat/service/impl/GroupChatService.java`: 创建快照并只依赖 ReplyStep 执行。
- Create `src/main/java/com/me/galchat/service/impl/GroupTurnRecoveryService.java`: 收敛异常和进程中断遗留的 Turn/Step/Message。
- Modify `src/main/java/com/me/galchat/service/impl/GroupChatWithdrawalService.java`: 删除 Plan 回写依赖。
- Modify `src/main/java/com/me/galchat/service/impl/GroupConversationLifecycleService.java`: 关闭前拒绝非终态 Turn。
- Create `src/main/java/com/me/galchat/service/impl/GroupReplyPlanSnapshotService.java`: 捕获和恢复世界存档中的 Plan。
- Modify `src/main/java/com/me/galchat/service/impl/UserWorldSaveServiceImpl.java`: 接入 Plan 快照和非终态 Turn 校验。

### HTTP and persistence

- Modify `src/main/java/com/me/galchat/controller/GroupChatController.java`: 增加显式推进接口。
- Modify `src/main/java/com/me/galchat/domain/dto/UserWorldSaveSnapshotDTO.java`: 增加嵌套 Plan 配置。
- Modify `docs/sql/V20260711__group_chat.sql`: 更新 PostgreSQL 表字段。
- Modify `src/test/java/com/me/galchat/init/console.sql`: 同步测试数据库结构。

### Tests

- Modify `src/test/java/com/me/galchat/service/impl/GroupReplyPlanServiceTest.java`.
- Modify `src/test/java/com/me/galchat/service/impl/GroupChatServiceTest.java`.
- Modify `src/test/java/com/me/galchat/service/impl/GroupChatWithdrawalServiceTest.java`.
- Modify `src/test/java/com/me/galchat/service/impl/GroupConversationLifecycleServiceTest.java`.
- Create `src/test/java/com/me/galchat/service/impl/GroupTurnRecoveryServiceTest.java`.
- Create `src/test/java/com/me/galchat/service/impl/GroupReplyPlanSnapshotServiceTest.java`.

---

### Task 1: Make Reply Plans Reusable and Select Only the Current Group

**Files:**
- Create: `src/main/java/com/me/galchat/groupchat/runtime/GroupReplyPlanSelection.java`
- Modify: `src/main/java/com/me/galchat/domain/po/GroupReplyPlanItem.java`
- Modify: `src/main/java/com/me/galchat/domain/vo/GroupReplyPlanVO.java`
- Modify: `src/main/java/com/me/galchat/service/impl/GroupReplyPlanService.java`
- Modify: `docs/sql/V20260711__group_chat.sql`
- Modify: `src/test/java/com/me/galchat/init/console.sql`
- Test: `src/test/java/com/me/galchat/service/impl/GroupReplyPlanServiceTest.java`

**Interfaces:**
- Produces: `GroupReplyPlanSelection GroupReplyPlanService.currentGroupForExecution(GroupConversation conversation)`.
- Produces: `record GroupReplyPlanSelection(String source, Long contextId, String groupKey, String groupName, Integer groupOrder, List<GroupReplyPlanItem> items)`.
- Removes: `markRunning`, `markCompleted`, `resetPending`, `pendingItemsForExecution`.

- [ ] **Step 1: Replace status-preservation tests with reusable/current-group tests**

Add tests proving that only the first ordered group is selected and that selecting it twice does not mutate PlanItem:

```java
@Test
void currentGroupIsReusableAndDoesNotSelectFutureGroups() {
    GroupReplyPlan firstPlan = new GroupReplyPlan()
            .setId(10L).setSource(GroupChatConstant.PLAN_SOURCE_SCENE).setContextId(100L);
    GroupReplyPlanItem current = item(11L, 10L, "scene:1", 1, 1, 9L);
    GroupReplyPlanItem future = item(12L, 10L, "scene:2", 2, 1, 8L);
    when(fixture.planMapper.selectById(10L)).thenReturn(firstPlan);
    when(fixture.itemMapper.selectList(any())).thenReturn(List.of(current, future));

    GroupReplyPlanSelection first = fixture.service.currentGroupForExecution(conversation(10L));
    GroupReplyPlanSelection second = fixture.service.currentGroupForExecution(conversation(10L));

    assertThat(first.items()).extracting(GroupReplyPlanItem::getId).containsExactly(11L);
    assertThat(second.items()).extracting(GroupReplyPlanItem::getId).containsExactly(11L);
    verify(fixture.itemMapper, never()).updateById(any());
}
```

Update VO assertions so `GroupReplyPlanVO.Item` contains only `id/order/actorType/actorId`.

- [ ] **Step 2: Run the focused test and verify failure**

Run:

```bash
./mvnw -Dtest=GroupReplyPlanServiceTest test
```

Expected: compilation or assertion failure because PlanItem still has status and `currentGroupForExecution` does not exist.

- [ ] **Step 3: Add the selection record and remove Plan execution state**

Create:

```java
public record GroupReplyPlanSelection(
        String source,
        Long contextId,
        String groupKey,
        String groupName,
        Integer groupOrder,
        List<GroupReplyPlanItem> items
) {
}
```

In `GroupReplyPlanService`, load all items ordered by `groupOrder/itemOrder/id`, take the first
item's `groupKey`, and return only items with that key:

```java
public GroupReplyPlanSelection currentGroupForExecution(GroupConversation conversation) {
    GroupReplyPlan plan = activePlan(conversation);
    if (plan == null) {
        throw new UserRequestException("当前群聊没有可执行的回复计划");
    }
    List<GroupReplyPlanItem> ordered = orderedItems(plan.getId());
    if (ordered.isEmpty()) {
        throw new UserRequestException("当前回复计划没有可执行分组");
    }
    GroupReplyPlanItem first = ordered.getFirst();
    List<GroupReplyPlanItem> current = ordered.stream()
            .filter(item -> first.getGroupKey().equals(item.getGroupKey()))
            .toList();
    return new GroupReplyPlanSelection(
            plan.getSource(), plan.getContextId(),
            first.getGroupKey(), first.getGroupName(), first.getGroupOrder(), current);
}
```

Delete PlanItem status reads/writes and status preservation during `replaceLocked`. Keep Plan replacement
structural: delete old items, insert request groups with no status.

- [ ] **Step 4: Update persistence definitions**

Remove `status VARCHAR(20) NOT NULL` from `group_reply_plan_item` in both SQL files and remove
the Java field. Change `GroupReplyPlanVO.Item` to:

```java
public static class Item {
    private Long id;
    private Integer order;
    private String actorType;
    private Long actorId;
}
```

- [ ] **Step 5: Run focused tests**

Run:

```bash
./mvnw -Dtest=GroupReplyPlanServiceTest test
```

Expected: PASS; repeated selection performs no `updateById`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/me/galchat/groupchat/runtime/GroupReplyPlanSelection.java \
  src/main/java/com/me/galchat/domain/po/GroupReplyPlanItem.java \
  src/main/java/com/me/galchat/domain/vo/GroupReplyPlanVO.java \
  src/main/java/com/me/galchat/service/impl/GroupReplyPlanService.java \
  docs/sql/V20260711__group_chat.sql \
  src/test/java/com/me/galchat/init/console.sql \
  src/test/java/com/me/galchat/service/impl/GroupReplyPlanServiceTest.java
git commit -m "refactor: make group reply plans reusable"
```

---

### Task 2: Add Explicit Group Advancement and Tight Plan Validation

**Files:**
- Modify: `src/main/java/com/me/galchat/service/impl/GroupReplyPlanService.java`
- Modify: `src/main/java/com/me/galchat/controller/GroupChatController.java`
- Test: `src/test/java/com/me/galchat/service/impl/GroupReplyPlanServiceTest.java`

**Interfaces:**
- Produces: `GroupReplyPlanVO GroupReplyPlanService.advanceGroup(Long conversationId)`.
- Produces: `POST /group-chat/conversations/{conversationId}/reply-plan/advance`.
- Consumes: `currentGroupForExecution` from Task 1.

- [ ] **Step 1: Write failing advancement and validation tests**

Cover all terminal behaviors:

```java
@Test
void advancingSceneDeletesOnlyCurrentGroup() {
    // scene:1 and scene:2 exist; advance removes scene:1 rows only.
    GroupReplyPlanVO result = fixture.service.advanceGroup(7L);
    assertThat(result.getGroups()).extracting(GroupReplyPlanVO.Group::getKey)
            .containsExactly("scene:2");
}

@Test
void advancingLastCombatGroupRestoresResumePlan() {
    GroupReplyPlanVO result = fixture.service.advanceGroup(7L);
    assertThat(result.getSource()).isEqualTo(GroupChatConstant.PLAN_SOURCE_SCENE);
    assertThat(conversation.getActiveReplyPlanId()).isEqualTo(10L);
}

@Test
void userPlanCannotAdvance() {
    assertThatThrownBy(() -> fixture.service.advanceGroup(7L))
            .isInstanceOf(UserRequestException.class)
            .hasMessageContaining("USER");
}
```

Also test:

- deleting a USER Plan recreates the ordinary chat default order;
- deleting a Scene Plan leaves the TRPG conversation without an Active Plan;
- deleting a Combat Plan restores its Resume Scene Plan;
- USER rejected in TRPG mode.
- SCENE/COMBAT rejected in chat mode.
- SCENE/COMBAT requires `contextId`.
- every actor must be an enabled member.
- current group size 13 is rejected while total size up to 200 remains legal.

- [ ] **Step 2: Run the focused test and verify failure**

```bash
./mvnw -Dtest=GroupReplyPlanServiceTest test
```

Expected: FAIL because `advanceGroup` and mode/member validation do not exist.

- [ ] **Step 3: Implement explicit advancement**

Inside the conversation lock and one transaction:

```java
public GroupReplyPlanVO advanceGroup(Long conversationId) {
    GroupConversation conversation = conversationService.requireActive(conversationId);
    OwnedLock lock = requireLock(conversationId);
    try {
        return transactionTemplate.execute(status -> advanceLocked(conversation));
    } finally {
        lockService.unlock(lock);
    }
}
```

`advanceLocked` deletes every item sharing the first ordered item's `groupKey`.

- If items remain, return the same Plan with its next group first.
- If the final Combat group was removed, delete Combat Plan and restore `resumePlanId`.
- If the final Scene group was removed, delete the Scene Plan and set
  `conversation.activeReplyPlanId = null`.
- Reject USER advancement.

Update `finishActive` with the same source-specific terminal behavior: USER recreates the default USER
Plan, SCENE leaves no Active Plan, and COMBAT restores Resume Plan.

- [ ] **Step 4: Implement source, member and size validation**

Validation must call:

```java
conversationService.checkReplyMember(
        conversation.getId(), actorType, actorId, false);
```

Count items per group and reject a group above `GroupChatConstant.MAX_REPLY_STEPS`; retain the total
Plan limit of 200. Require unique group keys and unique `(actorType, actorId)` within each group.

- [ ] **Step 5: Add the controller endpoint and run tests**

```java
@PostMapping("/conversations/{conversationId}/reply-plan/advance")
public Result advanceReplyPlan(@PathVariable Long conversationId) {
    return Result.success(replyPlanService.advanceGroup(conversationId));
}
```

Run:

```bash
./mvnw -Dtest=GroupReplyPlanServiceTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/me/galchat/service/impl/GroupReplyPlanService.java \
  src/main/java/com/me/galchat/controller/GroupChatController.java \
  src/test/java/com/me/galchat/service/impl/GroupReplyPlanServiceTest.java
git commit -m "feat: add explicit reply plan group advancement"
```

---

### Task 3: Materialize Each Turn as a Self-Contained Reply Snapshot

**Files:**
- Modify: `src/main/java/com/me/galchat/domain/po/GroupChatTurn.java`
- Modify: `src/main/java/com/me/galchat/domain/po/GroupChatReplyStep.java`
- Modify: `src/main/java/com/me/galchat/domain/vo/GroupChatEvent.java`
- Modify: `src/main/java/com/me/galchat/groupchat/runtime/GroupActionSpec.java`
- Modify: `src/main/java/com/me/galchat/groupchat/runtime/GroupTurnPolicy.java`
- Modify: `src/main/java/com/me/galchat/groupchat/runtime/chat/ChatGroupTurnPolicy.java`
- Modify: `src/main/java/com/me/galchat/groupchat/runtime/trpg/TrpgGroupTurnPolicy.java`
- Modify: `src/main/java/com/me/galchat/service/impl/GroupChatService.java`
- Modify: `docs/sql/V20260711__group_chat.sql`
- Modify: `src/test/java/com/me/galchat/init/console.sql`
- Test: `src/test/java/com/me/galchat/service/impl/GroupChatServiceTest.java`

**Interfaces:**
- Consumes: `GroupReplyPlanSelection currentGroupForExecution(...)`.
- Changes: `GroupTurnPolicy.plan(GroupConversation, GroupReplyPlanSelection)`.
- Produces: self-contained `GroupActionSpec` with action, group, order and actor fields.

- [ ] **Step 1: Write failing snapshot and reuse tests**

Capture inserted Turn and ReplyStep:

```java
assertThat(turnCaptor.getValue())
        .extracting(GroupChatTurn::getPlanSource, GroupChatTurn::getPlanContextId)
        .containsExactly(GroupChatConstant.PLAN_SOURCE_SCENE, 100L);

assertThat(stepCaptor.getValue())
        .extracting(
                GroupChatReplyStep::getGroupKey,
                GroupChatReplyStep::getGroupName,
                GroupChatReplyStep::getGroupOrder,
                GroupChatReplyStep::getItemOrder,
                GroupChatReplyStep::getSpeakerId)
        .containsExactly("scene:100", "地下室", 1, 2, 9L);
```

Invoke `service.chat` twice using the same mocked selection and assert both calls create the same ordered
snapshot while `GroupReplyPlanService` receives no status-changing call.

Update event assertions to check `groupKey/groupOrder/itemOrder`, not `planItemId`.

- [ ] **Step 2: Run the focused test and verify failure**

```bash
./mvnw -Dtest=GroupChatServiceTest test
```

Expected: FAIL because Turn/Step/Event do not contain snapshot fields.

- [ ] **Step 3: Make ActionSpec self-contained**

Replace the record with:

```java
public record GroupActionSpec(
        String actionType,
        String actorType,
        Long actorId,
        String groupKey,
        String groupName,
        Integer groupOrder,
        Integer itemOrder
) {
}
```

Change the policy interface:

```java
List<GroupActionSpec> plan(
        GroupConversation conversation,
        GroupReplyPlanSelection selection);
```

Both policies map selection items to ActionSpec and copy group/order fields. The TRPG policy derives
`actionType` from `selection.source()`.

- [ ] **Step 4: Update Turn, Step and Event persistence**

Use these fields:

```java
// GroupChatTurn
private String planSource;
private Long planContextId;

// GroupChatReplyStep
private String groupKey;
private String groupName;
private Integer groupOrder;
private Integer itemOrder;
```

Remove `policy` and `planItemId`. In SQL, rename `policy` to `plan_source`, add
`plan_context_id`, remove `plan_item_id`, and add the four group/order columns.

- [ ] **Step 5: Refactor prepareTurn to persist the snapshot once**

`prepareTurn` must:

1. call `currentGroupForExecution`;
2. call `turnPolicy.plan(conversation, selection)`;
3. validate actors and at most 12 actions;
4. insert Turn with `planSource/planContextId`;
5. insert every ReplyStep with all snapshot fields.

After `prepareTurn` returns, neither `executeStep` nor any finalizer may call `GroupReplyPlanService`.
Simplify records:

```java
private record PreparedTurn(
        GroupChatTurn turn,
        GroupChatMessage userMessage,
        List<PreparedAction> actions) {
}

private record PreparedAction(
        GroupActionSpec action,
        GroupChatReplyStep step) {
}
```

- [ ] **Step 6: Run focused tests**

```bash
./mvnw -Dtest=GroupChatServiceTest test
```

Expected: PASS; two Turns reuse one Plan selection and persist independent snapshots.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/me/galchat/domain/po/GroupChatTurn.java \
  src/main/java/com/me/galchat/domain/po/GroupChatReplyStep.java \
  src/main/java/com/me/galchat/domain/vo/GroupChatEvent.java \
  src/main/java/com/me/galchat/groupchat/runtime/GroupActionSpec.java \
  src/main/java/com/me/galchat/groupchat/runtime/GroupTurnPolicy.java \
  src/main/java/com/me/galchat/groupchat/runtime/chat/ChatGroupTurnPolicy.java \
  src/main/java/com/me/galchat/groupchat/runtime/trpg/TrpgGroupTurnPolicy.java \
  src/main/java/com/me/galchat/service/impl/GroupChatService.java \
  docs/sql/V20260711__group_chat.sql \
  src/test/java/com/me/galchat/init/console.sql \
  src/test/java/com/me/galchat/service/impl/GroupChatServiceTest.java
git commit -m "refactor: snapshot reply plans into group turns"
```

---

### Task 4: Make Step Finalization Atomic and Recover Interrupted Turns

**Files:**
- Create: `src/main/java/com/me/galchat/service/impl/GroupTurnRecoveryService.java`
- Modify: `src/main/java/com/me/galchat/service/impl/GroupChatService.java`
- Test: `src/test/java/com/me/galchat/service/impl/GroupTurnRecoveryServiceTest.java`
- Test: `src/test/java/com/me/galchat/service/impl/GroupChatServiceTest.java`

**Interfaces:**
- Produces: `void GroupTurnRecoveryService.recoverInterrupted(Long conversationId)`.
- Produces: `void GroupTurnRecoveryService.assertNoNonTerminalTurns(Long userWorldId)`.
- Produces: `void GroupTurnRecoveryService.cancelPendingSteps(Long turnId, String reason)`.

- [ ] **Step 1: Write failing model-preparation failure test**

Make `contextPolicy.load` throw synchronously:

```java
when(contextPolicy.load(conversation, action))
        .thenThrow(new IllegalStateException("context failed"));

List<GroupChatEvent> events = service.chat(7L, request).collectList().block();

assertThat(events).extracting(GroupChatEvent::getEventType)
        .contains(GroupChatConstant.EVENT_REPLY_FAILED);
verify(stepMapper).updateById(argThat(step ->
        GroupChatConstant.STATUS_FAILED.equals(step.getStatus())));
verify(messageMapper, never()).insert(argThat(message ->
        GroupChatConstant.STATUS_STREAMING.equals(message.getStatus())));
```

For a two-step Turn, make the first fail and assert the second becomes `cancelled`.
Add the same pending-step assertion when `contextPolicy.onTurnStarted` throws before the first Step.

- [ ] **Step 2: Write failing interrupted-turn recovery tests**

```java
@Test
void recoveryFailsRunningStepAndCancelsPendingSteps() {
    recoveryService.recoverInterrupted(7L);

    verify(messageMapper).update(any(), argThat(wrapper -> true));
    verify(stepMapper).update(any(), argThat(wrapper -> true));
    verify(turnMapper).update(any(), argThat(wrapper -> true));
}

@Test
void saveGuardRejectsAnyNonTerminalTurnInWorld() {
    when(turnMapper.countNonTerminalByUserWorldId(1L)).thenReturn(1L);
    assertThatThrownBy(() -> recoveryService.assertNoNonTerminalTurns(1L))
            .isInstanceOf(UserRequestException.class)
            .hasMessageContaining("未完成");
}
```

Add `countNonTerminalByUserWorldId(Long userWorldId)` to `GroupChatTurnMapper` as an annotated SQL query
or mapper XML query joining `group_conversation`.

- [ ] **Step 3: Run focused tests and verify failure**

```bash
./mvnw -Dtest=GroupChatServiceTest,GroupTurnRecoveryServiceTest test
```

Expected: FAIL because setup exceptions escape the inner finalizer and recovery service does not exist.

- [ ] **Step 4: Implement recovery as idempotent bulk transitions**

`recoverInterrupted(conversationId)` operates after the caller acquired the conversation lock:

```text
streaming messages for nonterminal turns -> failed
running steps                         -> failed
pending steps                         -> cancelled
pending/running turns                 -> failed
```

Use conditional update wrappers so repeated recovery calls change nothing after the first call. Do not
touch completed, failed, cancelled or withdrawn rows.

- [ ] **Step 5: Reorder executeStep and wrap the whole preparation path**

Inside one `Flux.defer`:

1. load context;
2. prepare model invocation and tool context;
3. atomically set Step to running and create streaming message;
4. start the model stream.

Attach error/cancel handling outside all four stages. If failure happens before streaming-message creation,
mark Step failed without trying to persist a null message. On failure or cancel, call
`cancelPendingSteps(turnId, reason)` before finalizing the Turn.

If `contextPolicy.onTurnStarted` fails, cancel every precreated pending Step and fail the Turn before
returning the failure event.

Call `recoverInterrupted(conversationId)` immediately after obtaining the conversation lock and before
creating a new Turn.

- [ ] **Step 6: Run focused tests**

```bash
./mvnw -Dtest=GroupChatServiceTest,GroupTurnRecoveryServiceTest test
```

Expected: PASS; no setup exception can leave running/streaming data.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/me/galchat/service/impl/GroupTurnRecoveryService.java \
  src/main/java/com/me/galchat/service/impl/GroupChatService.java \
  src/main/java/com/me/galchat/mapper/GroupChatTurnMapper.java \
  src/test/java/com/me/galchat/service/impl/GroupTurnRecoveryServiceTest.java \
  src/test/java/com/me/galchat/service/impl/GroupChatServiceTest.java
git commit -m "fix: recover interrupted group chat turns"
```

---

### Task 5: Decouple Withdrawal and Conversation Closing from Plans

**Files:**
- Modify: `src/main/java/com/me/galchat/service/impl/GroupChatWithdrawalService.java`
- Modify: `src/main/java/com/me/galchat/service/impl/GroupConversationLifecycleService.java`
- Test: `src/test/java/com/me/galchat/service/impl/GroupChatWithdrawalServiceTest.java`
- Test: `src/test/java/com/me/galchat/service/impl/GroupConversationLifecycleServiceTest.java`

**Interfaces:**
- Consumes: `GroupTurnRecoveryService` from Task 4.
- Removes from withdrawal constructor: `GroupReplyPlanItemMapper`, `GroupReplyPlanService`.

- [ ] **Step 1: Write failing withdrawal-decoupling test**

Use constructor-level fixture mocks and verify a completed Turn is withdrawn without Plan access:

```java
service.withdrawLatestTurn(7L);

verify(stepMapper).delete(any());
verify(turnMapper).updateById(argThat(turn ->
        GroupChatConstant.STATUS_WITHDRAWN.equals(turn.getStatus())));
verifyNoInteractions(planItemMapper, replyPlanService);
```

Then remove Plan mocks from the fixture; compilation should force the production constructor to lose those
dependencies too.

- [ ] **Step 2: Write failing close guard test**

```java
doThrow(new UserRequestException("存在未完成的群聊轮次"))
        .when(recoveryService).assertConversationHasNoNonTerminalTurns(7L);

assertThatThrownBy(() -> lifecycleService.close(7L))
        .isInstanceOf(UserRequestException.class);
verify(summaryClient, never()).prompt(any(Prompt.class));
```

Add `assertConversationHasNoNonTerminalTurns(Long conversationId)` to `GroupTurnRecoveryService`.

- [ ] **Step 3: Run focused tests and verify failure**

```bash
./mvnw -Dtest=GroupChatWithdrawalServiceTest,GroupConversationLifecycleServiceTest test
```

Expected: FAIL because withdrawal still resets PlanItem and close lacks the guard.

- [ ] **Step 4: Remove Plan rollback and add terminal-state guards**

Delete `resetPlanItems`, Plan dependencies and all `planItemId` access from withdrawal. Retain tool, favor,
topic-boundary and message rollback unchanged.

After acquiring the conversation lock:

- withdrawal calls `assertConversationHasNoNonTerminalTurns`;
- close calls `assertConversationHasNoNonTerminalTurns`;
- Plan replace/advance/delete also call the same guard before mutating Plan.

Normal completed Turn withdrawal still works; TRPG withdrawal remains rejected before lock acquisition.

- [ ] **Step 5: Run focused tests**

```bash
./mvnw -Dtest=GroupChatWithdrawalServiceTest,GroupConversationLifecycleServiceTest,GroupReplyPlanServiceTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/me/galchat/service/impl/GroupChatWithdrawalService.java \
  src/main/java/com/me/galchat/service/impl/GroupConversationLifecycleService.java \
  src/main/java/com/me/galchat/service/impl/GroupReplyPlanService.java \
  src/main/java/com/me/galchat/service/impl/GroupTurnRecoveryService.java \
  src/test/java/com/me/galchat/service/impl/GroupChatWithdrawalServiceTest.java \
  src/test/java/com/me/galchat/service/impl/GroupConversationLifecycleServiceTest.java \
  src/test/java/com/me/galchat/service/impl/GroupReplyPlanServiceTest.java
git commit -m "refactor: decouple group withdrawal from reply plans"
```

---

### Task 6: Save and Restore Active and Resume Plans in User World Saves

**Files:**
- Modify: `src/main/java/com/me/galchat/domain/dto/UserWorldSaveSnapshotDTO.java`
- Create: `src/main/java/com/me/galchat/service/impl/GroupReplyPlanSnapshotService.java`
- Modify: `src/main/java/com/me/galchat/service/impl/UserWorldSaveServiceImpl.java`
- Modify: `src/main/java/com/me/galchat/service/impl/GroupReplyPlanService.java`
- Test: `src/test/java/com/me/galchat/service/impl/GroupReplyPlanSnapshotServiceTest.java`

**Interfaces:**
- Produces: `List<GroupConversationPlanSnapshot> GroupReplyPlanSnapshotService.capture(Long userWorldId)`.
- Produces: `void GroupReplyPlanSnapshotService.restore(Long userWorldId, List<GroupConversationPlanSnapshot> snapshots)`.
- Consumes: `GroupTurnRecoveryService.assertNoNonTerminalTurns(Long userWorldId)`.

- [ ] **Step 1: Add DTO shape and failing capture test**

Add nested DTOs:

```java
private List<GroupConversationPlanSnapshot> conversationPlans;

@Data
@Accessors(chain = true)
public static class GroupConversationPlanSnapshot {
    private Long conversationId;
    private ReplyPlanSnapshot activePlan;
}

@Data
@Accessors(chain = true)
public static class ReplyPlanSnapshot {
    private String source;
    private Long contextId;
    private List<ReplyPlanGroupSnapshot> groups;
    private ReplyPlanSnapshot resumePlan;
}
```

`ReplyPlanGroupSnapshot` contains `key/name/order/items`; each item contains
`order/actorType/actorId`. No DTO contains database IDs, timestamps or status.

Capture test:

```java
List<GroupConversationPlanSnapshot> snapshots = service.capture(1L);

ReplyPlanSnapshot active = snapshots.getFirst().getActivePlan();
assertThat(active.getSource()).isEqualTo(GroupChatConstant.PLAN_SOURCE_COMBAT);
assertThat(active.getResumePlan().getSource()).isEqualTo(GroupChatConstant.PLAN_SOURCE_SCENE);
assertThat(active.getGroups()).extracting(ReplyPlanGroupSnapshot::getKey)
        .containsExactly("round:2", "round:3");
```

- [ ] **Step 2: Add failing restore and nesting tests**

Capture inserted Plan IDs and assert restore inserts resume first:

```java
service.restore(1L, List.of(snapshot));

assertThat(insertedPlans).extracting(GroupReplyPlan::getSource)
        .containsExactly(
                GroupChatConstant.PLAN_SOURCE_SCENE,
                GroupChatConstant.PLAN_SOURCE_COMBAT);
assertThat(insertedPlans.get(1).getResumePlanId())
        .isEqualTo(insertedPlans.get(0).getId());
assertThat(conversation.getActiveReplyPlanId())
        .isEqualTo(insertedPlans.get(1).getId());
assertThat(conversation.getStatus()).isEqualTo(GroupChatConstant.STATUS_ACTIVE);
assertThat(conversation.getClosedAt()).isNull();
```

Add a test that capture rejects `active.resumePlan.resumePlan != null` with a
`UserRequestException` mentioning nested combat.

- [ ] **Step 3: Run the focused test and verify failure**

```bash
./mvnw -Dtest=GroupReplyPlanSnapshotServiceTest test
```

Expected: FAIL because the DTO and service do not exist.

- [ ] **Step 4: Implement structural capture**

Query all `ACTIVE` conversations in the world ordered by ID. For each conversation:

1. load `activeReplyPlanId`;
2. convert its ordered items to grouped DTOs;
3. if `resumePlanId` exists, convert exactly one Resume Plan;
4. reject another nested `resumePlanId`.

Include active conversations with no Plan as `activePlan = null`; this preserves the between-scenes state.

- [ ] **Step 5: Implement transactional structural restore**

For every conversation represented in the snapshot:

1. verify it belongs to `userWorldId`;
2. delete all current PlanItem and Plan rows for the conversation;
3. insert Resume Plan and its items, if present;
4. insert Active Plan and its items, linking the newly generated Resume ID;
5. update `activeReplyPlanId`;
6. restore conversation status to `ACTIVE` and clear `closedAt`, including snapshots whose
   `activePlan` is null because they represent a valid between-scenes state.

Do not restore old Plan or PlanItem IDs. Do not touch Turn or ReplyStep references because they are now
self-contained.

- [ ] **Step 6: Integrate with UserWorldSaveServiceImpl**

In `buildSnapshot`:

```java
.setConversationPlans(groupReplyPlanSnapshotService.capture(userWorldId))
```

In both `saveWorld` and `loadWorld`, after all group locks are acquired and before the transaction:

```java
groupTurnRecoveryService.assertNoNonTerminalTurns(userWorldId);
```

Save locks the currently active conversation IDs. Load locks the sorted union of:

- currently active conversation IDs;
- every `conversationId` in `snapshot.getConversationPlans()`.

Refactor `lockActiveGroupConversations` into a helper accepting the extra snapshot IDs. This prevents
restoring and reopening a conversation that was closed after the save without holding its lock.

In `doLoadWorld`, replace:

```java
groupReplyPlanService.resetWorldPlans(userWorldId);
```

with:

```java
groupReplyPlanSnapshotService.restore(
        userWorldId, snapshot.getConversationPlans());
```

Delete `resetWorldPlans` after all callers are removed. Increment `FORMAT_VERSION` from 3 to 4 because old
save JSON is intentionally unsupported.

- [ ] **Step 7: Run save/restore tests**

```bash
./mvnw -Dtest=GroupReplyPlanSnapshotServiceTest,GroupReplyPlanServiceTest test
```

Expected: PASS; active and resume ordering round-trip without preserving database IDs.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/me/galchat/domain/dto/UserWorldSaveSnapshotDTO.java \
  src/main/java/com/me/galchat/service/impl/GroupReplyPlanSnapshotService.java \
  src/main/java/com/me/galchat/service/impl/UserWorldSaveServiceImpl.java \
  src/main/java/com/me/galchat/service/impl/GroupReplyPlanService.java \
  src/test/java/com/me/galchat/service/impl/GroupReplyPlanSnapshotServiceTest.java
git commit -m "feat: save and restore group reply plans"
```

---

### Task 7: Verify Schema, Save Semantics, and Full Regression Suite

**Files:**
- Modify if required: `docs/sql/V20260711__group_chat.sql`
- Modify if required: `src/test/java/com/me/galchat/init/console.sql`
- Modify if required: tests touched in Tasks 1–6

**Interfaces:**
- Consumes all interfaces from Tasks 1–6.
- Produces no new production API.

- [ ] **Step 1: Search for forbidden legacy coupling**

Run:

```bash
grep -R -nE 'getStatus\\(\\).*GroupReplyPlanItem|setStatus\\(GroupChatConstant.*\\).*GroupReplyPlanItem|planItemId|pendingItemsForExecution|resetWorldPlans|markRunning\\(|markCompleted\\(|resetPending\\(' \
  src/main/java src/test/java docs/sql
```

Expected: no Plan execution-state methods, no persisted `plan_item_id`, and no read/load Plan reset.

- [ ] **Step 2: Verify SQL and Java field parity**

Check:

```bash
grep -nE 'group_reply_plan_item|plan_source|plan_context_id|group_key|group_name|group_order|item_order|plan_item_id' \
  docs/sql/V20260711__group_chat.sql src/test/java/com/me/galchat/init/console.sql
```

Expected:

- no `group_reply_plan_item.status`;
- no `group_chat_reply_step.plan_item_id`;
- Turn has `plan_source` and `plan_context_id`;
- ReplyStep has all four group/order columns.

- [ ] **Step 3: Run all focused group-chat tests**

```bash
./mvnw -Dtest=GroupReplyPlanServiceTest,GroupChatServiceTest,GroupTurnRecoveryServiceTest,GroupChatWithdrawalServiceTest,GroupConversationLifecycleServiceTest,GroupReplyPlanSnapshotServiceTest test
```

Expected: PASS.

- [ ] **Step 4: Run the complete test suite**

```bash
./mvnw test
```

Expected: all tests pass with zero failures and zero errors.

- [ ] **Step 5: Inspect the final diff**

```bash
git diff --check
git status --short
git diff --stat HEAD~6..HEAD
```

Expected: no whitespace errors; only planned source, test, SQL and documentation changes are present.
Ignore existing untracked `.DS_Store` and `__pycache__` files.

- [ ] **Step 6: Commit any verification-only corrections**

Only if Step 1–5 required corrections:

```bash
git add docs/sql/V20260711__group_chat.sql \
  src/test/java/com/me/galchat/init/console.sql \
  src/main/java/com/me/galchat \
  src/test/java/com/me/galchat
git commit -m "test: complete reply plan snapshot regression coverage"
```

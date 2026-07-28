# Self-Describing Dice Messages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist each public dice event as `{"summaryId":...,"roundNos":[...]}` in `group_chat_message.content`, consume that reference directly in history/context, and merge automatically-created rounds into the latest matching dice message.

**Architecture:** Add one focused codec for the persisted message payload and one focused updater implementing a small dice-service port. `GroupChatService` writes the payload from `KpDiceToolResult`; `GroupContextAssembler` reads it and formats only the declared rounds. Tool-call rows remain the audit/follow-up source but are no longer the public-message content source.

**Tech Stack:** Java 21, Spring Boot 4, Spring AI 2.0.0-M4, MyBatis-Plus, Jackson 3, JUnit 5, AssertJ, Mockito.

## Global Constraints

- `message_kind = "dice_roll"` content is JSON with exactly `summaryId` and normalized `roundNos`.
- `roundNos` is non-empty, distinct, positive, and ascending.
- Old `dice_roll` rows with null content are unsupported.
- KP tool calls create a new dice message; player-resolution automatic rounds update the latest dice message in the conversation.
- The updater must reject a latest-message `summaryId` mismatch.
- `reply_step_id` remains for execution/audit/withdrawal, not public dice-content lookup.
- `group_chat_tool_call.dice_roll_summary_id` remains for audit and follow-up location.
- `total_result` keeps one newline between different completed rounds; same-round entries retain semicolon formatting.
- Preserve unrelated untracked `.DS_Store`, `__pycache__`, and `output/markdown` files.

---

### Task 1: Dice message payload codec

**Files:**
- Create: `src/main/java/com/me/galchat/groupchat/dice/DiceRollMessageContent.java`
- Create: `src/main/java/com/me/galchat/groupchat/dice/DiceRollMessageCodec.java`
- Test: `src/test/java/com/me/galchat/groupchat/dice/DiceRollMessageCodecTest.java`

**Interfaces:**
- Produces: `record DiceRollMessageContent(Long summaryId, List<Integer> roundNos)`
- Produces: `String DiceRollMessageCodec.encode(Long summaryId, Collection<Integer> roundNos)`
- Produces: `DiceRollMessageContent DiceRollMessageCodec.decode(String content)`

- [ ] **Step 1: Write failing codec tests**

```java
class DiceRollMessageCodecTest {
    private final DiceRollMessageCodec codec =
            new DiceRollMessageCodec(JsonMapper.builder().build());

    @Test
    void encodesDistinctRoundsInAscendingOrder() {
        assertThat(codec.encode(501L, List.of(3, 2, 3)))
                .isEqualTo("{\"summaryId\":501,\"roundNos\":[2,3]}");
    }

    @Test
    void decodesAValidReference() {
        assertThat(codec.decode("{\"summaryId\":501,\"roundNos\":[2,3]}"))
                .isEqualTo(new DiceRollMessageContent(501L, List.of(2, 3)));
    }

    @Test
    void rejectsMissingSummaryAndEmptyRounds() {
        assertThatThrownBy(() -> codec.decode("{\"summaryId\":null,\"roundNos\":[]}"))
                .isInstanceOf(UserRequestException.class);
    }
}
```

- [ ] **Step 2: Run the codec test and verify RED**

Run:

```bash
./mvnw -Dtest=DiceRollMessageCodecTest test
```

Expected: compilation failure because the codec and payload record do not exist.

- [ ] **Step 3: Implement the minimal payload and codec**

```java
public record DiceRollMessageContent(Long summaryId, List<Integer> roundNos) {
}
```

```java
@Component
@RequiredArgsConstructor
public class DiceRollMessageCodec {
    private final ObjectMapper objectMapper;

    public String encode(Long summaryId, Collection<Integer> roundNos) {
        return write(normalize(summaryId, roundNos));
    }

    public DiceRollMessageContent decode(String content) {
        if (!StringUtils.hasText(content)) {
            throw new UserRequestException("掷骰消息内容不能为空");
        }
        try {
            DiceRollMessageContent parsed =
                    objectMapper.readValue(content, DiceRollMessageContent.class);
            return normalize(parsed.summaryId(), parsed.roundNos());
        } catch (JacksonException exception) {
            throw new UserRequestException("掷骰消息内容无法解析");
        }
    }

    private DiceRollMessageContent normalize(
            Long summaryId, Collection<Integer> roundNos) {
        if (summaryId == null || summaryId <= 0) {
            throw new UserRequestException("掷骰消息缺少有效概要id");
        }
        List<Integer> normalized = roundNos == null ? List.of() : roundNos.stream()
                .filter(Objects::nonNull)
                .filter(round -> round > 0)
                .distinct()
                .sorted()
                .toList();
        if (normalized.isEmpty()) {
            throw new UserRequestException("掷骰消息缺少有效轮次");
        }
        return new DiceRollMessageContent(summaryId, normalized);
    }
}
```

`write` catches `JacksonException` and throws `IllegalStateException("序列化掷骰消息失败", exception)`.

- [ ] **Step 4: Run the codec test and verify GREEN**

Run:

```bash
./mvnw -Dtest=DiceRollMessageCodecTest test
```

Expected: all codec tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/me/galchat/groupchat/dice \
        src/test/java/com/me/galchat/groupchat/dice
git commit -m "feat: encode dice message references"
```

---

### Task 2: Persist dice references and return history without ReplyStep lookup

**Files:**
- Modify: `src/main/java/com/me/galchat/service/impl/GroupChatService.java`
- Modify: `src/main/java/com/me/galchat/domain/vo/GroupChatMessageVO.java`
- Test: `src/test/java/com/me/galchat/service/impl/GroupChatServiceTest.java`

**Interfaces:**
- Consumes: `DiceRollMessageCodec.encode(Long, Collection<Integer>)`
- Produces: completed `dice_roll` message content containing the summary ID and all round numbers returned by that tool call.
- Produces: history results directly from `group_chat_message` without `GroupToolCallStore.diceSummaryIdsByReplyStepIds`.

- [ ] **Step 1: Replace the direct-dice persistence test expectation**

Build `KpDiceToolResult` with results from rounds 3, 2, and 3. Capture the updated
`GroupChatMessage` and assert:

```java
assertThat(updated.getMessageKind()).isEqualTo(GroupChatConstant.MESSAGE_DICE_ROLL);
assertThat(updated.getContent())
        .isEqualTo("{\"summaryId\":501,\"roundNos\":[2,3]}");
assertThat(updated.getStatus()).isEqualTo(GroupChatConstant.STATUS_COMPLETED);
```

Change the history test so the database row has:

```java
.setContent("{\"summaryId\":501,\"roundNos\":[2]}")
```

and assert the VO returns that exact content. Remove stubbing and assertions for
`diceSummaryIdsByReplyStepIds`.

- [ ] **Step 2: Run the service test and verify RED**

Run:

```bash
./mvnw -Dtest=GroupChatServiceTest test
```

Expected: direct dice content is still null and history still performs the ReplyStep mapping.

- [ ] **Step 3: Write dice content in `persistOutput`**

Inject `DiceRollMessageCodec`. Replace the null assignment with:

```java
List<Integer> roundNos = accumulator.diceRoll.results().stream()
        .map(DiceRollDetailVO::getRoundNo)
        .toList();
message.setMessageKind(GroupChatConstant.MESSAGE_DICE_ROLL)
        .setContent(diceMessageCodec.encode(
                accumulator.diceRoll.summary().getId(), roundNos));
```

Keep dialogue persistence unchanged.

- [ ] **Step 4: Remove history’s tool-call lookup**

Delete collection of dice ReplyStep IDs and the call to
`toolCallStore.diceSummaryIdsByReplyStepIds`. Remove `diceRollSummaryId` from
`GroupChatMessageVO`, and construct history VOs directly from the message row:

```java
new GroupChatMessageVO(
        message.getId(), message.getConversationId(), message.getTurnId(),
        message.getReplyStepId(), message.getSpeakerType(), message.getSpeakerId(),
        speakerName(conversation, message), message.getMessageKind(),
        message.getContent(), message.getSequenceNo(), message.getStatus(),
        message.getCreatedAt())
```

Remove the now-unused `GroupToolCallStore` dependency from `GroupChatService`.

- [ ] **Step 5: Run the service test and verify GREEN**

Run:

```bash
./mvnw -Dtest=GroupChatServiceTest test
```

Expected: all `GroupChatServiceTest` tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/me/galchat/service/impl/GroupChatService.java \
        src/main/java/com/me/galchat/domain/vo/GroupChatMessageVO.java \
        src/test/java/com/me/galchat/service/impl/GroupChatServiceTest.java
git commit -m "feat: persist self-describing dice messages"
```

---

### Task 3: Assemble public dice context from message content

**Files:**
- Create: `src/main/java/com/me/galchat/groupchat/dice/GroupDiceMessageFormatter.java`
- Modify: `src/main/java/com/me/galchat/service/impl/GroupContextAssembler.java`
- Modify: `src/main/java/com/me/galchat/groupchat/tool/GroupToolHistoryAssembler.java`
- Test: `src/test/java/com/me/galchat/groupchat/dice/GroupDiceMessageFormatterTest.java`
- Test: `src/test/java/com/me/galchat/service/impl/GroupContextAssemblerTest.java`
- Test: `src/test/java/com/me/galchat/groupchat/tool/GroupToolHistoryAssemblerTest.java`

**Interfaces:**
- Consumes: `DiceRollMessageCodec.decode(String)`
- Produces: `String GroupDiceMessageFormatter.format(String messageContent)`
- Produces: `GroupToolHistoryAssembler.beforeMessages` containing only caller-private, non-dice tool history.

- [ ] **Step 1: Write a failing formatter test**

Stub summary `501` and results from rounds 1, 2, and 3. Format:

```json
{"summaryId":501,"roundNos":[2,3]}
```

Assert the text:

```java
assertThat(formatted)
        .contains("<dice-roll summary-id=\"501\" rounds=\"2,3\">")
        .contains("第2轮", "第3轮")
        .doesNotContain("第1轮");
```

Also assert a nonexistent summary and a round not owned by the summary throw
`UserRequestException`.

- [ ] **Step 2: Run the formatter test and verify RED**

Run:

```bash
./mvnw -Dtest=GroupDiceMessageFormatterTest test
```

Expected: compilation failure because `GroupDiceMessageFormatter` does not exist.

- [ ] **Step 3: Implement the formatter**

`format` decodes content, loads the summary by ID, loads results with:

```java
new LambdaQueryWrapper<DiceRollResult>()
        .eq(DiceRollResult::getSummaryId, reference.summaryId())
        .in(DiceRollResult::getRoundNo, reference.roundNos())
        .orderByAsc(DiceRollResult::getRoundNo)
        .orderByAsc(DiceRollResult::getDisplayOrder)
        .orderByAsc(DiceRollResult::getId)
```

Require every declared round to have at least one row. Format only those rows:

```text
<dice-roll summary-id="501" rounds="2,3">
原因：……
第2轮：……
第3轮：……
</dice-roll>
```

Use current `formula = result` detail formatting; do not read tool-call rows.

- [ ] **Step 4: Write failing context tests**

Update `contextUsesSyntheticDiceHistoryAndSkipsNullDiceMessageBody` into a test
whose dice message has JSON content and whose `GroupDiceMessageFormatter` returns
`<dice-roll summary-id="501" rounds="2" />`.

Assert:

```java
assertThat(context).extracting(Message::getText)
        .containsExactly("<dice-roll summary-id=\"501\" rounds=\"2\" />");
```

Verify `GroupToolHistoryAssembler` is not asked to supply the dice block. Add a
tool-history test proving a dice tool call is omitted from private tool replay.

- [ ] **Step 5: Run context/tool tests and verify RED**

Run:

```bash
./mvnw -Dtest=GroupContextAssemblerTest,GroupToolHistoryAssemblerTest test
```

Expected: context skips the dice message and tool history still synthesizes the public dice block.

- [ ] **Step 6: Route dice messages through the formatter**

Inject `GroupDiceMessageFormatter` into `GroupContextAssembler`. In message order:

```java
if (GroupChatConstant.MESSAGE_DICE_ROLL.equals(message.getMessageKind())) {
    prompt.add(new UserMessage(diceMessageFormatter.format(message.getContent())));
    continue;
}
```

Keep private tool messages before ordinary actor output. Simplify
`GroupToolHistoryAssembler` by deleting dice-summary/result mappers and the
`formatDice` branch; filter all rows with non-null `diceRollSummaryId` out of
private tool reconstruction.

- [ ] **Step 7: Run formatter/context/tool tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=GroupDiceMessageFormatterTest,GroupContextAssemblerTest,GroupToolHistoryAssemblerTest test
```

Expected: all selected tests pass and no public dice context depends on ReplyStep lookup.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/me/galchat/groupchat/dice \
        src/main/java/com/me/galchat/service/impl/GroupContextAssembler.java \
        src/main/java/com/me/galchat/groupchat/tool/GroupToolHistoryAssembler.java \
        src/test/java/com/me/galchat/groupchat/dice \
        src/test/java/com/me/galchat/service/impl/GroupContextAssemblerTest.java \
        src/test/java/com/me/galchat/groupchat/tool/GroupToolHistoryAssemblerTest.java
git commit -m "refactor: assemble dice context from messages"
```

---

### Task 4: Merge automatically-created rounds into the latest dice message

**Files:**
- Create: `src/main/java/com/me/galchat/service/DiceMessageRoundAppender.java`
- Create: `src/main/java/com/me/galchat/groupchat/dice/GroupDiceMessageRoundAppender.java`
- Modify: `src/main/java/com/me/galchat/service/impl/CocDiceOrchestrationService.java`
- Test: `src/test/java/com/me/galchat/groupchat/dice/GroupDiceMessageRoundAppenderTest.java`
- Test: `src/test/java/com/me/galchat/service/impl/CocDiceOrchestrationServiceTest.java`

**Interfaces:**
- Produces: `void DiceMessageRoundAppender.appendRounds(Long conversationId, Long summaryId, Collection<Integer> roundNos)`
- Consumes: `DiceRollMessageCodec.decode/encode`

- [ ] **Step 1: Write failing updater tests**

Use a mocked mapper that returns the latest dice message:

```java
new GroupChatMessage()
        .setId(91L)
        .setConversationId(7L)
        .setMessageKind("dice_roll")
        .setContent("{\"summaryId\":501,\"roundNos\":[1]}")
```

Call:

```java
appender.appendRounds(7L, 501L, List.of(3, 2, 3));
```

Capture the updated row and assert:

```java
assertThat(updated.getContent())
        .isEqualTo("{\"summaryId\":501,\"roundNos\":[1,2,3]}");
assertThat(updated.getUpdatedAt()).isNotNull();
```

Add tests that missing latest dice message and `summaryId=502` mismatch both throw
`UserRequestException` without updating a row.

- [ ] **Step 2: Run updater tests and verify RED**

Run:

```bash
./mvnw -Dtest=GroupDiceMessageRoundAppenderTest test
```

Expected: compilation failure because the port and implementation do not exist.

- [ ] **Step 3: Implement the updater**

The implementation queries:

```java
messageMapper.selectOne(new LambdaQueryWrapper<GroupChatMessage>()
        .eq(GroupChatMessage::getConversationId, conversationId)
        .eq(GroupChatMessage::getMessageKind, GroupChatConstant.MESSAGE_DICE_ROLL)
        .orderByDesc(GroupChatMessage::getSequenceNo)
        .orderByDesc(GroupChatMessage::getId)
        .last("limit 1"));
```

Decode, compare summary IDs, merge existing and supplied rounds, encode, set
`updatedAt`, and call `updateById`.

- [ ] **Step 4: Run updater tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=GroupDiceMessageRoundAppenderTest test
```

Expected: all updater tests pass.

- [ ] **Step 5: Write failing orchestration test**

In the player-result test that automatically creates insanity or major-wound
rounds, inject a mocked `DiceMessageRoundAppender` and capture:

```java
verify(diceMessageRoundAppender)
        .appendRounds(7L, 111L, List.of(2, 3));
```

Add a player roll that creates no automatic rows and verify the appender is not
called.

- [ ] **Step 6: Run orchestration tests and verify RED**

Run:

```bash
./mvnw -Dtest=CocDiceOrchestrationServiceTest test
```

Expected: the new appender receives no call.

- [ ] **Step 7: Notify the appender from `rollPlayerResult`**

After both automatic append helpers finish:

```java
List<Integer> createdRounds = created.stream()
        .map(DiceRollResult::getRoundNo)
        .filter(Objects::nonNull)
        .distinct()
        .sorted()
        .toList();
if (!createdRounds.isEmpty()) {
    diceMessageRoundAppender.appendRounds(
            summary.getConversationId(), summary.getId(), createdRounds);
}
```

Because `rollPlayerResult` is transactional, failure to update/validate the
message rolls back dice-result and summary changes as well.

- [ ] **Step 8: Run orchestration tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=CocDiceOrchestrationServiceTest test
```

Expected: all orchestration tests pass.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/me/galchat/service/DiceMessageRoundAppender.java \
        src/main/java/com/me/galchat/groupchat/dice/GroupDiceMessageRoundAppender.java \
        src/main/java/com/me/galchat/service/impl/CocDiceOrchestrationService.java \
        src/test/java/com/me/galchat/groupchat/dice/GroupDiceMessageRoundAppenderTest.java \
        src/test/java/com/me/galchat/service/impl/CocDiceOrchestrationServiceTest.java
git commit -m "feat: attach automatic dice rounds to messages"
```

---

### Task 5: Regression and full verification

**Files:**
- Modify only if a regression test reveals a missing behavior.

**Interfaces:**
- Verifies all interfaces produced by Tasks 1–4.

- [ ] **Step 1: Run focused dice and group-chat tests**

```bash
./mvnw -Dtest=DiceRollMessageCodecTest,GroupDiceMessageFormatterTest,GroupDiceMessageRoundAppenderTest,GroupChatServiceTest,GroupContextAssemblerTest,GroupToolHistoryAssemblerTest,CocDiceSummaryFormatterTest,CocDiceOrchestrationServiceTest test
```

Expected: all selected tests pass.

- [ ] **Step 2: Confirm `total_result` newline regression**

`CocDiceSummaryFormatterTest.rebuildsOnlyCompletedRoundsInNumericOrder` must
continue asserting the literal:

```text
林恩成功
林恩大失败
```

No production change is required if this test passes.

- [ ] **Step 3: Run the complete suite**

```bash
./mvnw test
```

Expected: all tests pass with zero failures and zero errors.

- [ ] **Step 4: Check the patch**

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only intentional tracked changes plus the
pre-existing unrelated untracked files.

- [ ] **Step 5: Confirm no final commit is needed**

Tasks 1–4 commit every production and regression-test change. If verification
changes a tracked file, move that change back into the task whose behavior it
corrects and repeat that task's focused test and commit rather than creating an
unscoped cleanup commit.

# KP Dice Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 TRPG 群聊加入隐式 KP Agent、结构化 CoC 掷骰工具、角色卡原子结算、多轮自动后续骰和 `returnDirect` 前端事件。

**Architecture:** `KpDiceTools` 只负责把 Spring AI 工具参数和 `ToolContext` 转交给 `CocDiceOrchestrationService`；后者组合纯规则 `CocDiceRules`、骰子持久化服务、角色卡服务和群工具调用定位器。用户点击通过现有公开骰子接口进入同一编排服务，KP 工具执行、骰子/角色卡写入和 `group_chat_tool_call` 记录由 `RecordingGroupToolCallingManager` 的定向事务统一提交。

**Tech Stack:** Java 21、Spring Boot 4.0.5、Spring AI 2.0.0-M4、MyBatis-Plus 3.5.16、PostgreSQL JSONB、Jackson 3、JUnit 5、AssertJ、Mockito。

**Design:** `docs/superpowers/specs/2026-07-27-kp-dice-tools-design.md`

## Global Constraints

- `conversationId`、`runId/userWorldId`、当前 actor 和 `replyStepId` 只能来自 `ToolContext`，不能成为模型参数。
- `summaryId` 只能从当前群聊上一条兼容的 `group_chat_tool_call.dice_roll_summary_id` 解析，不能成为模型参数。
- `total_result`、骰点、成功失败、胜者和角色卡变化只能由后端生成。
- `character_id IS NULL` 表示用户角色，非空值表示玩家 Agent 对应的 participant/角色模板 ID。
- 用户真实骰子必须保存完整 `formula/modules` 占位且 `result=null`；Agent 自动投骰；常量公式立即结算。
- 所有 `CocDiceOrchestrationService` 状态型入口使用 `@Transactional(rollbackFor = Exception.class)`；多角色卡加锁按 card ID 升序执行。
- 普通检定公开结果只有 `CRITICAL_SUCCESS`、`SUCCESS`、`FAILURE`、`FUMBLE`；困难/极难等级只用于内部判断和对抗比较。
- SAN 检定不接受奖励骰或惩罚骰；本期不实现幸运调整、暗骰和幕间成长。
- 每次 KP 模型响应最多执行一个状态型掷骰工具；出现该工具时，同一模型步骤不能并行调用其他工具。
- `requestPushedCheck` 和 `rollSanLoss` 由 KP 主动发起；疯狂轮和重伤 CON 轮由系统按条件自动创建。
- 用户重试已结算的 `resultId` 时返回保存结果，不能重新投骰或重复修改角色卡。
- 疯狂文本选择代码常量方案：固定目录保存在 `InsanityCatalog`，不新增数据库目录表。
- 现有 `dice_skin` 字段和 `DiceUtils.prepare` 已完成，本计划只做回归验证，不重复实现。

## File and Responsibility Map

- `constant/InsanityCatalog.java`：固定的 10 类总结症状、100 项恐惧症和 100 项躁狂症文本及编号解析。
- `service/impl/CocDiceRules.java`：无数据库依赖的百分骰、对抗、SAN、伤害、重伤和疯狂规则。
- `service/impl/CharacterCardServiceImpl.java`：在当前 run 内按角色名解析人物卡、按 ID 加锁、解析属性/技能值并保存骰子产生的状态变化。
- `service/impl/DiceRollInternalServiceImpl.java`：概要/结果的创建、加锁、追加轮次、查询和保存，不解释 CoC 规则。
- `service/impl/CocDiceOrchestrationService.java`：工具和用户点击的事务编排、规则结算、概要重建及条件后续轮。
- `tool/KpDiceTools.java`：六个 `returnDirect` KP 工具及 ToolContext 校验。
- `groupchat/tool/GroupToolCallStore.java`：记录工具调用、从直接返回 DTO 绑定概要、定位上一条兼容概要。
- `groupchat/tool/RecordingGroupToolCallingManager.java`：限制状态型工具数量并为骰子工具执行及记录提供单一事务。
- `service/impl/GroupChatService.java`：识别 Spring AI 的 `returnDirect` generation，发出 `dice_roll.created`，不保存 JSON 对话文本。
- `service/impl/GroupContextAssembler.java`：按 `(actorType, actorId)` 组装私有历史，并把人物卡疯狂编号解析为文本。

---

### Task 1: Extend Dice Persistence and Public DTO Contracts

**Files:**
- Create: `docs/sql/V20260727__kp_dice_tools.sql`
- Modify: `docs/sql/V20260720__dice_roll.sql`
- Modify: `docs/sql/V20260711__group_chat.sql`
- Modify: `src/test/java/com/me/galchat/init/console.sql`
- Create: `src/main/java/com/me/galchat/domain/vo/DiceResolutionDataVO.java`
- Create: `src/main/java/com/me/galchat/domain/vo/DiceResolutionVO.java`
- Create: `src/main/java/com/me/galchat/domain/vo/DiceRollProgressVO.java`
- Create: `src/main/java/com/me/galchat/domain/vo/KpDiceToolResult.java`
- Modify: `src/main/java/com/me/galchat/domain/po/DiceRollResult.java`
- Modify: `src/main/java/com/me/galchat/domain/dto/DiceRollResultCreateDTO.java`
- Modify: `src/main/java/com/me/galchat/domain/vo/DiceRollDetailVO.java`
- Test: `src/test/java/com/me/galchat/domain/vo/DiceRollDetailVOTest.java`

**Interfaces:**
- Produces: `DiceResolutionDataVO`, the persisted JSONB envelope with `version`, `type`, `sourceResultId`, `rule`, `outcome`, and `effect`.
- Produces: `DiceResolutionVO`, the public view containing `type`, `sourceResultId`, `outcome`, and `effect`, but never `rule`.
- Produces: `DiceRollProgressVO(DiceRollSummaryVO summary, DiceRollDetailVO rolledResult, List<DiceRollDetailVO> createdResults)`.
- Produces: `KpDiceToolResult(DiceRollSummaryVO summary, List<DiceRollDetailVO> results, String semanticResult)`.

- [ ] **Step 1: Write a failing sanitization test**

```java
@Test
void detailExposesOutcomeButNotInternalRuleSnapshot() {
    DiceResolutionDataVO resolution = DiceResolutionDataVO.pending(
            "DAMAGE", 91L, Map.of("cardId", 77L, "characterName", "林恩"));
    resolution.setOutcome(Map.of("damage", 6));
    resolution.setEffect(Map.of("hpBefore", 10, "hpAfter", 4));
    DiceRollResult entity = new DiceRollResult()
            .setId(1L)
            .setResolutionData(resolution)
            .setResolvedAt(LocalDateTime.parse("2026-07-27T12:00:00"));

    DiceRollDetailVO detail = DiceRollDetailVO.from(entity);

    assertThat(detail.getResolution().getOutcome()).containsEntry("damage", 6);
    assertThat(detail.getResolution().getEffect()).containsEntry("hpAfter", 4);
    assertThat(detail.getResolution().getSourceResultId()).isEqualTo(91L);
    assertThat(DiceRollDetailVO.class.getDeclaredFields())
            .extracting(Field::getName)
            .doesNotContain("resolutionData");
}
```

- [ ] **Step 2: Run the new test and verify it fails**

Run:

```bash
./mvnw -Dtest=DiceRollDetailVOTest test
```

Expected: compilation fails because the resolution types and entity fields do not exist.

- [ ] **Step 3: Add the typed JSONB envelope and public DTOs**

Implement these exact shapes:

```java
@Data
@Accessors(chain = true)
public class DiceResolutionDataVO {
    private Integer version = 1;
    private String type;
    private Long sourceResultId;
    private Map<String, Object> rule = new LinkedHashMap<>();
    private Map<String, Object> outcome;
    private Map<String, Object> effect;

    public static DiceResolutionDataVO pending(
            String type, Long sourceResultId, Map<String, Object> rule) {
        return new DiceResolutionDataVO()
                .setType(type)
                .setSourceResultId(sourceResultId)
                .setRule(new LinkedHashMap<>(rule));
    }

    public DiceResolutionVO publicView() {
        return new DiceResolutionVO(type, sourceResultId, outcome, effect);
    }
}
```

Add `resolutionData` with `JsonbTypeHandler` and nullable `resolvedAt` to `DiceRollResult`. Add only the sanitized `DiceResolutionVO resolution` and `resolvedAt` to `DiceRollDetailVO`.

- [ ] **Step 4: Add forward and upgrade SQL**

`V20260727__kp_dice_tools.sql` must contain:

```sql
ALTER TABLE dice_roll_result
    ADD COLUMN IF NOT EXISTS resolution_data JSONB NOT NULL
        DEFAULT '{"version":1,"type":"LEGACY","rule":{},"outcome":null,"effect":null}'::jsonb,
    ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMP;

UPDATE dice_roll_result
SET resolved_at = updated_at
WHERE resolved_at IS NULL
  AND (result_data ->> 'result') IS NOT NULL;

ALTER TABLE group_reply_plan_item
    ALTER COLUMN actor_id DROP NOT NULL;
```

Add the two dice columns to the original create script, make `group_reply_plan_item.actor_id` nullable in the original group script, and mirror both changes in `src/test/java/com/me/galchat/init/console.sql`.

- [ ] **Step 5: Run the DTO test**

Run:

```bash
./mvnw -Dtest=DiceRollDetailVOTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add docs/sql/V20260727__kp_dice_tools.sql docs/sql/V20260720__dice_roll.sql docs/sql/V20260711__group_chat.sql src/test/java/com/me/galchat/init/console.sql src/main/java/com/me/galchat/domain/vo/DiceResolutionDataVO.java src/main/java/com/me/galchat/domain/vo/DiceResolutionVO.java src/main/java/com/me/galchat/domain/vo/DiceRollProgressVO.java src/main/java/com/me/galchat/domain/vo/KpDiceToolResult.java src/main/java/com/me/galchat/domain/po/DiceRollResult.java src/main/java/com/me/galchat/domain/dto/DiceRollResultCreateDTO.java src/main/java/com/me/galchat/domain/vo/DiceRollDetailVO.java src/test/java/com/me/galchat/domain/vo/DiceRollDetailVOTest.java
git commit -m "feat: add dice resolution persistence contract"
```

### Task 2: Implement CoC Rule Types and the Constant Insanity Catalog

**Files:**
- Create: `src/main/java/com/me/galchat/constant/CocCheckDifficulty.java`
- Create: `src/main/java/com/me/galchat/constant/CocPercentileModifier.java`
- Create: `src/main/java/com/me/galchat/constant/CocCheckOutcome.java`
- Create: `src/main/java/com/me/galchat/constant/DamageSourceMode.java`
- Modify: `src/main/java/com/me/galchat/constant/DiceRollConstant.java`
- Create: `src/main/java/com/me/galchat/constant/InsanityCatalog.java`
- Create: `src/main/java/com/me/galchat/service/impl/CocDiceRules.java`
- Test: `src/test/java/com/me/galchat/constant/InsanityCatalogTest.java`
- Test: `src/test/java/com/me/galchat/service/impl/CocDiceRulesTest.java`

**Interfaces:**
- Produces: `CocPercentileModifier.formula()` mapping `NORMAL/BONUS_1/BONUS_2/PENALTY_1/PENALTY_2` to `1D100`, `1D100#`, `1D100##`, `1D100$`, `1D100$$`.
- Produces: `CocDiceRules.CheckResolution`, `OpposedResolution`, `DamageResolution`, and `InsanityResolution`.
- Produces: `InsanityCatalog.code(int typeRoll, Integer detailRoll)` and `InsanityCatalog.display(String code)`.

- [ ] **Step 1: Write failing catalog coverage tests**

```java
@Test
void catalogContainsEveryFixedEntryAndResolvesStableCodes() {
    assertThat(InsanityCatalog.summarySymptoms()).hasSize(10);
    assertThat(InsanityCatalog.phobias()).hasSize(100);
    assertThat(InsanityCatalog.manias()).hasSize(100);
    assertThat(InsanityCatalog.code(9, 37)).isEqualTo("9:037");
    assertThat(InsanityCatalog.display("9:037"))
            .isEqualTo("恐惧症（昆虫恐惧症：害怕昆虫）");
    assertThat(InsanityCatalog.display("10:036"))
            .isEqualTo("躁狂症（嗜酒狂：反常地渴望饮酒）");
}

@ParameterizedTest
@ValueSource(strings = {"9", "9:000", "10:101", "11", "bad"})
void catalogRejectsInvalidStoredCodes(String code) {
    assertThatThrownBy(() -> InsanityCatalog.display(code))
            .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Write failing rule tests**

```java
@Test
void normalCheckCollapsesInternalDifficultyButPreservesCriticalAndFumble() {
    assertThat(CocDiceRules.resolveCheck(20, 60, CocCheckDifficulty.HARD).outcome())
            .isEqualTo(CocCheckOutcome.SUCCESS);
    assertThat(CocDiceRules.resolveCheck(40, 60, CocCheckDifficulty.HARD).outcome())
            .isEqualTo(CocCheckOutcome.FAILURE);
    assertThat(CocDiceRules.resolveCheck(1, 20, CocCheckDifficulty.EXTREME).outcome())
            .isEqualTo(CocCheckOutcome.CRITICAL_SUCCESS);
    assertThat(CocDiceRules.resolveCheck(96, 40, CocCheckDifficulty.REGULAR).outcome())
            .isEqualTo(CocCheckOutcome.FUMBLE);
}

@Test
void opposedTieUsesHigherTargetThenDeclaredTieWinner() {
    var sameRank = List.of(
            new CocDiceRules.OpposedCandidate("林恩", 60, 20),
            new CocDiceRules.OpposedCandidate("陈默", 50, 20));
    assertThat(CocDiceRules.resolveOpposed(sameRank, null).winner()).isEqualTo("林恩");

    var exactTie = List.of(
            new CocDiceRules.OpposedCandidate("林恩", 60, 20),
            new CocDiceRules.OpposedCandidate("陈默", 60, 20));
    assertThat(CocDiceRules.resolveOpposed(exactTie, "陈默").winner()).isEqualTo("陈默");
    assertThat(CocDiceRules.resolveOpposed(exactTie, null).draw()).isTrue();
}
```

- [ ] **Step 3: Run both tests and verify failure**

Run:

```bash
./mvnw -Dtest=InsanityCatalogTest,CocDiceRulesTest test
```

Expected: compilation fails because the catalog, enums, and rules do not exist.

- [ ] **Step 4: Populate `InsanityCatalog` as immutable code data**

Use `Map.copyOf`/unmodifiable maps. Copy the exact fixed text from:

- Summary symptoms 1–10: `output/pdf/第8章-理智.md:208`
- Phobias 1–100: `output/pdf/第8章-理智.md:245`
- Manias 1–100: `output/pdf/第8章-理智.md:288`

Do not create a database table or seed SQL. The stored phase code remains `1`–`8`, `9:NNN`, or `10:NNN`.

Expose:

```java
public static Map<Integer, String> summarySymptoms();
public static Map<Integer, String> phobias();
public static Map<Integer, String> manias();
public static String code(int typeRoll, Integer detailRoll);
public static String display(String code);
```

- [ ] **Step 5: Implement pure rule functions**

Implement at least:

```java
static CheckResolution resolveCheck(int roll, int target, CocCheckDifficulty difficulty);
static OpposedResolution resolveOpposed(
        List<OpposedCandidate> candidates, @Nullable String tieWinnerCharacterName);
static String selectSanLossFormula(
        CocCheckOutcome checkOutcome, String successFormula, String failureFormula);
static DamageResolution resolveDamage(int rolledDamage, int hpCurrent, int hpMax);
static InsanityResolution resolveInsanity(
        int typeRoll, int durationRoll, @Nullable Integer detailRoll);
```

Rules:

- Critical is roll `1`.
- Fumble is `96..100` when the effective required threshold is below `50`, otherwise only `100`.
- Ordinary success compares against `target`, `target/2`, or `target/5`, but returns the collapsed public outcome.
- Opposed comparison keeps critical/extreme/hard/regular/failure/fumble ranks internally; tied rank uses higher base target, then declared tie winner, then draw.
- Damage clamps HP at zero and marks major wound when one damage value is at least `ceil(hpMax / 2)`.
- Insanity type 9/10 requires a `1..100` detail roll; other types reject a supplied detail roll.

- [ ] **Step 6: Run rule and catalog tests**

Run:

```bash
./mvnw -Dtest=InsanityCatalogTest,CocDiceRulesTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/me/galchat/constant/CocCheckDifficulty.java src/main/java/com/me/galchat/constant/CocPercentileModifier.java src/main/java/com/me/galchat/constant/CocCheckOutcome.java src/main/java/com/me/galchat/constant/DamageSourceMode.java src/main/java/com/me/galchat/constant/DiceRollConstant.java src/main/java/com/me/galchat/constant/InsanityCatalog.java src/main/java/com/me/galchat/service/impl/CocDiceRules.java src/test/java/com/me/galchat/constant/InsanityCatalogTest.java src/test/java/com/me/galchat/service/impl/CocDiceRulesTest.java
git commit -m "feat: add CoC dice rules and insanity catalog"
```

### Task 3: Add Dice-Focused Character Card Access and Context Formatting

**Files:**
- Create: `src/main/java/com/me/galchat/domain/vo/CocDiceCharacterVO.java`
- Modify: `src/main/java/com/me/galchat/domain/po/CocCharacter.java`
- Modify: `src/main/java/com/me/galchat/mapper/CocCharacterMapper.java`
- Modify: `src/main/java/com/me/galchat/service/ICharacterCardService.java`
- Modify: `src/main/java/com/me/galchat/service/impl/CharacterCardServiceImpl.java`
- Create: `src/main/java/com/me/galchat/service/impl/CharacterCardContextFormatter.java`
- Modify: `src/test/java/com/me/galchat/service/impl/CharacterCardServiceImplTest.java`
- Test: `src/test/java/com/me/galchat/service/impl/CharacterCardContextFormatterTest.java`

**Interfaces:**
- Produces:

```java
record CocDiceCharacterVO(
        Long cardId,
        Long participantId,
        String name,
        Map<String, Integer> checkValues,
        Integer hpCurrent,
        Integer hpMax,
        Integer sanCurrent,
        Integer sanMax,
        Integer con,
        Integer armor,
        Boolean majorWound,
        Boolean unconscious,
        Boolean dying,
        Boolean dead,
        Boolean temporaryInsanity,
        String temporaryInsanityPhase,
        Integer temporaryInsanityRemainingHours) {}
```

- Produces on `ICharacterCardService`:

```java
CocDiceCharacterVO requireDiceCharacter(Long runId, String characterName);
List<CocDiceCharacterVO> listDiceCharacters(Long runId);
CocCharacter lockDiceCharacter(Long runId, Long cardId);
void updateDiceCharacter(CocCharacter character);
```

- Produces: `CharacterCardContextFormatter.format(List<CocDiceCharacterVO>)`.

- [ ] **Step 1: Write failing character resolution tests**

```java
@Test
void resolvesAttributeAndSkillChecksByRunAndUniqueCardName() {
    CocCharacter card = new CocCharacter()
            .setId(71L).setRunId(5L).setParticipantId(null)
            .setName("林恩").setCon(55).setSanCurrent(63);
    when(characterMapper.selectList(any())).thenReturn(List.of(card));
    when(skillMapper.selectList(any())).thenReturn(List.of(
            new CocCharacterSkill().setCharacterId(71L).setDisplayName("侦查").setValue(70)));

    CocDiceCharacterVO resolved = service.requireDiceCharacter(5L, "林恩");

    assertThat(resolved.checkValues())
            .containsEntry("CON", 55)
            .containsEntry("体质", 55)
            .containsEntry("SAN", 63)
            .containsEntry("理智", 63)
            .containsEntry("侦查", 70);
}

@Test
void rejectsMissingOrAmbiguousCardNamesInsideOneRun() {
    when(characterMapper.selectList(any())).thenReturn(List.of());
    assertThatThrownBy(() -> service.requireDiceCharacter(5L, "林恩"))
            .hasMessage("人物卡不存在");
}
```

- [ ] **Step 2: Write a failing insanity-context test**

```java
@Test
void formatsStoredInsanityCodeAsReadableCardContext() {
    CocDiceCharacterVO card = new CocDiceCharacterVO(
            71L, null, "林恩", Map.of("CON", 55),
            10, 10, 54, 60, 55, 0,
            false, false, false, false,
            true, "9:037", 4);

    String text = formatter.format(List.of(card));

    assertThat(text)
            .contains("<investigator-card")
            .contains("林恩")
            .contains("临时疯狂：恐惧症（昆虫恐惧症：害怕昆虫）")
            .contains("剩余4小时");
}
```

- [ ] **Step 3: Run tests and verify failure**

Run:

```bash
./mvnw -Dtest=CharacterCardServiceImplTest,CharacterCardContextFormatterTest test
```

Expected: compilation fails because the dice lookup and formatter do not exist.

- [ ] **Step 4: Add mapper locking and service lookup**

Add:

```java
@Select("SELECT * FROM coc_character WHERE id = #{id} AND run_id = #{runId} FOR UPDATE")
CocCharacter selectByIdAndRunIdForUpdate(@Param("runId") Long runId, @Param("id") Long id);
```

`requireDiceCharacter` must:

- Normalize the supplied name with `trim()`.
- Query only the supplied `runId`.
- Require exactly one matching `coc_character.name`.
- Build case-insensitive English aliases and Chinese aliases for STR/CON/SIZ/DEX/APP/INT/POW/EDU/SAN/LUCK.
- Add every `CocCharacterSkill.displayName -> value`.
- Keep the participant ID as the value later persisted in `dice_roll_result.character_id`.

- [ ] **Step 5: Rename the Java duration property without changing the database**

Replace the PO property with:

```java
@TableField("temporary_insanity_remaining_rounds")
private Integer temporaryInsanityRemainingHours;
```

The current code has no other production accessor for this property. Update the new formatter and its tests to use the `temporaryInsanityRemainingHours` accessor. Do not alter the database column.

- [ ] **Step 6: Implement context formatting**

`CharacterCardContextFormatter` must render HP, SAN, relevant statuses and the resolved insanity description. Invalid legacy phase codes must render a stable fallback such as `临时疯狂（编号：<code>）` instead of breaking prompt assembly.

- [ ] **Step 7: Run character tests**

Run:

```bash
./mvnw -Dtest=CharacterCardServiceImplTest,CharacterCardContextFormatterTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/me/galchat/domain/vo/CocDiceCharacterVO.java src/main/java/com/me/galchat/domain/po/CocCharacter.java src/main/java/com/me/galchat/mapper/CocCharacterMapper.java src/main/java/com/me/galchat/service/ICharacterCardService.java src/main/java/com/me/galchat/service/impl/CharacterCardServiceImpl.java src/main/java/com/me/galchat/service/impl/CharacterCardContextFormatter.java src/test/java/com/me/galchat/service/impl/CharacterCardServiceImplTest.java src/test/java/com/me/galchat/service/impl/CharacterCardContextFormatterTest.java
git commit -m "feat: expose character cards to dice orchestration"
```

### Task 4: Introduce the Implicit KP Actor Across Reply Plans and Context

**Files:**
- Modify: `src/main/java/com/me/galchat/constant/GroupChatConstant.java`
- Modify: `src/main/java/com/me/galchat/constant/ChatToolContextConstant.java`
- Create: `src/main/java/com/me/galchat/groupchat/runtime/GroupActorRef.java`
- Modify: `src/main/java/com/me/galchat/service/impl/GroupReplyPlanService.java`
- Modify: `src/main/java/com/me/galchat/service/impl/GroupConversationService.java`
- Modify: `src/main/java/com/me/galchat/groupchat/runtime/GroupAgentPolicy.java`
- Modify: `src/main/java/com/me/galchat/groupchat/runtime/chat/ChatGroupAgentPolicy.java`
- Modify: `src/main/java/com/me/galchat/groupchat/runtime/trpg/TrpgGroupAgentPolicy.java`
- Modify: `src/main/java/com/me/galchat/groupchat/runtime/chat/ChatGroupContextPolicy.java`
- Modify: `src/main/java/com/me/galchat/groupchat/runtime/trpg/TrpgGroupContextPolicy.java`
- Modify: `src/main/java/com/me/galchat/service/impl/GroupContextAssembler.java`
- Modify: `src/main/java/com/me/galchat/groupchat/tool/GroupToolContextFactory.java`
- Modify: `src/main/java/com/me/galchat/groupchat/tool/GroupToolHistoryAssembler.java`
- Modify: `src/main/java/com/me/galchat/service/impl/ChatServiceImpl.java`
- Modify: `src/main/java/com/me/galchat/service/impl/GroupChatService.java`
- Modify tests: `src/test/java/com/me/galchat/service/impl/GroupReplyPlanServiceTest.java`
- Modify tests: `src/test/java/com/me/galchat/service/impl/GroupContextAssemblerTest.java`
- Modify tests: `src/test/java/com/me/galchat/groupchat/tool/GroupToolContextFactoryTest.java`
- Modify tests: `src/test/java/com/me/galchat/groupchat/tool/GroupToolHistoryAssemblerTest.java`
- Modify tests: `src/test/java/com/me/galchat/service/impl/GroupChatServiceTest.java`
- Modify tests: `src/test/java/com/me/galchat/service/impl/GroupReplyPlanSnapshotServiceTest.java`
- Test: `src/test/java/com/me/galchat/groupchat/runtime/trpg/TrpgGroupAgentPolicyTest.java`

**Interfaces:**
- Produces: `GroupActorRef(String type, Long id)` and `matches(String type, Long id)`.
- Changes: `GroupAgentPolicy.characterName(...)` to `actorName(Long userWorldId, GroupActorRef actor)`.
- Changes: context/history assembly methods receive `GroupActorRef`, never a bare nullable character ID.
- Produces ToolContext key: `actorType`; omits `characterId` for KP.

- [ ] **Step 1: Write failing reply-plan validation tests**

```java
@Test
void trpgPlanAcceptsOneImplicitKpIdentityWithNullActorId() {
    GroupReplyPlanDTO request = sceneRequest(item(GroupChatConstant.ACTOR_KP, null));
    when(fixture.conversationService.requireActive(7L))
            .thenReturn(activeConversation(GroupChatConstant.MODE_TRPG, null));

    fixture.service.replace(7L, request);

    verify(fixture.itemMapper).insert(argThat(item ->
            GroupChatConstant.ACTOR_KP.equals(item.getActorType())
                    && item.getActorId() == null));
}

@Test
void normalChatAndNonNullKpIdsAreRejected() {
    assertThatThrownBy(() -> fixture.service.replace(
            7L, userRequest(item(GroupChatConstant.ACTOR_KP, null))))
            .hasMessageContaining("TRPG");
    assertThatThrownBy(() -> fixture.service.replace(
            7L, sceneRequest(item(GroupChatConstant.ACTOR_KP, 9L))))
            .hasMessageContaining("KP");
}

@Test
void replyPlanSnapshotCapturesKpWithNullActorId() {
    GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
    GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
    GroupReplyPlanItemMapper itemMapper = mock(GroupReplyPlanItemMapper.class);
    GroupReplyPlanSnapshotService service = new GroupReplyPlanSnapshotService(
            conversationMapper, planMapper, itemMapper, mock(GroupReplyPlanService.class));
    when(conversationMapper.selectList(any())).thenReturn(List.of(
            new GroupConversation().setId(7L).setUserWorldId(1L).setActiveReplyPlanId(10L)));
    when(planMapper.selectById(10L)).thenReturn(
            new GroupReplyPlan().setId(10L).setConversationId(7L)
                    .setSource(GroupChatConstant.PLAN_SOURCE_SCENE).setContextId(100L));
    when(itemMapper.selectList(any())).thenReturn(List.of(
            new GroupReplyPlanItem().setPlanId(10L).setGroupKey("scene:1")
                    .setGroupName("地下室").setGroupOrder(1).setItemOrder(1)
                    .setActorType(GroupChatConstant.ACTOR_KP).setActorId(null)));

    var captured = service.capture(1L).getFirst().getActivePlan()
            .getGroups().getFirst().getItems().getFirst();

    assertThat(captured.getActorType()).isEqualTo(GroupChatConstant.ACTOR_KP);
    assertThat(captured.getActorId()).isNull();
}
```

- [ ] **Step 2: Write failing actor-ownership and ToolContext tests**

```java
@Test
void kpOwnsItsPrivateToolHistoryEvenThoughItsIdIsNull() {
    GroupChatMessage kpMessage = message(41L, GroupChatConstant.ACTOR_KP, null);
    Map<Long, List<Message>> own = assembler.beforeMessages(
            List.of(kpMessage), new GroupActorRef(GroupChatConstant.ACTOR_KP, null));
    Map<Long, List<Message>> other = assembler.beforeMessages(
            List.of(kpMessage), new GroupActorRef(GroupChatConstant.ACTOR_CHARACTER, null));
    assertThat(own.get(41L)).hasSize(2);
    assertThat(other.get(41L)).isEmpty();
}

@Test
void kpToolContextContainsActorTypeButNoCharacterId() {
    Map<String, Object> context = factory.create(conversation, kpAction, 41L, "NORMAL");
    assertThat(context)
            .containsEntry(ChatToolContextConstant.ACTOR_TYPE_KEY, GroupChatConstant.ACTOR_KP)
            .doesNotContainKey(ChatToolContextConstant.CHARACTER_ID_KEY);
}
```

- [ ] **Step 3: Run the focused tests and verify failure**

Run:

```bash
./mvnw -Dtest=GroupReplyPlanServiceTest,GroupContextAssemblerTest,GroupToolContextFactoryTest,GroupToolHistoryAssemblerTest,TrpgGroupAgentPolicyTest test
```

Expected: tests fail because null KP actors are rejected or compared only by ID.

- [ ] **Step 4: Implement actor validation**

Add:

```java
public static final String ACTOR_KP = "kp";
public static final String ACTOR_TYPE_KEY = "actorType";
```

Validation rules:

- `kp + null ID` is valid only when `conversation.mode == trpg`.
- `kp + non-null ID` is invalid.
- `character + non-null ID` continues through enabled-member validation.
- Any other reply actor type is invalid.
- The duplicate key inside a plan group is `actorType + "\n" + actorId`, so two KP entries in the same group remain invalid.

- [ ] **Step 5: Replace nullable-ID ownership with `GroupActorRef`**

Use:

```java
public record GroupActorRef(String type, Long id) {
    public boolean matches(String otherType, Long otherId) {
        return Objects.equals(type, otherType) && Objects.equals(id, otherId);
    }
}
```

Update `GroupContextAssembler`, `GroupToolHistoryAssembler`, chat/TRPG context policies, and agent-name resolution. A KP-authored normal message is an `AssistantMessage` only for KP; it is a `UserMessage` for every character.

- [ ] **Step 6: Add a world-only KP prompt path**

Refactor `ChatServiceImpl` to expose:

```java
public String buildWorldSystemPrompt(Long worldId, Long userWorldId);
```

The existing character prompt appends `buildCharacterPrompt` to the world-only prompt. `TrpgGroupAgentPolicy` uses the world-only prompt plus all formatted investigator cards for KP, and the existing character prompt plus that actor's investigator card for character actors.

KP system instructions must state:

- KP is not a visible investigator.
- KP may call at most one state-changing dice tool in a response.
- After calling one, it must not emit narration or JSON.

Do not add tools yet; Task 9 wires them.

- [ ] **Step 7: Run focused actor tests**

Run:

```bash
./mvnw -Dtest=GroupReplyPlanServiceTest,GroupContextAssemblerTest,GroupToolContextFactoryTest,GroupToolHistoryAssemblerTest,TrpgGroupAgentPolicyTest,GroupChatServiceTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/me/galchat/constant/GroupChatConstant.java src/main/java/com/me/galchat/constant/ChatToolContextConstant.java src/main/java/com/me/galchat/groupchat/runtime/GroupActorRef.java src/main/java/com/me/galchat/service/impl/GroupReplyPlanService.java src/main/java/com/me/galchat/service/impl/GroupConversationService.java src/main/java/com/me/galchat/groupchat/runtime/GroupAgentPolicy.java src/main/java/com/me/galchat/groupchat/runtime/chat/ChatGroupAgentPolicy.java src/main/java/com/me/galchat/groupchat/runtime/trpg/TrpgGroupAgentPolicy.java src/main/java/com/me/galchat/groupchat/runtime/chat/ChatGroupContextPolicy.java src/main/java/com/me/galchat/groupchat/runtime/trpg/TrpgGroupContextPolicy.java src/main/java/com/me/galchat/service/impl/GroupContextAssembler.java src/main/java/com/me/galchat/groupchat/tool/GroupToolContextFactory.java src/main/java/com/me/galchat/groupchat/tool/GroupToolHistoryAssembler.java src/main/java/com/me/galchat/service/impl/ChatServiceImpl.java src/main/java/com/me/galchat/service/impl/GroupChatService.java src/test/java/com/me/galchat/service/impl/GroupReplyPlanServiceTest.java src/test/java/com/me/galchat/service/impl/GroupContextAssemblerTest.java src/test/java/com/me/galchat/groupchat/tool/GroupToolContextFactoryTest.java src/test/java/com/me/galchat/groupchat/tool/GroupToolHistoryAssemblerTest.java src/test/java/com/me/galchat/service/impl/GroupChatServiceTest.java src/test/java/com/me/galchat/service/impl/GroupReplyPlanSnapshotServiceTest.java src/test/java/com/me/galchat/groupchat/runtime/trpg/TrpgGroupAgentPolicyTest.java
git commit -m "feat: add implicit KP group actor"
```

### Task 5: Separate Raw Dice Persistence from Public Dice APIs

**Files:**
- Create: `src/main/java/com/me/galchat/domain/vo/DiceRollAggregate.java`
- Modify: `src/main/java/com/me/galchat/service/IDiceRollInternalService.java`
- Create: `src/main/java/com/me/galchat/service/impl/DiceRollInternalServiceImpl.java`
- Modify: `src/main/java/com/me/galchat/service/impl/DiceRollServiceImpl.java`
- Modify: `src/main/java/com/me/galchat/service/IDiceRollService.java`
- Modify: `src/main/java/com/me/galchat/domain/dto/DiceRollSummaryUpdateDTO.java`
- Modify: `src/main/java/com/me/galchat/mapper/DiceRollResultMapper.java`
- Modify: `src/test/java/com/me/galchat/service/impl/DiceRollServiceImplTest.java`
- Test: `src/test/java/com/me/galchat/service/impl/DiceRollInternalServiceImplTest.java`

**Interfaces:**
- Produces:

```java
record DiceRollAggregate(DiceRollSummary summary, List<DiceRollResult> results) {}
DiceRollAggregate createDiceRoll(Long conversationId, String reason, List<DiceRollResultCreateDTO> results);
DiceRollSummary requireSummary(Long summaryId);
DiceRollSummary requireSummaryForUpdate(Long summaryId);
DiceRollResult requireResult(Long resultId);
List<DiceRollResult> listResultEntities(Long summaryId);
List<DiceRollResult> appendDiceRollRound(
        Long conversationId, Long summaryId, List<DiceRollResultCreateDTO> results);
void saveResult(DiceRollResult result);
void saveSummary(DiceRollSummary summary);
```

- Removes: caller-supplied `totalResult` from `appendDiceRollRound`.

- [ ] **Step 1: Write failing raw-storage tests**

```java
@Test
void constantPlayerFormulaIsResolvedImmediatelyAndDoesNotMakeSummaryPending() {
    DiceRollAggregate aggregate = service.createDiceRoll(
            7L, "SAN损失", List.of(draft(null, "0", resolution("SAN_LOSS"))));

    assertThat(aggregate.results()).singleElement().satisfies(result -> {
        assertThat(result.getResultData().getResult()).isZero();
        assertThat(result.getResolvedAt()).isNull(); // semantic settlement belongs to orchestration
    });
    assertThat(aggregate.summary().getStatus()).isEqualTo(DiceRollConstant.STATUS_COMPLETED);
}

@Test
void realPlayerDiceKeepsPreparedModulesAndPendingStatus() {
    DiceRollAggregate aggregate = service.createDiceRoll(
            7L, "侦查", List.of(draft(null, "1D100", resolution("CHECK"))));
    assertThat(aggregate.results().getFirst().getResultData().getModules()).isNotEmpty();
    assertThat(aggregate.results().getFirst().getResultData().getResult()).isNull();
    assertThat(aggregate.summary().getStatus()).isEqualTo(DiceRollConstant.STATUS_PENDING);
}
```

- [ ] **Step 2: Run the storage tests and verify failure**

Run:

```bash
./mvnw -Dtest=DiceRollInternalServiceImplTest test
```

Expected: compilation fails because the internal implementation and aggregate do not exist.

- [ ] **Step 3: Move mapper operations into `DiceRollInternalServiceImpl`**

The raw service decides whether a user expression is a true pending roll by parsing it:

```java
DiceRollResultVO prepared = DiceUtils.prepare(formula);
boolean pendingUserDice = characterId == null && !prepared.getModules().isEmpty();
DiceRollResultVO data = pendingUserDice ? prepared : DiceUtils.roll(formula);
```

It must not:

- Interpret `resolutionData`.
- Modify character cards.
- Generate `totalResult`.
- Set `resolvedAt`.

Appending a round locks the summary, requires the current status to be completed, increments `round_count`, and derives the new status from actual pending user dice.

- [ ] **Step 4: Keep public query authorization in `DiceRollServiceImpl`**

`DiceRollServiceImpl.getSummary/listResults` call the raw service and authorize through `summary.conversationId`. Keep a temporary raw user-roll implementation so this commit remains buildable; Task 6 replaces it with semantic orchestration.

Change `IDiceRollService.roll` return type now to `DiceRollProgressVO`, returning `createdResults=List.of()` in the temporary implementation.

- [ ] **Step 5: Remove manual total-result updates**

Delete `totalResult` from `DiceRollSummaryUpdateDTO` or remove the DTO if no caller remains. No Controller or KP input may update it.

- [ ] **Step 6: Run dice persistence tests**

Run:

```bash
./mvnw -Dtest=DiceRollInternalServiceImplTest,DiceRollServiceImplTest,DiceUtilsTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/me/galchat/domain/vo/DiceRollAggregate.java src/main/java/com/me/galchat/service/IDiceRollInternalService.java src/main/java/com/me/galchat/service/impl/DiceRollInternalServiceImpl.java src/main/java/com/me/galchat/service/impl/DiceRollServiceImpl.java src/main/java/com/me/galchat/service/IDiceRollService.java src/main/java/com/me/galchat/domain/dto/DiceRollSummaryUpdateDTO.java src/main/java/com/me/galchat/mapper/DiceRollResultMapper.java src/test/java/com/me/galchat/service/impl/DiceRollServiceImplTest.java src/test/java/com/me/galchat/service/impl/DiceRollInternalServiceImplTest.java
git commit -m "refactor: separate dice persistence service"
```

### Task 6: Orchestrate Checks, Opposed Checks, Pushes, and User Resolution

**Files:**
- Create: `src/main/java/com/me/galchat/domain/dto/KpDiceRequestDTOs.java`
- Create: `src/main/java/com/me/galchat/service/DiceFollowUpLocator.java`
- Create: `src/main/java/com/me/galchat/service/ICocDiceOrchestrationService.java`
- Create: `src/main/java/com/me/galchat/service/impl/CocDiceSummaryFormatter.java`
- Create: `src/main/java/com/me/galchat/service/impl/CocDiceOrchestrationService.java`
- Modify: `src/main/java/com/me/galchat/mapper/GroupChatToolCallMapper.java`
- Modify: `src/main/java/com/me/galchat/groupchat/tool/GroupToolCallStore.java`
- Modify: `src/main/java/com/me/galchat/service/impl/DiceRollServiceImpl.java`
- Modify: `src/main/java/com/me/galchat/constant/DiceRollConstant.java`
- Test: `src/test/java/com/me/galchat/service/impl/CocDiceSummaryFormatterTest.java`
- Test: `src/test/java/com/me/galchat/service/impl/CocDiceOrchestrationServiceTest.java`
- Modify: `src/test/java/com/me/galchat/groupchat/tool/GroupToolCallStoreTest.java`
- Modify: `src/test/java/com/me/galchat/service/impl/DiceRollServiceImplTest.java`

**Interfaces:**
- Produces request records:

```java
record Check(String reason, CocCheckDifficulty difficulty, List<CheckTarget> targets) {}
record CheckTarget(String characterName, String checkName, CocPercentileModifier modifier) {}
record Opposed(String reason, List<CheckTarget> targets, String tieWinnerCharacterName) {}
record Pushed(String reason, List<String> characterNames) {}
record SanCheck(String reason, List<String> characterNames) {}
```

- Produces orchestration methods:

```java
KpDiceToolResult requestCheck(Long conversationId, Long runId, KpDiceRequestDTOs.Check request);
KpDiceToolResult requestOpposedCheck(Long conversationId, Long runId, KpDiceRequestDTOs.Opposed request);
KpDiceToolResult requestPushedCheck(Long conversationId, Long runId, KpDiceRequestDTOs.Pushed request);
KpDiceToolResult requestSanCheck(Long conversationId, Long runId, KpDiceRequestDTOs.SanCheck request);
DiceRollProgressVO rollPlayerResult(Long resultId);
```

- Produces:

```java
interface DiceFollowUpLocator {
    Long requireLatestSummaryId(Long conversationId, Set<String> compatibleToolNames);
}

String CocDiceSummaryFormatter.rebuildTotalResult(List<DiceRollResult> results);
String CocDiceSummaryFormatter.formatRound(List<DiceRollResult> completedRound);
```

- [ ] **Step 1: Write failing orchestration tests**

```java
@Test
void groupCheckCreatesPlayerPlaceholderAndResolvesAgentSemantics() {
    when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(player("林恩", 70));
    when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(agent(12L, "陈默", 45));

    KpDiceToolResult result = service.requestCheck(
            7L, 5L, check("调查书房", REGULAR, target("林恩", "侦查"), target("陈默", "侦查")));

    assertThat(result.results()).hasSize(2);
    assertThat(result.results().get(0).getResultData().getResult()).isNull();
    assertThat(result.results().get(1).getResolution().getOutcome())
            .containsKey("category");
}

@Test
void opposedResultReturnsWinnerInsteadOfRawRanks() {
    KpDiceToolResult result = completedOpposed("林恩", "陈默", "林恩");
    assertThat(result.semanticResult()).isEqualTo("林恩获胜");
    assertThat(result.semanticResult()).doesNotContain("困难", "极难");
}
```

Add retry coverage:

```java
@Test
void repeatedPlayerRollReturnsSavedResultWithoutRollingOrApplyingAgain() {
    pending.setResolvedAt(LocalDateTime.now());
    DiceRollProgressVO result = service.rollPlayerResult(pending.getId());
    assertThat(result.rolledResult().getId()).isEqualTo(pending.getId());
    verify(internal, never()).saveResult(any());
    verify(cards, never()).updateDiceCharacter(any());
}
```

- [ ] **Step 2: Run the orchestration tests and verify failure**

Run:

```bash
./mvnw -Dtest=CocDiceOrchestrationServiceTest,CocDiceSummaryFormatterTest test
```

Expected: compilation fails because request records and orchestration do not exist.

- [ ] **Step 3: Implement check draft creation**

Every result's rule snapshot contains exact stable keys:

```java
Map.of(
    "cardId", card.cardId(),
    "characterName", card.name(),
    "checkName", target.checkName(),
    "targetValue", checkValue,
    "difficulty", request.difficulty().name(),
    "modifier", modifier.name(),
    "pushed", false
)
```

Use `CocPercentileModifier.formula()` for the stored formula. Set `characterId` to `card.participantId()`.

Agent and constant results are resolved immediately in the same transaction; player dice remain pending.

Annotate all public state-changing orchestration methods with `@Transactional(rollbackFor = Exception.class)`. This transaction is the endpoint boundary for user clicks and joins the outer tool-manager transaction for KP tools.

- [ ] **Step 4: Implement opposed and pushed rules**

`requestOpposedCheck` requires at least two unique card names and stores the optional tie winner in each rule snapshot. Once every result in the round is resolved, `CocDiceSummaryFormatter` computes one winner/draw line.

`requestPushedCheck`:

- Resolves the latest compatible `requestCheck` summary using `DiceFollowUpLocator`.
- Requires the previous round to be completed.
- Allows only prior `FAILURE`, not success, critical, fumble, SAN, combat, or opposed results.
- Copies check name, target value, difficulty and modifier; sets `pushed=true`.

- [ ] **Step 5: Implement the previous-summary locator**

Make `GroupToolCallStore` implement `DiceFollowUpLocator`. Add a MyBatis query joining:

```text
group_chat_tool_call
  -> group_chat_reply_step
  -> group_chat_turn
```

Filter by `group_chat_turn.conversation_id`, non-null `dice_roll_summary_id`, and the supplied compatible tool-name set. Order by tool-call ID descending and return one row. The locator throws a business error when no compatible row exists. Add a store test proving a row from another conversation is never selected.

- [ ] **Step 6: Implement generic user resolution**

Transaction order:

1. Read result to find summary ID.
2. Lock summary.
3. Re-read result.
4. Authorize active `summary.conversationId`.
5. Return saved data immediately when `resolvedAt != null`.
6. Require `characterId == null`, current `roundNo`, and real unresolved dice.
7. Roll stored formula.
8. Dispatch by `resolutionData.type`.
9. Save result and `resolvedAt`.
10. Rebuild completed-round total and status.

`DiceRollServiceImpl.roll` delegates to this method and returns `DiceRollProgressVO`.

- [ ] **Step 7: Implement deterministic summary rebuilding**

`CocDiceSummaryFormatter` groups results by `roundNo`, ignores any incomplete round, and returns each complete round's semantic line in numeric order. It must recompute opposed winners from stored rolls/rules, not append strings.

- [ ] **Step 8: Run basic orchestration tests**

Run:

```bash
./mvnw -Dtest=CocDiceOrchestrationServiceTest,CocDiceSummaryFormatterTest,DiceRollServiceImplTest,GroupToolCallStoreTest test
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/me/galchat/domain/dto/KpDiceRequestDTOs.java src/main/java/com/me/galchat/service/DiceFollowUpLocator.java src/main/java/com/me/galchat/service/ICocDiceOrchestrationService.java src/main/java/com/me/galchat/service/impl/CocDiceSummaryFormatter.java src/main/java/com/me/galchat/service/impl/CocDiceOrchestrationService.java src/main/java/com/me/galchat/mapper/GroupChatToolCallMapper.java src/main/java/com/me/galchat/groupchat/tool/GroupToolCallStore.java src/main/java/com/me/galchat/service/impl/DiceRollServiceImpl.java src/main/java/com/me/galchat/constant/DiceRollConstant.java src/test/java/com/me/galchat/service/impl/CocDiceSummaryFormatterTest.java src/test/java/com/me/galchat/service/impl/CocDiceOrchestrationServiceTest.java src/test/java/com/me/galchat/groupchat/tool/GroupToolCallStoreTest.java src/test/java/com/me/galchat/service/impl/DiceRollServiceImplTest.java
git commit -m "feat: orchestrate CoC checks and opposed rolls"
```

### Task 7: Add SAN Loss and Automatic Temporary Insanity Rounds

**Files:**
- Modify: `src/main/java/com/me/galchat/domain/dto/KpDiceRequestDTOs.java`
- Modify: `src/main/java/com/me/galchat/service/ICocDiceOrchestrationService.java`
- Create: `src/main/java/com/me/galchat/service/DiceRandomSource.java`
- Create: `src/main/java/com/me/galchat/service/impl/ThreadLocalDiceRandomSource.java`
- Modify: `src/main/java/com/me/galchat/service/impl/CocDiceOrchestrationService.java`
- Modify: `src/main/java/com/me/galchat/service/impl/CocDiceSummaryFormatter.java`
- Modify: `src/main/java/com/me/galchat/constant/DiceRollConstant.java`
- Modify: `src/test/java/com/me/galchat/service/impl/CocDiceOrchestrationServiceTest.java`
- Modify: `src/test/java/com/me/galchat/service/impl/CocDiceSummaryFormatterTest.java`

**Interfaces:**
- Adds:

```java
record SanLoss(String reason, String successFormula, String failureFormula) {}
KpDiceToolResult rollSanLoss(Long conversationId, Long runId, KpDiceRequestDTOs.SanLoss request);
interface DiceRandomSource {
    int d100();
}
```

- [ ] **Step 1: Write failing SAN-chain tests**

```java
@Test
void sanLossUsesActualBranchAndDefersMadnessWhilePlayerDiceIsPending() {
    KpDiceToolResult created = service.rollSanLoss(
            7L, 5L, new SanLoss("目睹怪物", "0", "1D6"));

    assertThat(created.summary().getRoundCount()).isEqualTo(2);
    assertThat(created.results())
            .filteredOn(r -> r.getCharacterId() == null)
            .singleElement()
            .satisfies(r -> {
                assertThat(r.getResultData().getFormula()).isEqualTo("1D6");
                assertThat(r.getResultData().getResult()).isNull();
            });
    verify(internal, never()).appendDiceRollRound(
            eq(7L), eq(summaryId), argThat(drafts -> drafts.stream()
                    .anyMatch(d -> "TEMPORARY_INSANITY_TYPE"
                            .equals(d.getResolutionData().getType()))));
}

@Test
void finishingPlayerSanLossCreatesOneMadnessRoundForEveryQualifiedCard() {
    DiceRollProgressVO progress = service.rollPlayerResult(playerSanLossResultId);
    assertThat(progress.createdResults()).hasSize(4); // type + duration for user and agent
    assertThat(progress.createdResults()).extracting(DiceRollDetailVO::getRoundNo)
            .containsOnly(3);
}

@Test
void typeNineStoresRandomCatalogNumberWithoutCreatingD100Result() {
    when(randomSource.d100()).thenReturn(37);
    completeInsanityPair(typeResultWithRoll(9), durationResultWithRoll(4));
    assertThat(allSummaryResults()).noneMatch(r -> "1D100".equals(r.getResultData().getFormula()));
    assertThat(card.getTemporaryInsanityPhase()).isEqualTo("9:037");
    assertThat(card.getTemporaryInsanityRemainingHours()).isEqualTo(4);
}
```

- [ ] **Step 2: Run SAN tests and verify failure**

Run:

```bash
./mvnw -Dtest=CocDiceOrchestrationServiceTest,CocDiceSummaryFormatterTest test
```

Expected: SAN-chain cases fail because `rollSanLoss` and automatic rounds are absent.

- [ ] **Step 3: Implement SAN branch selection and card mutation**

Locate only the latest completed `requestSanCheck` summary. For each previous result:

- `CRITICAL_SUCCESS`/`SUCCESS` selects `successFormula`.
- `FAILURE`/`FUMBLE` selects `failureFormula`.
- Save the selected formula on the new result.
- On semantic settlement, lock the card, clamp SAN to zero, save before/after values in `effect`, and set `resolvedAt`.

Do not perform an INT check.

- [ ] **Step 4: Implement one idempotent madness round**

Under the locked summary:

```java
boolean userDicePending = currentRound.stream().anyMatch(this::isPendingUserDice);
if (!userDicePending) {
    appendTemporaryInsanityRoundIfNeeded(summary, currentRound);
}
```

For each SAN-loss result with actual loss `>= 5`, create exactly:

- `TEMPORARY_INSANITY_TYPE` with formula `1D10`.
- `TEMPORARY_INSANITY_DURATION` with formula `1D10`.

Both rows use the SAN-loss result ID as `sourceResultId`. Before append, scan existing results for either type with the same source ID; if found, do not create another round.

- [ ] **Step 5: Settle a madness pair only after both results resolve**

When both type and duration exist and are resolved:

- Call `DiceRandomSource.d100()` only for type `9` or `10`. `ThreadLocalDiceRandomSource` implements it with `ThreadLocalRandom.current().nextInt(1, 101)`, while tests inject a mock.
- Store the generated number in the type result's `outcome`.
- Set `temporaryInsanity=true`.
- Store `InsanityCatalog.code(typeRoll, detailRoll)` in `temporaryInsanityPhase`.
- Store duration in `temporaryInsanityRemainingHours`.
- Put the card effect on the duration result, which is the pair-completion point.

The formatter emits one line per pair:

```text
林恩理智-6；进入临时疯狂：恐惧症（昆虫恐惧症：害怕昆虫），持续4小时
```

- [ ] **Step 6: Run SAN-chain tests**

Run:

```bash
./mvnw -Dtest=CocDiceOrchestrationServiceTest,CocDiceSummaryFormatterTest,InsanityCatalogTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/me/galchat/domain/dto/KpDiceRequestDTOs.java src/main/java/com/me/galchat/service/ICocDiceOrchestrationService.java src/main/java/com/me/galchat/service/DiceRandomSource.java src/main/java/com/me/galchat/service/impl/ThreadLocalDiceRandomSource.java src/main/java/com/me/galchat/service/impl/CocDiceOrchestrationService.java src/main/java/com/me/galchat/service/impl/CocDiceSummaryFormatter.java src/main/java/com/me/galchat/constant/DiceRollConstant.java src/test/java/com/me/galchat/service/impl/CocDiceOrchestrationServiceTest.java src/test/java/com/me/galchat/service/impl/CocDiceSummaryFormatterTest.java
git commit -m "feat: resolve SAN loss and temporary insanity"
```

### Task 8: Add Standalone/Follow-Up Damage and Automatic Major-Wound CON Rounds

**Files:**
- Modify: `src/main/java/com/me/galchat/domain/dto/KpDiceRequestDTOs.java`
- Modify: `src/main/java/com/me/galchat/service/ICocDiceOrchestrationService.java`
- Modify: `src/main/java/com/me/galchat/service/impl/CocDiceOrchestrationService.java`
- Modify: `src/main/java/com/me/galchat/service/impl/CocDiceSummaryFormatter.java`
- Modify: `src/main/java/com/me/galchat/constant/DiceRollConstant.java`
- Modify: `src/test/java/com/me/galchat/service/impl/CocDiceOrchestrationServiceTest.java`
- Modify: `src/test/java/com/me/galchat/service/impl/CocDiceSummaryFormatterTest.java`

**Interfaces:**
- Adds:

```java
record Damage(String reason, DamageSourceMode sourceMode, List<DamageTarget> targets) {}
record DamageTarget(
        String targetCharacterName,
        String sourceCharacterName,
        String formula) {}
KpDiceToolResult rollDamage(Long conversationId, Long runId, KpDiceRequestDTOs.Damage request);
```

- [ ] **Step 1: Write failing damage-chain tests**

```java
@Test
void standaloneDamageCreatesFirstRoundAndAppliesHp() {
    KpDiceToolResult result = service.rollDamage(
            7L, 5L, damage(STANDALONE, target("林恩", null, "1D6")));
    assertThat(result.summary().getRoundCount()).isEqualTo(1);
    assertThat(lockedCard.getHpCurrent()).isLessThan(lockedCard.getHpMax());
}

@Test
void followUpDamageRequiresAWinningOrSuccessfulSource() {
    assertThatThrownBy(() -> service.rollDamage(
            7L, 5L, damage(FOLLOW_UP, target("邪教徒", "林恩", "1D6"))))
            .hasMessageContaining("前置检定未成功");
}

@Test
void playerDamageDefersAllMajorWoundConRollsUntilCurrentRoundCompletes() {
    KpDiceToolResult created = service.rollDamage(7L, 5L, mixedDamageRequest());
    assertThat(created.summary().getStatus()).isEqualTo(DiceRollConstant.STATUS_PENDING);
    assertThat(created.summary().getRoundCount()).isEqualTo(sourceRound + 1);

    DiceRollProgressVO progress = service.rollPlayerResult(playerDamageResultId);
    assertThat(progress.createdResults()).extracting(r -> r.getResolution().getType())
            .containsOnly("MAJOR_WOUND_CON");
}
```

- [ ] **Step 2: Run damage tests and verify failure**

Run:

```bash
./mvnw -Dtest=CocDiceOrchestrationServiceTest,CocDiceSummaryFormatterTest test
```

Expected: damage-chain cases fail because the request and orchestration path do not exist.

- [ ] **Step 3: Implement source modes**

`STANDALONE` creates a new summary and requires every `sourceCharacterName` to be null.

`FOLLOW_UP`:

- Requires every `sourceCharacterName`.
- Locates the latest compatible completed check/opposed summary.
- For a skill check, requires the named source result to be critical/successful.
- For an opposed check, requires the named source to be the computed winner.
- Rejects a source whose check is dodge-only.
- Appends to the same summary.

- [ ] **Step 4: Apply damage atomically**

For each resolved result:

- Lock cards in ascending card ID order.
- Reject dead targets.
- Use the rolled expression result as actual damage.
- Clamp `hpCurrent` to zero.
- Set `majorWound=true` when the single damage meets `ceil(hpMax / 2)`.
- If HP reaches zero, set `unconscious=true`; do not create a redundant keep-consciousness CON roll for that target.
- Store raw damage, HP before/after, and major-wound change in resolution outcome/effect.

- [ ] **Step 5: Append and settle major-wound CON**

Use the same timing and idempotency rule as insanity:

- Any true user damage placeholder delays the whole automatic CON round.
- Otherwise append immediately.
- One `1D100` result per newly major-wounded, still-conscious target.
- Use `sourceResultId` and `type=MAJOR_WOUND_CON` to prevent duplicates.
- Target value is the saved CON snapshot.
- Critical/success leaves the target conscious.
- Failure/fumble sets `unconscious=true`.
- No further automatic round is generated.

- [ ] **Step 6: Run damage tests**

Run:

```bash
./mvnw -Dtest=CocDiceOrchestrationServiceTest,CocDiceSummaryFormatterTest,CocDiceRulesTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/me/galchat/domain/dto/KpDiceRequestDTOs.java src/main/java/com/me/galchat/service/ICocDiceOrchestrationService.java src/main/java/com/me/galchat/service/impl/CocDiceOrchestrationService.java src/main/java/com/me/galchat/service/impl/CocDiceSummaryFormatter.java src/main/java/com/me/galchat/constant/DiceRollConstant.java src/test/java/com/me/galchat/service/impl/CocDiceOrchestrationServiceTest.java src/test/java/com/me/galchat/service/impl/CocDiceSummaryFormatterTest.java
git commit -m "feat: resolve damage and major wound checks"
```

### Task 9: Expose KP Tools and Make Tool Recording Atomic

**Files:**
- Create: `src/main/java/com/me/galchat/tool/KpDiceTools.java`
- Modify: `src/main/java/com/me/galchat/groupchat/runtime/trpg/TrpgGroupAgentPolicy.java`
- Modify: `src/main/java/com/me/galchat/groupchat/tool/GroupToolCallStore.java`
- Modify: `src/main/java/com/me/galchat/groupchat/tool/RecordingGroupToolCallingManager.java`
- Modify: `src/main/java/com/me/galchat/config/DeepSeekModelConfiguration.java`
- Test: `src/test/java/com/me/galchat/tool/KpDiceToolsTest.java`
- Modify: `src/test/java/com/me/galchat/groupchat/runtime/trpg/TrpgGroupAgentPolicyTest.java`
- Modify: `src/test/java/com/me/galchat/groupchat/tool/GroupToolCallStoreTest.java`
- Modify: `src/test/java/com/me/galchat/groupchat/tool/RecordingGroupToolCallingManagerTest.java`

**Interfaces:**
- Produces exactly six Spring AI tool names:
  - `requestCheck`
  - `requestOpposedCheck`
  - `requestPushedCheck`
  - `requestSanCheck`
  - `rollSanLoss`
  - `rollDamage`
- `GroupToolCallStore` already implements `DiceFollowUpLocator` from Task 6 and gains direct-result summary binding here.
- `RecordingGroupToolCallingManager` receives a `TransactionTemplate`.
- `KpDiceTools` contains a private `KpExecutionContext(Long conversationId, Long runId, Long replyStepId)` record used only after ToolContext validation.

- [ ] **Step 1: Write failing tool-contract tests**

```java
@Test
void everyKpDiceToolIsReturnDirectAndRejectsNonKpContext() {
    for (String name : DiceRollConstant.KP_STATE_TOOL_NAMES) {
        Method method = Arrays.stream(KpDiceTools.class.getDeclaredMethods())
                .filter(candidate -> candidate.isAnnotationPresent(Tool.class))
                .filter(candidate -> candidate.getAnnotation(Tool.class).name().equals(name))
                .findFirst().orElseThrow();
        assertThat(method.getAnnotation(Tool.class).returnDirect()).isTrue();
    }

    ToolContext characterContext = new ToolContext(Map.of(
            ChatToolContextConstant.ACTOR_TYPE_KEY, GroupChatConstant.ACTOR_CHARACTER,
            ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY, 7L,
            ChatToolContextConstant.USER_WORLD_ID_KEY, 5L));
    KpDiceRequestDTOs.SanCheck request =
            new KpDiceRequestDTOs.SanCheck("目睹尸体", List.of("林恩"));
    assertThatThrownBy(() -> tools.requestSanCheck(request, characterContext))
            .hasMessageContaining("KP");
}
```

- [ ] **Step 2: Write failing storage and transaction tests**

```java
@Test
void directDiceResponseBindsSummaryWhileSavingToolResult() {
    store.saveExecution(41L, responseWithCall("rollDamage"), directResult(
            "{\"summary\":{\"id\":501},\"results\":[],\"semanticResult\":\"生命-4\"}"));
    verify(mapper).updateById(argThat(call ->
            Long.valueOf(501L).equals(call.getDiceRollSummaryId())
                    && call.getToolResult().contains("生命-4")));
}

@Test
void diceToolExecutionAndRecordingUseOneTransaction() {
    when(transactionTemplate.execute(any())).thenAnswer(runCallback());
    manager.executeToolCalls(prompt, responseWithCall("requestCheck"));
    InOrder order = inOrder(delegate, store);
    order.verify(delegate).executeToolCalls(prompt, response);
    order.verify(store).saveExecution(replyStepId, response, result);
}

@Test
void rejectsParallelToolCallsWhenOneIsStateChangingDiceTool() {
    assertThatThrownBy(() -> manager.executeToolCalls(
            prompt, responseWithCalls("requestCheck", "searchInfo")))
            .hasMessageContaining("只能调用一个掷骰工具");
    verifyNoInteractions(delegate);
}
```

- [ ] **Step 3: Run focused tool tests and verify failure**

Run:

```bash
./mvnw -Dtest=KpDiceToolsTest,GroupToolCallStoreTest,RecordingGroupToolCallingManagerTest,TrpgGroupAgentPolicyTest test
```

Expected: tests fail because tools, summary binding parsing and transaction wrapping are absent.

- [ ] **Step 4: Implement the six thin tools**

Each method has one request object plus `ToolContext`, for example:

```java
@Tool(
    name = "rollDamage",
    description = "结算独立伤害，或为最近一次成功的攻击/对抗检定追加伤害轮。",
    returnDirect = true)
public KpDiceToolResult rollDamage(
        @ToolParam(description = "伤害原因、来源模式、来源角色、目标角色与表达式")
        KpDiceRequestDTOs.Damage request,
        ToolContext context) {
    KpExecutionContext kp = requireKpContext(context);
    return orchestration.rollDamage(kp.conversationId(), kp.runId(), request);
}
```

`requireKpContext` requires actor type KP plus non-null conversation ID, run ID, and reply-step ID. Tool parameters contain none of them.

- [ ] **Step 5: Bind direct results during `saveExecution`**

When saving each tool response:

- Always store `toolResult`.
- If `toolName` is in `KP_STATE_TOOL_NAMES`, deserialize `KpDiceToolResult` with the application's Jackson 3 `ObjectMapper`.
- Require `summary.id`.
- Set `diceRollSummaryId` on the same inserted row before `updateById`.

Do not require the tool implementation to know `toolCallId`.

- [ ] **Step 6: Wrap only state-changing dice executions**

Inspect the assistant tool calls before delegate execution:

- No dice tool: keep current delegate-then-store behavior.
- Exactly one call and it is a dice tool: execute delegate and `saveExecution` inside one `TransactionTemplate.execute`.
- Dice plus another call, or multiple dice calls: reject before delegate execution.

Update both group model bean constructions in `DeepSeekModelConfiguration` to pass the transaction template.

- [ ] **Step 7: Give tools only to the KP branch**

`TrpgGroupAgentPolicy.prepare` returns `List.of(kpDiceTools)` only when `action.actorType()==kp`; investigator agents continue to receive no KP dice tools.

- [ ] **Step 8: Run tool integration tests**

Run:

```bash
./mvnw -Dtest=KpDiceToolsTest,GroupToolCallStoreTest,RecordingGroupToolCallingManagerTest,TrpgGroupAgentPolicyTest test
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/me/galchat/tool/KpDiceTools.java src/main/java/com/me/galchat/groupchat/runtime/trpg/TrpgGroupAgentPolicy.java src/main/java/com/me/galchat/groupchat/tool/GroupToolCallStore.java src/main/java/com/me/galchat/groupchat/tool/RecordingGroupToolCallingManager.java src/main/java/com/me/galchat/config/DeepSeekModelConfiguration.java src/test/java/com/me/galchat/tool/KpDiceToolsTest.java src/test/java/com/me/galchat/groupchat/runtime/trpg/TrpgGroupAgentPolicyTest.java src/test/java/com/me/galchat/groupchat/tool/GroupToolCallStoreTest.java src/test/java/com/me/galchat/groupchat/tool/RecordingGroupToolCallingManagerTest.java
git commit -m "feat: expose atomic KP dice tools"
```

### Task 10: Convert `returnDirect` into Dice Events and Reloadable History

**Files:**
- Modify: `src/main/java/com/me/galchat/constant/GroupChatConstant.java`
- Modify: `src/main/java/com/me/galchat/domain/vo/GroupChatEvent.java`
- Modify: `src/main/java/com/me/galchat/domain/vo/GroupChatMessageVO.java`
- Modify: `src/main/java/com/me/galchat/service/impl/GroupChatService.java`
- Modify: `src/main/java/com/me/galchat/groupchat/tool/GroupToolCallStore.java`
- Modify: `src/main/java/com/me/galchat/service/impl/GroupContextAssembler.java`
- Modify: `src/test/java/com/me/galchat/service/impl/GroupChatServiceTest.java`
- Modify: `src/test/java/com/me/galchat/service/impl/GroupContextAssemblerTest.java`
- Modify: `src/test/java/com/me/galchat/groupchat/tool/GroupToolCallStoreTest.java`

**Interfaces:**
- Adds constants:

```java
MESSAGE_DICE_ROLL = "dice_roll";
EVENT_DICE_ROLL_CREATED = "dice_roll.created";
```

- Adds `KpDiceToolResult diceRoll` to `GroupChatEvent`.
- Adds `Long diceRollSummaryId` to `GroupChatMessageVO`.
- Adds `Map<Long, Long> diceSummaryIdsByReplyStepIds(Collection<Long> replyStepIds)` to `GroupToolCallStore`.

- [ ] **Step 1: Write a failing direct-response stream test**

Construct a `Generation` whose metadata contains:

```java
ChatGenerationMetadata.builder()
        .finishReason(ToolExecutionResult.FINISH_REASON)
        .metadata(ToolExecutionResult.METADATA_TOOL_NAME, "requestCheck")
        .metadata(ToolExecutionResult.METADATA_TOOL_ID, "call-1")
        .build()
```

Use assistant text containing serialized `KpDiceToolResult`, then assert:

```java
assertThat(events).extracting(GroupChatEvent::getEventType)
        .contains(GroupChatConstant.EVENT_DICE_ROLL_CREATED)
        .doesNotContain(GroupChatConstant.EVENT_MESSAGE_DELTA);
assertThat(events).filteredOn(e ->
        GroupChatConstant.EVENT_DICE_ROLL_CREATED.equals(e.getEventType()))
        .singleElement()
        .extracting(e -> e.getDiceRoll().summary().getId())
        .isEqualTo(501L);
verify(messageMapper).updateById(argThat(message ->
        GroupChatConstant.MESSAGE_DICE_ROLL.equals(message.getMessageKind())
                && message.getContent() == null
                && GroupChatConstant.STATUS_COMPLETED.equals(message.getStatus())));
```

- [ ] **Step 2: Write failing history reload and context tests**

```java
@Test
void historyReturnsSummaryIdForDiceMessagesWithoutStoringJsonContent() {
    when(store.diceSummaryIdsByReplyStepIds(Set.of(41L))).thenReturn(Map.of(41L, 501L));
    GroupChatMessageVO message = service.listHistory(7L, null, 50).getFirst();
    assertThat(message.getMessageKind()).isEqualTo(GroupChatConstant.MESSAGE_DICE_ROLL);
    assertThat(message.getContent()).isNull();
    assertThat(message.getDiceRollSummaryId()).isEqualTo(501L);
}

@Test
void contextUsesSyntheticDiceHistoryAndSkipsNullDiceMessageBody() {
    List<Message> context = assembler.assembleContextFrom(
            conversation, new GroupActorRef(GroupChatConstant.ACTOR_KP, null), 1L);
    assertThat(context).extracting(Message::getText)
            .contains("<dice-roll summary-id=\"501\" />")
            .doesNotContainNull();
}
```

- [ ] **Step 3: Run direct-response tests and verify failure**

Run:

```bash
./mvnw -Dtest=GroupChatServiceTest,GroupContextAssemblerTest,GroupToolCallStoreTest test
```

Expected: tests fail because direct output is still emitted/persisted as dialogue text.

- [ ] **Step 4: Detect Spring AI direct generations**

In `GroupChatService.toEvents`:

- Check `generation.getMetadata().getFinishReason()` against `ToolExecutionResult.FINISH_REASON`.
- Check tool name against `KP_STATE_TOOL_NAMES`.
- Deserialize `output.getText()` into one `KpDiceToolResult`.
- Store it in the generation accumulator.
- Emit one `dice_roll.created` with the typed payload.
- Do not append the JSON to content and do not emit `message.delta`.

Reject multiple direct dice payloads for one reply step.

- [ ] **Step 5: Persist a dice message and finish the step**

On normal finalization:

```java
if (accumulator.diceRoll != null) {
    message.setMessageKind(GroupChatConstant.MESSAGE_DICE_ROLL)
            .setContent(null);
} else {
    message.setMessageKind(GroupChatConstant.MESSAGE_DIALOGUE)
            .setContent(accumulator.content.toString());
}
```

Keep the normal `message.completed` and `turn.completed` lifecycle events so the client stream terminates exactly as before.

- [ ] **Step 6: Make history reloadable without another message-table column**

Batch map dice messages' `replyStepId` to `group_chat_tool_call.dice_roll_summary_id`, then populate `GroupChatMessageVO.diceRollSummaryId`. Do not store JSON or summary ID in `group_chat_message.content`.

`GroupContextAssembler` first inserts public synthetic `<dice-roll>` history from `GroupToolHistoryAssembler`, then skips the body of `message_kind=dice_roll`.

- [ ] **Step 7: Run direct-response and history tests**

Run:

```bash
./mvnw -Dtest=GroupChatServiceTest,GroupContextAssemblerTest,GroupToolCallStoreTest,GroupToolHistoryAssemblerTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/me/galchat/constant/GroupChatConstant.java src/main/java/com/me/galchat/domain/vo/GroupChatEvent.java src/main/java/com/me/galchat/domain/vo/GroupChatMessageVO.java src/main/java/com/me/galchat/service/impl/GroupChatService.java src/main/java/com/me/galchat/groupchat/tool/GroupToolCallStore.java src/main/java/com/me/galchat/service/impl/GroupContextAssembler.java src/test/java/com/me/galchat/service/impl/GroupChatServiceTest.java src/test/java/com/me/galchat/service/impl/GroupContextAssemblerTest.java src/test/java/com/me/galchat/groupchat/tool/GroupToolCallStoreTest.java
git commit -m "feat: stream direct dice events"
```

### Task 11: Complete API, Regression, and Transaction Verification

**Files:**
- Modify: `src/test/java/com/me/galchat/service/impl/DiceRollServiceImplTest.java`
- Modify: `src/test/java/com/me/galchat/service/impl/CocDiceOrchestrationServiceTest.java`

**Interfaces:**
- Verifies the existing endpoints:
  - `GET /dice-rolls/{id}`
  - `GET /dice-rolls/{id}/results`
  - `POST /dice-roll-results/{id}/roll`
- Verifies the roll endpoint returns `DiceRollProgressVO`.

- [ ] **Step 1: Add end-to-end service regression cases**

Cover these named cases in `CocDiceOrchestrationServiceTest`:

```text
queryUsesSummaryConversationAuthorization
agentResultIsVisibleWhilePlayerResultIsPending
retryDoesNotRerollOrReapplySanLoss
completedRoundOnlyIsIncludedInTotalResult
sanConstantZeroDoesNotCreateClickablePlaceholder
madnessRoundIsCreatedOnceUnderSummaryLock
majorWoundConRoundIsCreatedOnceUnderSummaryLock
followUpLookupCannotCrossConversation
playerCannotRollAgentResult
oldRoundPendingResultCannotBeRolledAfterRoundAdvance
```

Each test must assert the exact mapper/service interaction that prevents the unwanted write, not only the returned exception.

- [ ] **Step 2: Run all dice, card, group-tool, and group-runtime tests**

Run:

```bash
./mvnw -Dtest='DiceUtilsTest,DiceRoll*Test,CocDice*Test,InsanityCatalogTest,CharacterCard*Test,KpDiceToolsTest,GroupReplyPlanServiceTest,GroupTool*Test,RecordingGroupToolCallingManagerTest,GroupContextAssemblerTest,GroupChatServiceTest,TrpgGroupAgentPolicyTest' test
```

Expected: PASS with no failures or errors.

- [ ] **Step 3: Run the full project test suite**

Run:

```bash
./mvnw test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Review schema and catalog invariants**

Run:

```bash
grep -nE 'resolution_data|resolved_at|actor_id BIGINT' docs/sql/V20260720__dice_roll.sql docs/sql/V20260711__group_chat.sql docs/sql/V20260727__kp_dice_tools.sql src/test/java/com/me/galchat/init/console.sql
grep -nE 'PHOBIAS|MANIAS|SUMMARY_SYMPTOMS' src/main/java/com/me/galchat/constant/InsanityCatalog.java
```

Verify:

- Fresh-install and upgrade SQL agree.
- `group_reply_plan_item.actor_id` is nullable.
- No insanity database table or seed file was introduced.
- Catalog tests prove 10/100/100 entry counts.

- [ ] **Step 5: Review repository diff**

Run:

```bash
git status --short
git diff --check
git diff --stat
```

Expected: only intended KP/dice files are changed; pre-existing `.DS_Store` and `__pycache__` files remain untouched.

- [ ] **Step 6: Commit final regression adjustments**

```bash
git add src/test/java/com/me/galchat/service/impl/DiceRollServiceImplTest.java src/test/java/com/me/galchat/service/impl/CocDiceOrchestrationServiceTest.java
git commit -m "test: verify KP dice workflows"
```

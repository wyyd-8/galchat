package com.me.galchat.service.impl.group;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.*;
import com.me.galchat.groupchat.dice.DiceRollMessageCodec;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.mapper.*;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.service.impl.trpg.*;
import com.me.galchat.support.MybatisPlusTestSupport;
import org.junit.jupiter.api.*;
import org.mockito.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrpgSuspensionRollbackTest {
    @Mock GroupTurnCheckpointMapper checkpoints;
    @Mock GroupChatMessageMapper messages;
    @Mock GroupChatToolCallMapper calls;
    @Mock DiceRollSummaryMapper dice;
    @Mock DiceRollMessageCodec codec;
    @Mock ICharacterCardService cards;
    @Mock GroupChatFavorRollbackService favor;
    @Mock TrpgEquipmentService equipment;
    @Mock TrpgMaterialRecoveryService materialRecovery;
    @Spy ObjectMapper json = JsonMapper.builder().build();
    final GroupConversationService conversations = mock(GroupConversationService.class);
    final TrpgParticipantService participants = mock(TrpgParticipantService.class);
    @Spy GroupChatReplyStepMapper steps = mock(GroupChatReplyStepMapper.class);
    @Spy GroupChatTurnMapper turns = mock(GroupChatTurnMapper.class);
    final GroupReplyPlanMapper plans = mock(GroupReplyPlanMapper.class);
    final TrpgInvestigatorSuspensionMapper mapper = mock(TrpgInvestigatorSuspensionMapper.class);
    final org.springframework.data.redis.core.StringRedisTemplate redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
    final TrpgSceneProgressStore progress = spy(new TrpgSceneProgressStore(redis));
    final GroupReplyPlanItemMapper items = mock(GroupReplyPlanItemMapper.class);
    final Map<Long, GroupReplyPlanItem> sceneItems = new LinkedHashMap<>();
    final Map<Long, GroupReplyPlan> scenePlans = new LinkedHashMap<>();
    final Set<String> redisKeys = new HashSet<>();
    @Spy TrpgSceneLifecycleService lifecycle = new TrpgSceneLifecycleService(conversations, steps, turns, plans,
            items, new GroupTurnRecoveryService(mock(com.me.galchat.mapper.GroupTurnCheckpointMapper.class), turns, steps, mock(GroupChatMessageMapper.class)), progress,
            mock(TrpgSceneSummaryService.class), mock(GroupReplyPlanService.class),
            mock(TrpgChildScenePlanService.class), mock(TrpgTemporaryInsanityService.class));
    @Spy TrpgInvestigatorSuspensionService suspension = new TrpgInvestigatorSuspensionService(
            conversations, participants, steps, turns, plans, items,
            mock(GroupConversationMapper.class), mapper, progress);
    @Spy TrpgSceneFinishRecoveryService finishRecovery = new TrpgSceneFinishRecoveryService(plans, turns, steps, progress);
    @Spy TrpgChildSceneCommandService childCommands = new TrpgChildSceneCommandService(conversations, steps, turns,
            plans, items, mock(TrpgChildScenePlanService.class), progress, mock(GroupChatToolCallMapper.class), JsonMapper.builder().build());
    @InjectMocks GroupTurnCheckpointService checkpointService;
    AutoCloseable mocks;
    final Map<Long, TrpgInvestigatorSuspension> rows = new LinkedHashMap<>();
    final Set<Long> ready = new HashSet<>(Set.of(101L));
    final List<GroupChatToolCall> records = new ArrayList<>();
    final GroupChatTurn turn = new GroupChatTurn().setId(61L).setConversationId(7L)
            .setPlanId(31L).setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE);
    final GroupChatReplyStep step = new GroupChatReplyStep().setId(51L).setTurnId(61L)
            .setSpeakerType(GroupChatConstant.ACTOR_KP);
    final GroupTurnCheckpoint checkpoint = new GroupTurnCheckpoint().setTurnId(61L)
            .setReplyStepId(51L).setCheckpointType("STEP_START").setMessageId(0L).setToolCallId(0L);
    long nextId = 80;

    @BeforeEach void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        MybatisPlusTestSupport.initialize(GroupChatReplyStep.class, GroupChatToolCall.class,
                TrpgInvestigatorSuspension.class, GroupReplyPlan.class, GroupReplyPlanItem.class);
        var conversation = new GroupConversation().setId(7L).setActiveReplyPlanId(31L);
        when(conversations.requireActive(7L)).thenReturn(conversation);
        when(steps.selectById(51L)).thenReturn(step);
        when(turns.selectById(61L)).thenReturn(turn);
        scenePlans.put(31L, new GroupReplyPlan().setId(31L).setConversationId(7L).setContextId(21L).setSource("SCENE"));
        when(plans.selectById(anyLong())).thenAnswer(i -> scenePlans.get(i.getArgument(0)));
        when(plans.insert(any(GroupReplyPlan.class))).thenAnswer(i -> {
            GroupReplyPlan p = i.getArgument(0); p.setId(++nextId); scenePlans.put(p.getId(), p); return 1;
        });
        when(plans.updateById(any(GroupReplyPlan.class))).thenReturn(1);
        when(plans.deleteById(anyLong())).thenAnswer(i -> scenePlans.remove(i.getArgument(0)) == null ? 0 : 1);
        when(plans.update(isNull(), any())).thenAnswer(i -> applyUpdate(scenePlans, i.getArgument(1)));
        when(items.selectList(any())).thenAnswer(i -> new ArrayList<>(sceneItems.values()));
        when(items.selectById(anyLong())).thenAnswer(i -> sceneItems.get(i.getArgument(0)));
        when(items.insert(any(GroupReplyPlanItem.class))).thenAnswer(i -> {
            GroupReplyPlanItem item = i.getArgument(0); item.setId(++nextId); sceneItems.put(item.getId(), item); return 1;
        });
        when(items.updateById(any(GroupReplyPlanItem.class))).thenReturn(1);
        when(items.update(isNull(), any())).thenAnswer(i -> applyUpdate(sceneItems, i.getArgument(1)));
        when(items.deleteById(anyLong())).thenAnswer(i -> sceneItems.remove(i.getArgument(0)) == null ? 0 : 1);
        when(items.delete(any())).thenAnswer(i -> {
            LambdaQueryWrapper<GroupReplyPlanItem> w = i.getArgument(0); w.getSqlSegment();
            int before = sceneItems.size();
            sceneItems.values().removeIf(item -> w.getParamNameValuePairs().containsValue(item.getPlanId()));
            return before - sceneItems.size();
        });
        when(mapper.update(isNull(), any())).thenAnswer(i -> applyUpdate(rows, i.getArgument(1)));
        when(mapper.updateById(any(TrpgInvestigatorSuspension.class))).thenReturn(1);
        when(mapper.deleteById(anyLong())).thenAnswer(i -> rows.remove(i.getArgument(0)) == null ? 0 : 1);
        var values = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(i -> { redisKeys.add(i.getArgument(0)); return null; }).when(values).set(anyString(), anyString());
        when(redis.hasKey(anyString())).thenAnswer(i -> redisKeys.contains(i.getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(i -> redisKeys.remove(i.getArgument(0)));
        doReturn(Set.of()).when(progress).readyCharacterIds(anyLong(), anyLong());
        org.springframework.test.util.ReflectionTestUtils.setField(lifecycle, "suspensionService", suspension);
        when(participants.listInvestigators(conversation)).thenReturn(List.of(
                participant(101L, "林恩"), participant(102L, "陈默"), participant(103L, "亨利")));
        when(mapper.selectList(any())).thenAnswer(i -> new ArrayList<>(rows.values()));
        when(mapper.insert(any(TrpgInvestigatorSuspension.class))).thenAnswer(i -> {
            TrpgInvestigatorSuspension row = i.getArgument(0);
            row.setId(++nextId); rows.put(row.getId(), row); return 1;
        });
        when(mapper.selectById(anyLong())).thenAnswer(i -> rows.get(i.getArgument(0)));
        when(mapper.delete(any())).thenAnswer(i -> {
            LambdaQueryWrapper<TrpgInvestigatorSuspension> w = i.getArgument(0);
            assertThat(w.getSqlSegment()).contains("id =", "conversation_id =", "subject_character_id =", "state =");
            var params = w.getParamNameValuePairs().values();
            var matching = rows.values().stream().filter(r -> params.contains(r.getId())
                    && params.contains(r.getConversationId()) && params.contains(r.getSubjectCharacterId())
                    && params.contains(r.getState())).map(TrpgInvestigatorSuspension::getId).toList();
            matching.forEach(rows::remove); return matching.size();
        });
        doAnswer(i -> Set.copyOf(ready)).when(progress).readyCharacterIds(7L, 31L);
        org.springframework.data.redis.core.SetOperations<String,String> sets = mock(org.springframework.data.redis.core.SetOperations.class);
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.remove(anyString(), any())).thenAnswer(i ->
                ready.remove(Long.valueOf(i.<String>getArgument(1).substring("character-card:".length()))) ? 1L : 0L);
        doAnswer(i -> {ready.add(i.getArgument(2)); return null;})
                .when(progress).markReady(eq(7L), eq(31L), anyLong());
        when(checkpoints.selectById(7L)).thenReturn(checkpoint);
        when(calls.selectList(any())).thenAnswer(i -> {
            LambdaQueryWrapper<GroupChatToolCall> w = i.getArgument(0);
            w.getSqlSegment();
            assertThat(w.getParamNameValuePairs()).containsValue(51L).containsValue(checkpoint.getToolCallId());
            return records.reversed().stream().filter(r -> r.getId() > checkpoint.getToolCallId())
                    .filter(r -> w.getParamNameValuePairs().containsValue(r.getToolName())).toList();
        });
        when(calls.deleteAfterCheckpoint(eq(51L), anyLong())).thenAnswer(i -> {
            records.removeIf(r -> r.getId() > i.<Long>getArgument(1)); return 1;
        });
    }
    // Apply the explicit SQL SET values so null restoration is exercised as in MyBatis.
    static int applyUpdate(Map<Long, ?> data, com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?> w) {
        String condition = w.getSqlSegment();
        var id = java.util.regex.Pattern.compile("(?:^|[ (])id = #\\{ew.paramNameValuePairs.(\\w+)\\}").matcher(condition);
        if (!id.find()) throw new AssertionError(condition);
        Object row = data.get(w.getParamNameValuePairs().get(id.group(1)));
        if (row == null) return 0;
        var bean = new org.springframework.beans.BeanWrapperImpl(row);
        var set = java.util.regex.Pattern.compile("(\\w+)=#\\{ew.paramNameValuePairs.(\\w+)\\}").matcher(w.getSqlSet());
        while (set.find()) {
            String[] parts = set.group(1).split("_"); String property = parts[0];
            for (int j = 1; j < parts.length; j++) property += Character.toUpperCase(parts[j].charAt(0)) + parts[j].substring(1);
            bean.setPropertyValue(property, w.getParamNameValuePairs().get(set.group(2)));
        }
        return 1;
    }
    void record(String tool, Object result) {
        records.add(new GroupChatToolCall().setId((long) records.size() + 1).setToolName(tool)
                .setToolResult(json.writeValueAsString(result)));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void retryRestoresWaitingInvestigatorAndOriginalReadyMarker(boolean readyBefore) {
        if (!readyBefore) ready.clear();
        var originalTime = java.time.LocalDateTime.of(2026, 9, 1, 12, 0);
        var item = new GroupReplyPlanItem().setId(41L).setPlanId(31L)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER).setSubjectCharacterId(101L)
                .setSubjectCharacterName("林恩").setParticipantStatus(GroupChatConstant.PARTICIPANT_WAITING)
                .setUpdatedAt(originalTime);
        sceneItems.put(41L, item);
        var commands = new TrpgChildSceneCommandService(conversations, steps, turns, plans, items,
                mock(TrpgChildScenePlanService.class), progress, calls, json);
        record("resumeWaitingInvestigators", commands.resumeWaitingInvestigators(7L, 51L, List.of("林恩")));
        assertThat(item.getParticipantStatus()).isEqualTo(GroupChatConstant.PARTICIPANT_ACTIVE);
        assertThat(ready).doesNotContain(101L);

        checkpointService.restore(turn, step);

        assertThat(item.getParticipantStatus()).isEqualTo(GroupChatConstant.PARTICIPANT_WAITING);
        assertThat(item.getUpdatedAt()).isEqualTo(originalTime);
        assertThat(ready.contains(101L)).isEqualTo(readyBefore);
        assertThat(records).isEmpty();
    }

    @Test
    void pauseBoundaryKeepsCommittedWaitingResume() {
        sceneItems.put(41L, new GroupReplyPlanItem().setId(41L).setPlanId(31L)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER).setSubjectCharacterId(101L)
                .setSubjectCharacterName("林恩").setParticipantStatus(GroupChatConstant.PARTICIPANT_WAITING));
        record("resumeWaitingInvestigators", childCommands.resumeWaitingInvestigators(7L, 51L, List.of("林恩")));
        checkpoint.setCheckpointType("PAUSED").setToolCallId(1L);
        checkpointService.restore(turn, step);
        assertThat(sceneItems.get(41L).getParticipantStatus()).isEqualTo(GroupChatConstant.PARTICIPANT_ACTIVE);
        assertThat(ready).doesNotContain(101L);
        assertThat(records).hasSize(1);
    }

    @Test
    void legacyWaitingResumeWithoutUndoStopsRetryAndRetainsRecord() {
        record("resumeWaitingInvestigators", "林恩已结束等待，将从下一轮开始正常参与行动。");
        assertThatThrownBy(() -> checkpointService.restore(turn, step))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("缺少撤销数据");
        assertThat(records).hasSize(1);
    }

    @Test
    void waitingRollbackRejectsAnItemMovedToAnotherScene() {
        var item = new GroupReplyPlanItem().setId(41L).setPlanId(31L)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER).setSubjectCharacterId(101L)
                .setSubjectCharacterName("林恩").setParticipantStatus(GroupChatConstant.PARTICIPANT_WAITING);
        sceneItems.put(41L, item);
        record("resumeWaitingInvestigators", childCommands.resumeWaitingInvestigators(7L, 51L, List.of("林恩")));
        item.setPlanId(99L);
        assertThatThrownBy(() -> checkpointService.restore(turn, step))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("状态已变化");
        assertThat(item.getParticipantStatus()).isEqualTo(GroupChatConstant.PARTICIPANT_ACTIVE);
        assertThat(records).hasSize(1);
    }

    void seedSuspension() {
        rows.put(71L, new TrpgInvestigatorSuspension().setId(71L).setConversationId(7L)
                .setSubjectCharacterId(101L).setState("SUSPENDED").setSuspensionContext("留院").setOriginContextId(21L));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CURRENT_SCENE", "INDEPENDENT_SCENE"})
    void retryUndoesResumeIncludingCreatedPlansAndItems(String mode) {
        seedSuspension();
        record("resumeSuspendedInvestigators", suspension.resumeSuspendedInvestigators(7L, 51L,
                List.of("林恩"), mode, "出院", "医院"));
        checkpointService.restore(turn, step);
        assertThat(rows.get(71L).getState()).isEqualTo("SUSPENDED");
        assertThat(rows.get(71L).getRecoveryPlanId()).isNull();
        assertThat(rows.get(71L).getReentryContext()).isNull();
        assertThat(scenePlans).containsOnlyKeys(31L);
        assertThat(scenePlans.get(31L).getNextPlanId()).isNull();
        assertThat(sceneItems).isEmpty();
        assertThat(records).isEmpty();
        assertThatCode(() -> suspension.resumeSuspendedInvestigators(7L,51L,List.of("林恩"),mode,"出院","医院"))
                .doesNotThrowAnyException();
    }
    @Test void retryRestoresExistingSceneMemberStatus() {
        seedSuspension();
        sceneItems.put(41L, new GroupReplyPlanItem().setId(41L).setPlanId(31L)
                .setSubjectCharacterId(101L).setParticipantStatus("WAITING"));
        record("resumeSuspendedInvestigators", suspension.resumeSuspendedInvestigators(7L,51L,
                List.of("林恩"),"CURRENT_SCENE","出院",null));
        checkpointService.restore(turn,step);
        assertThat(sceneItems.get(41L).getParticipantStatus()).isEqualTo("WAITING");
    }
    @Test void retryUndoesSuspendThenResumeInReverseOrder() {
        suspend("林恩");
        record("resumeSuspendedInvestigators", suspension.resumeSuspendedInvestigators(7L,51L,
                List.of("林恩"),"CURRENT_SCENE","出院",null));
        assertThatCode(() -> checkpointService.restore(turn,step)).doesNotThrowAnyException();
        assertThat(rows).isEmpty();
        assertThat(ready).containsExactly(101L);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void retryRestoresPreviousFinishFlag(boolean alreadyFinished) {
        turn.setPlanContextId(21L);
        if (alreadyFinished) progress.requestFinish(7L,31L);
        record("finishSceneExploration", new com.me.galchat.tool.KpSceneTools(lifecycle).finishSceneExploration(
                new org.springframework.ai.chat.model.ToolContext(Map.of(
                    com.me.galchat.constant.ChatToolContextConstant.ACTOR_TYPE_KEY, GroupChatConstant.ACTOR_KP,
                    com.me.galchat.constant.ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY, 7L,
                    com.me.galchat.constant.ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 51L))));
        assertThat(progress.isFinishRequested(7L,31L)).isTrue();
        checkpointService.restore(turn,step);
        assertThat(progress.isFinishRequested(7L,31L)).isEqualTo(alreadyFinished);
        assertThat(records).isEmpty();
    }
    @Test void reentryIsConsumedByFirstActionInAnotherScene() {
        seedSuspension();
        suspension.resumeSuspendedInvestigators(7L,51L,List.of("林恩"),"CURRENT_SCENE","出院",null);
        when(steps.selectList(any())).thenReturn(List.of(new GroupChatReplyStep().setSubjectCharacterId(101L)));
        suspension.completeReentriesAfterTurn(new GroupChatTurn().setId(62L).setConversationId(7L)
                .setPlanId(32L).setPlanSource("SCENE"));
        assertThat(rows).isEmpty();
    }
    @Test void closingRecoverySceneBeforeFirstActionClearsPendingReentry() {
        seedSuspension();
        suspension.resumeSuspendedInvestigators(7L,51L,List.of("林恩"),"CURRENT_SCENE","出院",null);
        progress.requestFinish(7L,31L);
        assertThat(lifecycle.finalizeAfterTurn(conversations.requireActive(7L),"SCENE")).isTrue();
        assertThat(rows).isEmpty();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CURRENT_SCENE", "INDEPENDENT_SCENE"})
    void retryKeepsResumeBeforePausedCheckpoint(String mode) {
        seedSuspension();
        record("resumeSuspendedInvestigators", suspension.resumeSuspendedInvestigators(7L,51L,
                List.of("林恩"),mode,"出院","医院"));
        checkpoint.setCheckpointType("PAUSED").setToolCallId(1L);
        checkpointService.restore(turn,step);
        assertThat(rows.get(71L).getState()).isEqualTo(mode.equals("CURRENT_SCENE") ? "REENTRY_PENDING" : "RECOVERY_QUEUED");
        assertThat(records).hasSize(1);
        assertThat(sceneItems).isNotEmpty();
    }
    @Test void queuedRecoveryForAnotherSceneSurvivesActionAndSceneClose() {
        seedSuspension();
        suspension.resumeSuspendedInvestigators(7L,51L,List.of("林恩"),"INDEPENDENT_SCENE","出院","医院");
        when(steps.selectList(any())).thenReturn(List.of(new GroupChatReplyStep().setSubjectCharacterId(101L)));
        suspension.completeReentriesAfterTurn(turn);
        suspension.completeReentriesForScene(7L,31L);
        assertThat(rows.get(71L).getState()).isEqualTo("RECOVERY_QUEUED");
    }
    @Test void changedRecoverySceneStopsRetryWithoutDeletingRecords() {
        seedSuspension();
        record("resumeSuspendedInvestigators", suspension.resumeSuspendedInvestigators(7L,51L,
                List.of("林恩"),"INDEPENDENT_SCENE","出院","医院"));
        scenePlans.get(rows.get(71L).getRecoveryPlanId()).setNextPlanId(999L);
        assertThatThrownBy(() -> checkpointService.restore(turn,step)).hasMessageContaining("后续变化");
        assertThat(records).hasSize(1);
        assertThat(rows.get(71L).getState()).isEqualTo("RECOVERY_QUEUED");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "resumeSuspendedInvestigators,调查员林恩已并入当前场景。",
            "finishSceneExploration,当前场景已请求结算。",
            "endSceneExploration,你的结束探索意向已记录。",
            "endSceneExploration,所有调查员均已结束探索，当前场景将进入结算。"})
    void legacySceneToolSuccessCannotDiscardUnknownEffects(String tool, String result) {
        record(tool,result);
        assertThatThrownBy(() -> checkpointService.restore(turn,step)).hasMessageContaining("撤销数据");
        assertThat(records).hasSize(1);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void finishFlagRollbackPublishesOnlyAfterCommit(boolean rollback) {
        turn.setPlanContextId(21L);
        record("finishSceneExploration", lifecycle.requestKpFinish(7L,51L));
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                    @Override protected Object doGetTransaction() { return new Object(); }
                    @Override protected void doBegin(Object t, org.springframework.transaction.TransactionDefinition d) { }
                    @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s) { }
                    @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus s) { }
                });
        tx.executeWithoutResult(status -> {
            checkpointService.restore(turn,step);
            assertThat(progress.isFinishRequested(7L,31L)).isTrue();
            if (rollback) status.setRollbackOnly();
        });
        assertThat(progress.isFinishRequested(7L,31L)).isEqualTo(rollback);
    }
    @Test void retryRestoresOnlyStepsCancelledByFinishTool() {
        turn.setPlanContextId(21L);
        var tail = new GroupChatReplyStep().setId(52L).setTurnId(61L).setStatus("pending");
        Map<Long,GroupChatReplyStep> stored = new HashMap<>(Map.of(51L,step,52L,tail));
        when(steps.selectById(52L)).thenReturn(tail);
        when(steps.selectList(any())).thenAnswer(i -> tail.getStatus().equals("pending") ? List.of(tail) : List.of());
        when(steps.update(any(), any())).thenAnswer(i -> {
            GroupChatReplyStep update = i.getArgument(0);
            if (update == null) return applyUpdate(stored,i.getArgument(1));
            tail.setStatus(update.getStatus()).setErrorMessage(update.getErrorMessage()); return 1;
        });
        record("finishSceneExploration", lifecycle.requestKpFinish(7L,51L));
        assertThat(tail.getStatus()).isEqualTo("cancelled");
        checkpointService.restore(turn,step);
        assertThat(tail.getStatus()).isEqualTo("pending");
        assertThat(tail.getErrorMessage()).isNull();
        assertThat(progress.isFinishRequested(7L,31L)).isFalse();
    }
    void prepareInvestigatorFinish(boolean lastInvestigator) {
        turn.setPlanContextId(21L);
        step.setSpeakerType(GroupChatConstant.ACTOR_CHARACTER).setSpeakerId(11L).setSubjectCharacterId(101L);
        ready.clear();
        doAnswer(i -> ready.stream().map(TrpgSceneProgressStore::actorKey)
                .collect(java.util.stream.Collectors.toSet())).when(progress).readyActors(7L,31L);
        sceneItems.put(41L, new GroupReplyPlanItem().setId(41L).setPlanId(31L)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER).setSubjectCharacterId(101L));
        if (!lastInvestigator) sceneItems.put(42L, new GroupReplyPlanItem().setId(42L).setPlanId(31L)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER).setSubjectCharacterId(102L));
    }
    void endInvestigatorExploration() {
        record("endSceneExploration", new com.me.galchat.tool.InvestigatorSceneTools(lifecycle)
                .endSceneExploration(new org.springframework.ai.chat.model.ToolContext(Map.of(
                        com.me.galchat.constant.ChatToolContextConstant.ACTOR_TYPE_KEY, GroupChatConstant.ACTOR_CHARACTER,
                        com.me.galchat.constant.ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY, 7L,
                        com.me.galchat.constant.ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 51L,
                        com.me.galchat.constant.ChatToolContextConstant.ACTOR_ID_KEY, 11L))));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void investigatorFinishIsUndoneByRetry(boolean lastInvestigator) {
        prepareInvestigatorFinish(lastInvestigator);
        endInvestigatorExploration();
        assertThat(ready).containsExactly(101L);
        assertThat(progress.isFinishRequested(7L,31L)).isEqualTo(lastInvestigator);
        checkpointService.restore(turn,step);
        assertThat(records).isEmpty();
        assertThat(ready).isEmpty();
        assertThat(progress.isFinishRequested(7L,31L)).isFalse();
    }
    @Test void investigatorRetryKeepsExistingReadyAndFinishFlags() {
        prepareInvestigatorFinish(true);
        ready.add(101L);
        progress.requestFinish(7L,31L);
        endInvestigatorExploration();
        checkpointService.restore(turn,step);
        assertThat(ready).containsExactly(101L);
        assertThat(progress.isFinishRequested(7L,31L)).isTrue();
    }
    @Test void investigatorFinishBeforePausedCheckpointIsKept() {
        prepareInvestigatorFinish(true);
        endInvestigatorExploration();
        checkpoint.setCheckpointType("PAUSED").setToolCallId(1L);
        checkpointService.restore(turn,step);
        assertThat(records).hasSize(1);
        assertThat(ready).containsExactly(101L);
        assertThat(progress.isFinishRequested(7L,31L)).isTrue();
    }
    @Test void investigatorRetryRestoresCancelledTail() {
        prepareInvestigatorFinish(true);
        var tail = new GroupChatReplyStep().setId(52L).setTurnId(61L)
                .setSpeakerType(GroupChatConstant.ACTOR_CHARACTER).setStatus("pending");
        Map<Long,GroupChatReplyStep> stored = new HashMap<>(Map.of(51L,step,52L,tail));
        when(steps.selectById(52L)).thenReturn(tail);
        when(steps.selectList(any())).thenAnswer(i -> tail.getStatus().equals("pending") ? List.of(tail) : List.of());
        when(steps.update(any(), any())).thenAnswer(i -> {
            GroupChatReplyStep update = i.getArgument(0);
            if (update == null) return applyUpdate(stored,i.getArgument(1));
            tail.setStatus(update.getStatus()).setErrorMessage(update.getErrorMessage()); return 1;
        });
        endInvestigatorExploration();
        assertThat(tail.getStatus()).isEqualTo("cancelled");
        checkpointService.restore(turn,step);
        assertThat(tail.getStatus()).isEqualTo("pending");
        assertThat(tail.getErrorMessage()).isNull();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void investigatorRetryPublishesBothFlagsOnlyAfterCommit(boolean rollback) {
        prepareInvestigatorFinish(true);
        endInvestigatorExploration();
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                    @Override protected Object doGetTransaction() { return new Object(); }
                    @Override protected void doBegin(Object t, org.springframework.transaction.TransactionDefinition d) { }
                    @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s) { }
                    @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus s) { }
                });
        tx.executeWithoutResult(status -> {
            checkpointService.restore(turn,step);
            assertThat(ready).containsExactly(101L);
            assertThat(progress.isFinishRequested(7L,31L)).isTrue();
            if (rollback) status.setRollbackOnly();
        });
        assertThat(ready.contains(101L)).isEqualTo(rollback);
        assertThat(progress.isFinishRequested(7L,31L)).isEqualTo(rollback);
    }
    @Test void runCloseFailureRollsBackReentryAndPlanDeletionTogether() {
        seedSuspension();
        suspension.resumeSuspendedInvestigators(7L,51L,List.of("林恩"),"CURRENT_SCENE","出院",null);
        var planService = mock(GroupReplyPlanService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(lifecycle,"replyPlanService",planService);
        doAnswer(i -> {
            assertThat(rows).isEmpty();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            scenePlans.clear();
            throw new IllegalStateException("closing failed");
        }).when(planService).clearConversationPlans(any());
        var txManager = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            Map<Long,TrpgInvestigatorSuspension> savedRows;
            Map<Long,GroupReplyPlan> savedPlans;
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object t, org.springframework.transaction.TransactionDefinition d) {
                savedRows = new LinkedHashMap<>(rows); savedPlans = new LinkedHashMap<>(scenePlans);
            }
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s) { }
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus s) {
                rows.clear(); rows.putAll(savedRows); scenePlans.clear(); scenePlans.putAll(savedPlans);
            }
        };
        var proxy = new org.springframework.aop.framework.ProxyFactory(lifecycle);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(txManager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var transactional = (TrpgSceneLifecycleService) proxy.getProxy();
        assertThatThrownBy(() -> transactional.finishRunSceneUnderLock(conversations.requireActive(7L)))
                .hasMessage("closing failed");
        assertThat(scenePlans).containsOnlyKeys(31L);
        assertThat(rows).containsOnlyKeys(71L);
        assertThat(rows.get(71L).getState()).isEqualTo("REENTRY_PENDING");
    }
    @Test void finishingRunClearsCurrentAndQueuedReentriesButKeepsSuspendedStories() {
        seedSuspension();
        rows.put(72L,new TrpgInvestigatorSuspension().setId(72L).setConversationId(7L)
                .setSubjectCharacterId(102L).setState("SUSPENDED").setSuspensionContext("留院").setOriginContextId(21L));
        rows.put(73L,new TrpgInvestigatorSuspension().setId(73L).setConversationId(7L)
                .setSubjectCharacterId(103L).setState("SUSPENDED").setSuspensionContext("失联").setOriginContextId(21L));
        suspension.resumeSuspendedInvestigators(7L,51L,List.of("林恩"),"CURRENT_SCENE","出院",null);
        suspension.resumeSuspendedInvestigators(7L,51L,List.of("陈默"),"INDEPENDENT_SCENE","出院","医院");
        lifecycle.finishRunSceneUnderLock(conversations.requireActive(7L));
        assertThat(rows).containsOnlyKeys(73L);
        assertThat(rows.get(73L).getState()).isEqualTo("SUSPENDED");
        assertThat(rows.get(73L).getSuspensionContext()).isEqualTo("失联");
    }
    @AfterEach void close() throws Exception { mocks.close(); }
    TrpgParticipantService.Participant participant(Long id, String name) {
        return new TrpgParticipantService.Participant(new GroupActorRef("character", id), id, name, name);
    }
    void suspend(String name) {
        var result = suspension.suspendInvestigators(7L, 51L, List.of(name), "暂留医院");
        records.add(new GroupChatToolCall().setId((long) records.size() + 1)
                .setToolName("suspendInvestigators").setToolResult(json.writeValueAsString(result)));
    }
    @Test void retryRestoresSuspensionAndReadyStateAndAllowsSuspendingAgain() {
        suspend("林恩");
        suspend("陈默");
        assertThat(rows).hasSize(2);
        assertThat(ready).isEmpty();
        checkpointService.restore(turn, step);
        assertThat(rows).isEmpty();
        assertThat(records).isEmpty();
        assertThat(ready).containsExactly(101L);
        suspend("林恩");
        assertThat(rows).hasSize(1);
    }
    @Test void retryKeepsSuspensionBeforePausedCheckpoint() {
        suspend("林恩");
        checkpoint.setCheckpointType("PAUSED").setToolCallId(1L);
        suspend("陈默");
        checkpointService.restore(turn, step);
        assertThat(rows.values()).extracting(TrpgInvestigatorSuspension::getSubjectCharacterId)
                .containsExactly(101L);
        assertThat(records).hasSize(1);
        assertThat(ready).isEmpty();
    }
    @Test void changedSuspensionAbortsRetryWithoutDeletingItsRecord() {
        suspend("林恩");
        rows.values().iterator().next().setState(TrpgInvestigatorSuspension.STATE_REENTRY_PENDING);
        assertThatThrownBy(() -> checkpointService.restore(turn, step)).isInstanceOf(IllegalStateException.class);
        assertThat(records).hasSize(1);
        assertThat(rows).hasSize(1);
        verify(messages, never()).deleteAfterCheckpoint(anyLong(), anyLong());
    }
    @Test void legacySuccessWithoutUndoDataIsNotSilentlyDiscarded() {
        records.add(new GroupChatToolCall().setId(1L).setToolName("suspendInvestigators")
                .setToolResult("已悬置调查员林恩的剧情线。"));
        assertThatThrownBy(() -> checkpointService.restore(turn, step)).hasMessageContaining("撤销数据");
        assertThat(records).hasSize(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void readyFlagIsRestoredOnlyAfterRetryTransactionCommits(boolean rollback) {
        suspend("林恩");
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                    @Override protected Object doGetTransaction() { return new Object(); }
                    @Override protected void doBegin(Object transaction,
                            org.springframework.transaction.TransactionDefinition definition) { }
                    @Override protected void doCommit(
                            org.springframework.transaction.support.DefaultTransactionStatus status) { }
                    @Override protected void doRollback(
                            org.springframework.transaction.support.DefaultTransactionStatus status) { }
                });
        tx.executeWithoutResult(status -> {
            checkpointService.restore(turn, step);
            assertThat(ready).isEmpty();
            if (rollback) status.setRollbackOnly();
        });
        if (rollback) assertThat(ready).isEmpty();
        else assertThat(ready).containsExactly(101L);
    }

    @Test void failedToolCallDoesNotTryToUndoAnySuspension() {
        records.add(new GroupChatToolCall().setId(1L).setToolName("suspendInvestigators")
                .setToolResult("调查员剧情已经被悬置：林恩"));
        checkpointService.restore(turn, step);
        assertThat(records).isEmpty();
        assertThat(ready).containsExactly(101L);
        verify(mapper, never()).delete(any());
    }
}

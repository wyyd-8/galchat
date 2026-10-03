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
    final TrpgSceneProgressStore progress = mock(TrpgSceneProgressStore.class);
    @Spy TrpgInvestigatorSuspensionService suspension = new TrpgInvestigatorSuspensionService(
            conversations, participants, steps, turns, plans, mock(GroupReplyPlanItemMapper.class),
            mock(GroupConversationMapper.class), mapper, progress);
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
                TrpgInvestigatorSuspension.class);
        var conversation = new GroupConversation().setId(7L).setActiveReplyPlanId(31L);
        when(conversations.requireActive(7L)).thenReturn(conversation);
        when(steps.selectById(51L)).thenReturn(step);
        when(turns.selectById(61L)).thenReturn(turn);
        when(plans.selectById(31L)).thenReturn(new GroupReplyPlan().setId(31L)
                .setConversationId(7L).setContextId(21L).setSource(GroupChatConstant.PLAN_SOURCE_SCENE));
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
        when(progress.readyCharacterIds(7L, 31L)).thenAnswer(i -> Set.copyOf(ready));
        doAnswer(i -> {ready.remove(i.<Long>getArgument(2)); return null;})
                .when(progress).clearReady(eq(7L), eq(31L), anyLong());
        doAnswer(i -> {ready.add(i.getArgument(2)); return null;})
                .when(progress).markReady(eq(7L), eq(31L), anyLong());
        when(checkpoints.selectById(7L)).thenReturn(checkpoint);
        when(calls.selectList(any())).thenAnswer(i -> {
            LambdaQueryWrapper<GroupChatToolCall> w = i.getArgument(0);
            w.getSqlSegment();
            assertThat(w.getParamNameValuePairs()).containsValue(51L).containsValue(checkpoint.getToolCallId());
            return w.getParamNameValuePairs().containsValue("suspendInvestigators")
                    ? records.reversed().stream().filter(r -> r.getId() > checkpoint.getToolCallId()).toList()
                    : List.of();
        });
        when(calls.deleteAfterCheckpoint(eq(51L), anyLong())).thenAnswer(i -> {
            records.removeIf(r -> r.getId() > i.<Long>getArgument(1)); return 1;
        });
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

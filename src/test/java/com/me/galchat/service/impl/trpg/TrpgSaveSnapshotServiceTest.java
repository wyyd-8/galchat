package com.me.galchat.service.impl.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.GroupChatAgentDecision;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.po.TrpgCombat;
import com.me.galchat.domain.po.TrpgRuntimeChildScene;
import com.me.galchat.domain.po.TrpgInvestigatorSuspension;
import com.me.galchat.domain.po.TrpgWeaponStash;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.CocCharacterSkillMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.DiceRollResultMapper;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import com.me.galchat.mapper.GroupChatAgentDecisionMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.GroupTurnCheckpointMapper;
import com.me.galchat.mapper.TrpgCombatMapper;
import com.me.galchat.mapper.TrpgSaveRestoreMapper;
import com.me.galchat.mapper.TrpgWeaponStashMapper;
import com.me.galchat.mapper.TrpgRuntimeChildSceneMapper;
import com.me.galchat.mapper.TrpgInvestigatorSuspensionMapper;
import com.me.galchat.mapper.VectorStoreCleanupMapper;
import com.me.galchat.service.ITrpgRedisStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Arrays;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrpgSaveSnapshotServiceTest {

    @Test
    void snapshotServiceDependsOnWeaponStashPersistence() {
        assertThat(Arrays.stream(
                        TrpgSaveSnapshotService.class
                                .getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(
                        constructor.getParameterTypes())))
                .contains(com.me.galchat.mapper
                        .TrpgWeaponStashMapper.class);
    }

    @Mock
    private TrpgSaveRestoreMapper restoreMapper;
    @Mock
    private GroupConversationMapper conversationMapper;
    @Mock
    private GroupReplyPlanMapper planMapper;
    @Mock
    private GroupReplyPlanItemMapper planItemMapper;
    @Mock
    private TrpgRuntimeChildSceneMapper runtimeChildSceneMapper;
    @Mock
    private TrpgInvestigatorSuspensionMapper suspensionMapper;
    @Mock
    private CocCharacterMapper characterMapper;
    @Mock
    private CocCharacterProfileMapper profileMapper;
    @Mock
    private CocCharacterSkillMapper skillMapper;
    @Mock
    private CocCharacterWeaponMapper weaponMapper;
    @Mock
    private TrpgWeaponStashMapper weaponStashMapper;
    @Mock
    private TrpgCombatMapper combatMapper;
    @Mock
    private GroupTurnCheckpointMapper checkpointMapper;
    @Mock
    private GroupChatTurnMapper turnMapper;
    @Mock
    private GroupChatReplyStepMapper stepMapper;
    @Mock
    private GroupChatMessageMapper messageMapper;
    @Mock
    private GroupChatToolCallMapper toolCallMapper;
    @Mock
    private GroupChatAgentDecisionMapper decisionMapper;
    @Mock
    private DiceRollSummaryMapper diceSummaryMapper;
    @Mock
    private DiceRollResultMapper diceResultMapper;
    @Mock
    private ITrpgRedisStateService redisStateService;
    @Mock
    private VectorStoreCleanupMapper vectorCleanupMapper;

    private TrpgSaveSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new TrpgSaveSnapshotService(
                restoreMapper,
                conversationMapper,
                planMapper,
                planItemMapper,
                runtimeChildSceneMapper,
                characterMapper,
                profileMapper,
                skillMapper,
                weaponMapper,
                weaponStashMapper,
                combatMapper,
                checkpointMapper,
                turnMapper,
                stepMapper,
                messageMapper,
                toolCallMapper,
                decisionMapper,
                diceSummaryMapper,
                diceResultMapper,
                redisStateService,
                vectorCleanupMapper);
        service.setSuspensionMapper(suspensionMapper);
    }

    @Test
    void captureBuildsACompleteSnapshotForTheRequestedRun() {
        GroupConversation conversation = conversation();
        TrpgSaveSnapshotDTO.CursorSnapshot cursors = cursors();
        GroupReplyPlan parentPlan = new GroupReplyPlan()
                .setId(100L)
                .setConversationId(51L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(301L)
                .setExecutionKey("scene:301")
                .setDisplayName("森林");
        GroupReplyPlan plan = new GroupReplyPlan()
                .setId(101L)
                .setConversationId(51L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(301L)
                .setExecutionKey("scene:101")
                .setDisplayName("林间临时营地")
                .setParentPlanId(100L);
        GroupReplyPlanItem item = new GroupReplyPlanItem()
                .setId(201L)
                .setPlanId(101L);
        TrpgRuntimeChildScene runtimeChildScene =
                new TrpgRuntimeChildScene()
                        .setPlanId(101L)
                        .setConversationId(51L)
                        .setSceneName("林间临时营地")
                        .setCreatedStepId(33L);
        CocCharacter character = new CocCharacter()
                .setId(401L)
                .setRunId(51L)
                .setName("林默")
                .setQuickNotes("藏着钥匙");
        TrpgInvestigatorSuspension suspension =
                new TrpgInvestigatorSuspension()
                        .setId(601L).setConversationId(51L)
                        .setSubjectCharacterId(401L)
                        .setState(TrpgInvestigatorSuspension
                                .STATE_SUSPENDED)
                        .setSuspensionContext("林默被带离森林。")
                        .setOriginContextId(301L);
        TrpgCombat combat = new TrpgCombat()
                .setId(501L)
                .setConversationId(51L);
        TrpgWeaponStash stashedWeapon = new TrpgWeaponStash()
                .setWeaponId(901L)
                .setRunId(51L)
                .setSourceCharacterName("林默")
                .setLocationName("森林 - 营地")
                .setStashReason("DISCARDED")
                .setWeaponSnapshot(
                        new TrpgWeaponStash.WeaponSnapshot()
                                .setName("弓箭")
                                .setRemainingAmmo(1)
                                .setIsBroken(false));
        GroupChatTurn turn = new GroupChatTurn()
                .setId(2L)
                .setConversationId(51L)
                .setStatus(GroupChatConstant.STATUS_WAITING_DICE);
        GroupChatReplyStep step = new GroupChatReplyStep()
                .setId(3L)
                .setTurnId(2L);
        GroupChatMessage message = new GroupChatMessage()
                .setId(1L)
                .setConversationId(51L)
                .setTurnId(2L);
        GroupChatToolCall toolCall = new GroupChatToolCall()
                .setId(4L)
                .setReplyStepId(3L)
                .setDiceRollSummaryId(8L);
        GroupChatAgentDecision decision = new GroupChatAgentDecision()
                .setId(5L)
                .setReplyStepId(3L);
        DiceRollSummary diceSummary = new DiceRollSummary()
                .setId(8L)
                .setConversationId(51L);
        DiceRollResult diceResult = new DiceRollResult()
                .setId(9L)
                .setSummaryId(8L);
        TrpgSaveSnapshotDTO.RedisStateSnapshot redis =
                new TrpgSaveSnapshotDTO.RedisStateSnapshot();
        when(restoreMapper.selectCursors(51L)).thenReturn(cursors);
        when(planMapper.selectList(any()))
                .thenReturn(List.of(parentPlan, plan));
        when(planItemMapper.selectList(any())).thenReturn(List.of(item));
        when(runtimeChildSceneMapper.selectList(any()))
                .thenReturn(List.of(runtimeChildScene));
        when(suspensionMapper.selectList(any()))
                .thenReturn(List.of(suspension));
        when(characterMapper.selectList(any())).thenReturn(List.of(character));
        when(profileMapper.selectList(any())).thenReturn(List.of());
        when(skillMapper.selectList(any())).thenReturn(List.of());
        when(weaponMapper.selectList(any())).thenReturn(List.of());
        when(weaponStashMapper.selectList(any()))
                .thenReturn(List.of(stashedWeapon));
        when(combatMapper.selectList(any())).thenReturn(List.of(combat));
        when(turnMapper.selectList(any())).thenReturn(List.of(turn));
        when(stepMapper.selectList(any())).thenReturn(List.of(step));
        when(messageMapper.selectList(any())).thenReturn(List.of(message));
        when(toolCallMapper.selectList(any())).thenReturn(List.of(toolCall));
        when(decisionMapper.selectList(any())).thenReturn(List.of(decision));
        when(diceSummaryMapper.selectList(any())).thenReturn(List.of(diceSummary));
        when(diceResultMapper.selectList(any())).thenReturn(List.of(diceResult));
        when(redisStateService.capture(
                51L, List.of(100L, 101L))).thenReturn(redis);

        TrpgSaveSnapshotDTO snapshot = service.capture(conversation);

        assertThat(snapshot.getConversationId()).isEqualTo(51L);
        assertThat(snapshot.getUserWorldId()).isEqualTo(12L);
        assertThat(snapshot.getWorldId()).isEqualTo(4L);
        assertThat(snapshot.getModuleId()).isEqualTo(8L);
        assertThat(snapshot.getCursors()).isSameAs(cursors);
        assertThat(snapshot.getConversationState().getActiveReplyPlanId()).isEqualTo(101L);
        assertThat(snapshot.getConversationState().getGameDayNo())
                .isEqualTo(2);
        assertThat(snapshot.getConversationState().getGameTimePeriod())
                .isEqualTo("EVENING");
        assertThat(snapshot.getConversationState().getGameTimeRevision())
                .isEqualTo(4);
        assertThat(snapshot.getConversationState().getGameTimeChangedStepId())
                .isEqualTo(88L);
        assertThat(snapshot.getReplyPlans())
                .containsExactly(parentPlan, plan);
        assertThat(snapshot.getReplyPlanItems()).containsExactly(item);
        assertThat(snapshot.getRuntimeChildScenes())
                .containsExactly(runtimeChildScene);
        assertThat(snapshot.getInvestigatorSuspensions())
                .containsExactly(suspension);
        assertThat(snapshot.getCharacters()).containsExactly(character);
        assertThat(snapshot.getCharacterQuickNotes())
                .containsEntry(401L, "藏着钥匙");
        assertThat(snapshot.getWeaponStash())
                .containsExactly(stashedWeapon);
        assertThat(snapshot.getCombats()).containsExactly(combat);
        assertThat(snapshot.getRestorableTurns()).singleElement()
                .satisfies(turnSnapshot -> {
                    assertThat(turnSnapshot.getTurn()).isEqualTo(turn);
                    assertThat(turnSnapshot.getReplySteps()).containsExactly(step);
                    assertThat(turnSnapshot.getMessages()).containsExactly(message);
                    assertThat(turnSnapshot.getToolCalls()).containsExactly(toolCall);
                    assertThat(turnSnapshot.getAgentDecisions()).containsExactly(decision);
                    assertThat(turnSnapshot.getDiceSummaries()).containsExactly(diceSummary);
                    assertThat(turnSnapshot.getDiceResults()).containsExactly(diceResult);
                });
        assertThat(snapshot.getRedisState()).isSameAs(redis);
    }

    @Test
    void restoreRejectsAPlanFromAnotherRunBeforeDeletingAnything() {
        TrpgSaveSnapshotDTO snapshot = baseSnapshot()
                .setReplyPlans(List.of(new GroupReplyPlan()
                        .setId(101L)
                        .setConversationId(99L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)))
                .setReplyPlanItems(List.of());

        assertThatThrownBy(() -> service.restoreDatabase(conversation(), snapshot))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("回复计划");

        verify(restoreMapper, never()).deleteMessagesAfter(anyLong(), anyLong());
        verify(planMapper, never()).delete(any());
    }

    @Test
    void restoreRejectsAPlanWithoutExecutionMetadata() {
        TrpgSaveSnapshotDTO snapshot = baseSnapshot()
                .setConversationState(
                        new TrpgSaveSnapshotDTO.ConversationStateSnapshot()
                                .setStatus(GroupChatConstant.STATUS_ACTIVE))
                .setReplyPlans(List.of(new GroupReplyPlan()
                        .setId(101L)
                        .setConversationId(51L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                        .setContextId(301L)))
                .setReplyPlanItems(List.of());

        assertThatThrownBy(() -> service.restoreDatabase(
                conversation(), snapshot))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("回复计划");

        verify(planMapper, never()).delete(any());
    }

    @Test
    void restoreRejectsSceneInvestigatorWithoutBoundCharacterCard() {
        GroupReplyPlan plan = new GroupReplyPlan()
                .setId(101L)
                .setConversationId(51L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(301L)
                .setExecutionKey("scene:301")
                .setDisplayName("森林");
        GroupReplyPlanItem item = new GroupReplyPlanItem()
                .setId(201L)
                .setPlanId(101L)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(9L);
        TrpgSaveSnapshotDTO snapshot = baseSnapshot()
                .setConversationState(
                        new TrpgSaveSnapshotDTO.ConversationStateSnapshot()
                                .setActiveReplyPlanId(101L)
                                .setStatus(GroupChatConstant.STATUS_ACTIVE))
                .setReplyPlans(List.of(plan))
                .setReplyPlanItems(List.of(item));

        assertThatThrownBy(() -> service.restoreDatabase(
                conversation(), snapshot))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("缺少人物卡");

        verify(planMapper, never()).delete(any());
    }

    @Test
    void restoreRejectsBoundPlanItemWithoutCharacterNameSnapshot() {
        GroupReplyPlan plan = new GroupReplyPlan()
                .setId(101L)
                .setConversationId(51L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(301L)
                .setExecutionKey("scene:301")
                .setDisplayName("森林");
        GroupReplyPlanItem item = new GroupReplyPlanItem()
                .setId(201L)
                .setPlanId(101L)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(9L)
                .setSubjectCharacterId(401L);
        TrpgSaveSnapshotDTO snapshot = baseSnapshot()
                .setConversationState(
                        new TrpgSaveSnapshotDTO.ConversationStateSnapshot()
                                .setActiveReplyPlanId(101L)
                                .setStatus(GroupChatConstant.STATUS_ACTIVE))
                .setReplyPlans(List.of(plan))
                .setReplyPlanItems(List.of(item))
                .setCharacters(List.of(new CocCharacter()
                        .setId(401L)
                        .setRunId(51L)
                        .setName("林默")));

        assertThatThrownBy(() -> service.restoreDatabase(
                conversation(), snapshot))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("回复计划项目");

        verify(planMapper, never()).delete(any());
    }

    @Test
    void restoreRejectsAnIncompleteCursorBeforeDeletingAnything() {
        TrpgSaveSnapshotDTO snapshot = baseSnapshot();
        snapshot.getCursors().setMaxToolCallId(null);

        assertThatThrownBy(() -> service.restoreDatabase(conversation(), snapshot))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("游标");

        verify(restoreMapper, never()).deleteMessagesAfter(anyLong(), anyLong());
    }

    @Test
    void restoreDeletesOnlyNewRowsAndRestoresTheSavedConversationState() {
        GroupConversation conversation = conversation()
                .setTitle("后来标题")
                .setStatus(GroupChatConstant.STATUS_CLOSED)
                .setActiveReplyPlanId(999L)
                .setGameDayNo(5)
                .setGameTimePeriod("LATE_NIGHT")
                .setGameTimeRevision(9)
                .setGameTimeChangedStepId(999L);
        GroupReplyPlan parentPlan = new GroupReplyPlan()
                .setId(100L)
                .setConversationId(51L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(301L)
                .setExecutionKey("scene:301")
                .setDisplayName("森林");
        GroupReplyPlan plan = new GroupReplyPlan()
                .setId(101L)
                .setConversationId(51L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(301L)
                .setExecutionKey("scene:101")
                .setDisplayName("林间临时营地")
                .setParentPlanId(100L);
        TrpgRuntimeChildScene runtimeChildScene =
                new TrpgRuntimeChildScene()
                        .setPlanId(101L)
                        .setConversationId(51L)
                        .setSceneName("林间临时营地")
                        .setCreatedStepId(33L);
        TrpgSaveSnapshotDTO snapshot = baseSnapshot()
                .setReplyPlans(List.of(parentPlan, plan))
                .setReplyPlanItems(List.of())
                .setRuntimeChildScenes(List.of(runtimeChildScene))
                .setCharacters(List.of())
                .setCharacterProfiles(List.of())
                .setCharacterSkills(List.of())
                .setCharacterWeapons(List.of())
                .setWeaponStash(List.of(new TrpgWeaponStash()
                        .setWeaponId(901L)
                        .setRunId(51L)
                        .setSourceCharacterName("林默")
                        .setLocationName("森林 - 营地")
                        .setStashReason("DISCARDED")
                        .setWeaponSnapshot(
                                new TrpgWeaponStash.WeaponSnapshot()
                                        .setName("弓箭"))))
                .setCombats(List.of())
                .setConversationState(new TrpgSaveSnapshotDTO.ConversationStateSnapshot()
                        .setActiveReplyPlanId(101L)
                        .setTitle("存档标题")
                        .setStatus(GroupChatConstant.STATUS_ACTIVE)
                        .setVersion(3)
                        .setGameDayNo(1)
                        .setGameTimePeriod("MORNING")
                        .setGameTimeRevision(2)
                        .setGameTimeChangedStepId(77L)
                        .setGameTimeUpdatedAt(LocalDateTime.of(
                                2026, 8, 7, 9, 0)));

        service.restoreDatabase(conversation, snapshot);

        verify(vectorCleanupMapper).deleteGroupTopicsByConversationAfterTopicId(51L, 7L);
        verify(vectorCleanupMapper)
                .deleteTrpgTurnsByConversationAfterTurnId(51L, 2L);
        verify(vectorCleanupMapper, never())
                .deleteTrpgTurnsByConversation(51L);
        verify(restoreMapper).deleteMessagesAfter(51L, 1L);
        verify(restoreMapper).deleteTurnsAfter(51L, 2L);
        verify(restoreMapper).deleteReplyStepsAfter(51L, 3L);
        verify(restoreMapper).deleteToolCallsAfter(51L, 4L);
        verify(restoreMapper).deleteAgentDecisionsAfter(51L, 5L);
        verify(restoreMapper).deleteContextSummariesAfter(51L, 6L);
        verify(restoreMapper).deleteTopicsAfter(51L, 7L);
        verify(restoreMapper).deleteDiceSummariesAfter(51L, 8L);
        verify(restoreMapper).deleteDiceResultsAfter(51L, 8L, 9L);
        assertThat(conversation.getTitle()).isEqualTo("存档标题");
        assertThat(conversation.getStatus()).isEqualTo(GroupChatConstant.STATUS_ACTIVE);
        assertThat(conversation.getActiveReplyPlanId()).isEqualTo(101L);
        assertThat(conversation.getGameDayNo()).isEqualTo(1);
        assertThat(conversation.getGameTimePeriod()).isEqualTo("MORNING");
        assertThat(conversation.getGameTimeRevision()).isEqualTo(2);
        assertThat(conversation.getGameTimeChangedStepId()).isEqualTo(77L);
        verify(conversationMapper).updateById(conversation);
        verify(conversationMapper).update(eq(null), any());
        verify(runtimeChildSceneMapper).delete(any());
        verify(runtimeChildSceneMapper).insert(runtimeChildScene);
        verify(weaponStashMapper).delete(any());
        verify(weaponStashMapper).insert(any(TrpgWeaponStash.class));
    }

    @Test
    void restoreDerivedStateRestoresRedisState() {
        TrpgSaveSnapshotDTO snapshot = baseSnapshot();

        service.restoreDerivedState(conversation(), snapshot);

        verify(redisStateService).restore(51L, snapshot.getRedisState());
    }

    @Test
    void restoreReplacesAResumableTurnIncludingItsDiceState() {
        GroupChatTurn turn = new GroupChatTurn()
                .setId(2L)
                .setConversationId(51L)
                .setStatus(GroupChatConstant.STATUS_WAITING_DICE);
        GroupChatReplyStep step = new GroupChatReplyStep()
                .setId(3L)
                .setTurnId(2L);
        GroupChatMessage message = new GroupChatMessage()
                .setId(1L)
                .setConversationId(51L)
                .setTurnId(2L);
        GroupChatToolCall toolCall = new GroupChatToolCall()
                .setId(4L)
                .setReplyStepId(3L)
                .setDiceRollSummaryId(8L);
        GroupChatAgentDecision decision = new GroupChatAgentDecision()
                .setId(5L)
                .setReplyStepId(3L);
        DiceRollSummary summary = new DiceRollSummary()
                .setId(8L)
                .setConversationId(51L);
        DiceRollResult result = new DiceRollResult()
                .setId(9L)
                .setSummaryId(8L);
        TrpgSaveSnapshotDTO snapshot = baseSnapshot()
                .setConversationState(
                        new TrpgSaveSnapshotDTO.ConversationStateSnapshot()
                                .setStatus(GroupChatConstant.STATUS_ACTIVE))
                .setRestorableTurns(List.of(
                        new TrpgSaveSnapshotDTO.RestorableTurnSnapshot()
                                .setTurn(turn)
                                .setReplySteps(List.of(step))
                                .setMessages(List.of(message))
                                .setToolCalls(List.of(toolCall))
                                .setAgentDecisions(List.of(decision))
                                .setDiceSummaries(List.of(summary))
                                .setDiceResults(List.of(result))));

        service.restoreDatabase(conversation(), snapshot);

        verify(vectorCleanupMapper)
                .deleteTrpgTurnsByConversationAndTurnIds(51L, List.of(2L));
        verify(turnMapper).deleteById(2L);
        verify(turnMapper).insert(turn);
        verify(stepMapper).insert(step);
        verify(messageMapper).insert(message);
        verify(toolCallMapper).insert(toolCall);
        verify(decisionMapper).insert(decision);
        verify(diceSummaryMapper).insert(summary);
        verify(diceResultMapper).insert(result);
    }

    private GroupConversation conversation() {
        return new GroupConversation()
                .setId(51L)
                .setUserWorldId(12L)
                .setWorldId(4L)
                .setModuleId(8L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setTitle("雾港")
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setActiveReplyPlanId(101L)
                .setGameDayNo(2)
                .setGameTimePeriod("EVENING")
                .setGameTimeRevision(4)
                .setGameTimeChangedStepId(88L)
                .setGameTimeUpdatedAt(LocalDateTime.of(
                        2026, 8, 7, 18, 0))
                .setVersion(1);
    }

    private TrpgSaveSnapshotDTO baseSnapshot() {
        return new TrpgSaveSnapshotDTO()
                .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                .setConversationId(51L)
                .setUserWorldId(12L)
                .setWorldId(4L)
                .setModuleId(8L)
                .setCursors(cursors())
                .setReplyPlans(List.of())
                .setReplyPlanItems(List.of())
                .setRuntimeChildScenes(List.of())
                .setCharacters(List.of())
                .setCharacterProfiles(List.of())
                .setCharacterSkills(List.of())
                .setCharacterWeapons(List.of())
                .setWeaponStash(List.of())
                .setCombats(List.of());
    }

    private TrpgSaveSnapshotDTO.CursorSnapshot cursors() {
        return new TrpgSaveSnapshotDTO.CursorSnapshot()
                .setMaxMessageId(1L)
                .setMaxTurnId(2L)
                .setMaxReplyStepId(3L)
                .setMaxToolCallId(4L)
                .setMaxAgentDecisionId(5L)
                .setMaxContextSummaryId(6L)
                .setMaxTopicId(7L)
                .setMaxDiceSummaryId(8L)
                .setMaxDiceResultId(9L);
    }
}

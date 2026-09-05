package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.mapper.CocModuleLocationMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;

class TrpgSceneSelectionServiceTest {

    @Test
    void firstSelectionRequiresKpToInitializeGameTime() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        TrpgSceneSelectionStore store =
                mock(TrpgSceneSelectionStore.class);
        GroupConversation conversation = activeTrpgConversation();
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        TrpgSceneSelectionService service = selectionService(
                conversationService,
                mock(CocModuleLocationMapper.class),
                conversationMapper, store);

        assertThatThrownBy(() -> service.publishOptions(
                7L, 30L, 31L, List.of("旅店"), null, null))
                .isInstanceOf(com.me.galchat.exception
                        .UserRequestException.class)
                .hasMessage("首次选景必须设置当前时间");

        verify(conversationMapper, never()).updateById(
                any(GroupConversation.class));
        verify(store, never()).putOptions(any(), any(), any());
    }

    @Test
    void firstSelectionAtomicallyInitializesGameTime() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        TrpgSceneSelectionStore store =
                mock(TrpgSceneSelectionStore.class);
        GroupConversation conversation = activeTrpgConversation();
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(locationMapper.selectList(any())).thenReturn(List.of(
                location(21L, "旅店"),
                location(22L, "医院")));
        org.mockito.Mockito.doReturn(1)
                .when(conversationMapper).updateById(conversation);
        TrpgSceneSelectionService service = selectionService(
                conversationService, locationMapper,
                conversationMapper, store);

        var result = service.publishOptions(
                7L, 30L, 31L,
                List.of("旅店", "医院"), 1, "MORNING");

        assertThat(conversation.getGameDayNo()).isEqualTo(1);
        assertThat(conversation.getGameTimePeriod())
                .isEqualTo("MORNING");
        assertThat(conversation.getGameTimeRevision()).isEqualTo(1);
        assertThat(conversation.getGameTimeChangedStepId())
                .isEqualTo(31L);
        assertThat(result.timeChanged()).isTrue();
        assertThat(result.gameTime().displayText())
                .isEqualTo("第一天 - 上午");
        verify(conversationMapper).updateById(conversation);
        verify(store).putOptions(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(30L),
                any());
    }

    @Test
    void laterSelectionMayKeepCurrentGameTime() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        TrpgSceneSelectionStore store =
                mock(TrpgSceneSelectionStore.class);
        GroupConversation conversation = activeTrpgConversation()
                .setGameDayNo(2)
                .setGameTimePeriod("AFTERNOON")
                .setGameTimeRevision(4);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(locationMapper.selectList(any())).thenReturn(List.of(
                location(21L, "旅店"),
                location(22L, "医院")));
        TrpgSceneSelectionService service = selectionService(
                conversationService, locationMapper,
                conversationMapper, store);

        var result = service.publishOptions(
                7L, 30L, 31L,
                List.of("旅店", "医院"), null, null);

        assertThat(result.timeChanged()).isFalse();
        assertThat(result.gameTime().displayText())
                .isEqualTo("第二天 - 下午");
        assertThat(conversation.getGameTimeRevision()).isEqualTo(4);
        verify(conversationMapper, never()).updateById(
                any(GroupConversation.class));
    }

    @Test
    void selectionOptionsTreatEveryModuleLocationAsMainScene() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        TrpgSceneSelectionStore store =
                mock(TrpgSceneSelectionStore.class);
        GroupConversation conversation = activeTrpgConversation()
                .setGameDayNo(2)
                .setGameTimePeriod("AFTERNOON")
                .setGameTimeRevision(4);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(locationMapper.selectList(any())).thenReturn(List.of(
                location(23L, "医院阁楼")));
        TrpgSceneSelectionService service = selectionService(
                conversationService, locationMapper,
                mock(GroupConversationMapper.class), store);

        var result = service.publishOptions(
                7L, 30L, 31L,
                List.of("医院阁楼"), null, null);

        assertThat(result.options())
                .containsExactlyEntriesOf(java.util.Map.of(
                        "1", "医院阁楼"));
        verify(store).putOptions(any(), any(), any());
    }

    @Test
    void laterSelectionMayAdvanceToAnyFuturePeriod() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        TrpgSceneSelectionStore store =
                mock(TrpgSceneSelectionStore.class);
        GroupConversation conversation = activeTrpgConversation()
                .setGameDayNo(1)
                .setGameTimePeriod("EVENING")
                .setGameTimeRevision(2);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(locationMapper.selectList(any())).thenReturn(List.of(
                location(21L, "旅店"),
                location(22L, "医院")));
        org.mockito.Mockito.doReturn(1)
                .when(conversationMapper).updateById(conversation);
        TrpgSceneSelectionService service = selectionService(
                conversationService, locationMapper,
                conversationMapper, store);

        var result = service.publishOptions(
                7L, 30L, 31L,
                List.of("旅店", "医院"), 3, "DAWN");

        assertThat(result.timeChanged()).isTrue();
        assertThat(result.gameTime().displayText())
                .isEqualTo("第三天 - 清晨");
        assertThat(conversation.getGameTimeRevision()).isEqualTo(3);
    }

    @Test
    void missingReplyStepIdIsNotMistakenForSameStepRetry() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        TrpgSceneSelectionStore store =
                mock(TrpgSceneSelectionStore.class);
        GroupConversation conversation = activeTrpgConversation()
                .setGameDayNo(1)
                .setGameTimePeriod("MORNING")
                .setGameTimeRevision(1);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(locationMapper.selectList(any())).thenReturn(
                List.of(location(21L, "旅店")));
        org.mockito.Mockito.doReturn(1)
                .when(conversationMapper).updateById(conversation);
        TrpgSceneSelectionService service = selectionService(
                conversationService, locationMapper,
                conversationMapper, store);

        var result = service.publishOptions(
                7L, 30L, null,
                List.of("旅店"), 1, "NOON");

        assertThat(result.timeChanged()).isTrue();
        assertThat(result.gameTime().period()).isEqualTo("NOON");
    }

    @Test
    void selectionRejectsTimeThatIsNotLater() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        TrpgSceneSelectionStore store =
                mock(TrpgSceneSelectionStore.class);
        GroupConversation conversation = activeTrpgConversation()
                .setGameDayNo(2)
                .setGameTimePeriod("AFTERNOON")
                .setGameTimeRevision(4);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        TrpgSceneSelectionService service = selectionService(
                conversationService,
                mock(CocModuleLocationMapper.class),
                conversationMapper, store);

        assertThatThrownBy(() -> service.publishOptions(
                7L, 30L, 31L,
                List.of("旅店"), 2, "MORNING"))
                .isInstanceOf(com.me.galchat.exception
                        .UserRequestException.class)
                .hasMessage("KP只能将时间推进到未来");

        verify(conversationMapper, never()).updateById(
                any(GroupConversation.class));
        verify(store, never()).putOptions(any(), any(), any());
    }

    @Test
    void retryingTheSameKpStepDoesNotAdvanceTimeTwice() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        TrpgSceneSelectionStore store =
                mock(TrpgSceneSelectionStore.class);
        GroupConversation conversation = activeTrpgConversation()
                .setGameDayNo(1)
                .setGameTimePeriod("AFTERNOON")
                .setGameTimeRevision(2)
                .setGameTimeChangedStepId(31L);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(locationMapper.selectList(any())).thenReturn(List.of(
                location(21L, "旅店"),
                location(22L, "医院")));
        TrpgSceneSelectionService service = selectionService(
                conversationService, locationMapper,
                conversationMapper, store);

        var result = service.publishOptions(
                7L, 30L, 31L,
                List.of("旅店", "医院"), 1, "AFTERNOON");

        assertThat(result.timeChanged()).isFalse();
        assertThat(conversation.getGameTimeRevision()).isEqualTo(2);
        verify(conversationMapper, never()).updateById(
                any(GroupConversation.class));
        verify(store).putOptions(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(30L),
                any());
    }

    @Test
    void selectionStageSchedulesKpBeforeEveryEnabledInvestigator() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        TrpgParticipantService participantService =
                mock(TrpgParticipantService.class);
        TrpgSceneSelectionService service = new TrpgSceneSelectionService(
                conversationService,
                mock(CocModuleLocationMapper.class),
                mock(GroupConversationMapper.class),
                mock(GroupReplyPlanMapper.class),
                mock(GroupReplyPlanItemMapper.class),
                mock(TrpgSceneSelectionStore.class),
                participantService,
                mock(TrpgSelectionRandomizer.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setModuleId(3L)
                .setMode(GroupChatConstant.MODE_TRPG);
        when(participantService.listInvestigators(conversation))
                .thenReturn(List.of(
                        participant(GroupChatConstant.ACTOR_USER,
                                101L, 101L, "林登", "用户"),
                        participant(GroupChatConstant.ACTOR_CHARACTER,
                                9L, 201L, "玛格丽特", "爱丽丝"),
                        participant(GroupChatConstant.ACTOR_CHARACTER,
                                7L, 202L, "陈默", "夏洛特")));

        var actions = service.selectionActions(conversation);

        assertThat(actions)
                .extracting(
                        action -> action.actorType(),
                        action -> action.actorId(),
                        action -> action.actionType())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.ACTOR_KP, null,
                                GroupChatConstant.ACTION_TRPG_SCENE_SELECTION),
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.ACTOR_USER, 101L,
                                GroupChatConstant.ACTION_TRPG_SCENE_SELECTION),
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.ACTOR_CHARACTER, 9L,
                                GroupChatConstant.ACTION_TRPG_SCENE_SELECTION),
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.ACTOR_CHARACTER, 7L,
                                GroupChatConstant.ACTION_TRPG_SCENE_SELECTION));
    }

    @Test
    void selectionStageOmitsSuspendedInvestigators() {
        TrpgParticipantService participantService =
                mock(TrpgParticipantService.class);
        TrpgInvestigatorSuspensionService suspensionService =
                mock(TrpgInvestigatorSuspensionService.class);
        TrpgSceneSelectionService service = new TrpgSceneSelectionService(
                mock(GroupConversationService.class),
                mock(CocModuleLocationMapper.class),
                mock(GroupConversationMapper.class),
                mock(GroupReplyPlanMapper.class),
                mock(GroupReplyPlanItemMapper.class),
                mock(TrpgSceneSelectionStore.class),
                participantService,
                mock(TrpgSelectionRandomizer.class));
        service.setSuspensionService(suspensionService);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setModuleId(3L)
                .setMode(GroupChatConstant.MODE_TRPG);
        when(participantService.listInvestigators(conversation))
                .thenReturn(List.of(
                        participant(GroupChatConstant.ACTOR_USER,
                                101L, 101L, "林登", "用户"),
                        participant(GroupChatConstant.ACTOR_CHARACTER,
                                9L, 201L, "玛格丽特", "爱丽丝")));
        when(suspensionService.isUnavailable(7L, 101L, null))
                .thenReturn(false);
        when(suspensionService.isUnavailable(7L, 201L, null))
                .thenReturn(true);

        assertThat(service.selectionActions(conversation))
                .extracting(GroupActionSpec::subjectCharacterId)
                .containsExactly(null, 101L);
    }

    @Test
    void investigatorSelectsLocationByExactName() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        TrpgSceneSelectionStore store = mock(TrpgSceneSelectionStore.class);
        TrpgSceneSelectionService service = new TrpgSceneSelectionService(
                conversationService,
                locationMapper,
                mock(GroupConversationMapper.class),
                mock(GroupReplyPlanMapper.class),
                mock(GroupReplyPlanItemMapper.class),
                store,
                mock(TrpgParticipantService.class),
                mock(TrpgSelectionRandomizer.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setModuleId(3L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        when(conversationService.requireActive(7L)).thenReturn(conversation);
        when(locationMapper.selectList(any())).thenReturn(List.of(
                new CocModuleLocation()
                        .setId(21L)
                        .setModuleId(3L)
                        .setName("皇家橡树酒店")));

        service.selectLocation(7L, 9L, " 皇家橡树酒店 ");

        verify(conversationService).checkReplyMember(
                7L, GroupChatConstant.ACTOR_CHARACTER, 9L, false);
        verify(store).put(7L, 9L, 21L);
    }

    @Test
    void completedSelectionsCreateASeparatePlanChainByLocation() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper =
                mock(GroupReplyPlanItemMapper.class);
        TrpgSceneSelectionStore store = mock(TrpgSceneSelectionStore.class);
        TrpgParticipantService participantService =
                mock(TrpgParticipantService.class);
        TrpgSceneSelectionService service = new TrpgSceneSelectionService(
                conversationService, locationMapper, conversationMapper,
                planMapper, itemMapper, store,
                participantService,
                mock(TrpgSelectionRandomizer.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setModuleId(3L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        when(participantService.listInvestigators(conversation))
                .thenReturn(List.of(
                        participant(GroupChatConstant.ACTOR_USER,
                                101L, 101L, "林登", "用户"),
                        participant(GroupChatConstant.ACTOR_CHARACTER,
                                9L, 201L, "玛格丽特", "爱丽丝")));
        when(store.getSelections(7L)).thenReturn(
                java.util.Map.of(
                        "user:101", 21L,
                        "character:9", 22L));
        when(locationMapper.selectList(any())).thenReturn(List.of(
                new CocModuleLocation().setId(22L).setModuleId(3L)
                        .setName("医院"),
                new CocModuleLocation().setId(21L).setModuleId(3L)
                        .setName("酒店")));
        java.util.concurrent.atomic.AtomicLong planIds =
                new java.util.concurrent.atomic.AtomicLong(100L);
        org.mockito.Mockito.doAnswer(invocation -> {
            ((GroupReplyPlan) invocation.getArgument(0))
                    .setId(planIds.incrementAndGet());
            return 1;
        }).when(planMapper).insert(any(GroupReplyPlan.class));

        assertThat(service.finalizeSelections(conversation)).isTrue();

        var planCaptor =
                org.mockito.ArgumentCaptor.forClass(GroupReplyPlan.class);
        verify(planMapper, times(2)).insert(planCaptor.capture());
        assertThat(planCaptor.getAllValues())
                .extracting(GroupReplyPlan::getContextId,
                        GroupReplyPlan::getExecutionKey,
                        GroupReplyPlan::getDisplayName,
                        GroupReplyPlan::getNextPlanId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                22L, "scene:22", "医院", null),
                        org.assertj.core.groups.Tuple.tuple(
                                21L, "scene:21", "酒店", 101L));
        assertThat(conversation.getActiveReplyPlanId()).isEqualTo(102L);
        var itemCaptor =
                org.mockito.ArgumentCaptor.forClass(GroupReplyPlanItem.class);
        verify(itemMapper, times(4)).insert(itemCaptor.capture());
        assertThat(itemCaptor.getAllValues())
                .extracting(GroupReplyPlanItem::getPlanId,
                        GroupReplyPlanItem::getActorType,
                        GroupReplyPlanItem::getActorId,
                        GroupReplyPlanItem::getSubjectCharacterId,
                        GroupReplyPlanItem::getSubjectCharacterName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                101L, GroupChatConstant.ACTOR_CHARACTER, 9L,
                                201L, "玛格丽特"),
                        org.assertj.core.groups.Tuple.tuple(
                                101L, GroupChatConstant.ACTOR_KP, null, null,
                                null),
                        org.assertj.core.groups.Tuple.tuple(
                                102L, GroupChatConstant.ACTOR_USER, 101L,
                                101L, "林登"),
                        org.assertj.core.groups.Tuple.tuple(
                                102L, GroupChatConstant.ACTOR_KP, null, null,
                                null));
        verify(conversationMapper).updateById(conversation);
        verify(store).clear(7L);
    }

    @Test
    void kpPublishesNumberedOptionsAndOneOptionAutoAssignsEveryone() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        TrpgSceneSelectionStore store =
                mock(TrpgSceneSelectionStore.class);
        TrpgParticipantService participantService =
                mock(TrpgParticipantService.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setModuleId(3L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setGameDayNo(1)
                .setGameTimePeriod("MORNING")
                .setGameTimeRevision(1);
        TrpgSceneSelectionService service = new TrpgSceneSelectionService(
                conversationService, locationMapper,
                mock(GroupConversationMapper.class),
                mock(GroupReplyPlanMapper.class),
                mock(GroupReplyPlanItemMapper.class),
                store, participantService,
                mock(TrpgSelectionRandomizer.class));
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(locationMapper.selectList(any())).thenReturn(List.of(
                new CocModuleLocation().setId(21L)
                        .setModuleId(3L).setName("旅店")));
        var user = participant(GroupChatConstant.ACTOR_USER,
                101L, 101L, "林登", "用户");
        var agent = participant(GroupChatConstant.ACTOR_CHARACTER,
                9L, 201L, "玛格丽特", "爱丽丝");
        when(participantService.listInvestigators(conversation))
                .thenReturn(List.of(user, agent));
        when(store.getSelections(7L)).thenReturn(
                java.util.Map.of(
                        "user:101", 21L,
                        "character:9", 21L));
        when(locationMapper.selectList(any())).thenReturn(List.of(
                new CocModuleLocation().setId(21L)
                        .setModuleId(3L).setName("旅店")));

        var result = service.publishOptions(7L, List.of("旅店"));

        assertThat(result.options()).containsExactly(
                java.util.Map.entry("1", "旅店"));
        assertThat(result.autoAssigned()).isTrue();
        verify(store).put(7L, 0L, user.actor(), 21L);
        verify(store).put(7L, 0L, agent.actor(), 21L);
    }

    @Test
    void invalidNumberRandomlyFallsBackToAnUnselectedLocationFirst() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        TrpgSceneSelectionStore store =
                mock(TrpgSceneSelectionStore.class);
        TrpgParticipantService participantService =
                mock(TrpgParticipantService.class);
        TrpgSelectionRandomizer randomizer =
                mock(TrpgSelectionRandomizer.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setModuleId(3L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        var agent = participant(
                GroupChatConstant.ACTOR_CHARACTER,
                9L, 201L, "玛格丽特", "爱丽丝");
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(participantService.listInvestigators(conversation))
                .thenReturn(List.of(agent));
        var hotel = new TrpgSceneSelectionStore.LocationOption(
                21L, "酒店");
        var hospital = new TrpgSceneSelectionStore.LocationOption(
                22L, "医院");
        when(store.getOptions(7L, 0L)).thenReturn(
                java.util.Map.of("1", hotel, "2", hospital));
        when(store.getSelections(7L, 0L)).thenReturn(
                java.util.Map.of("user:101", 21L));
        when(randomizer.choose(List.of(hospital)))
                .thenReturn(hospital);
        TrpgSceneSelectionService service = new TrpgSceneSelectionService(
                conversationService,
                mock(CocModuleLocationMapper.class),
                mock(GroupConversationMapper.class),
                mock(GroupReplyPlanMapper.class),
                mock(GroupReplyPlanItemMapper.class),
                store, participantService, randomizer);

        var result = service.selectOption(
                7L, agent.actor(), "99");

        assertThat(result.locationName()).isEqualTo("医院");
        assertThat(result.randomized()).isTrue();
        verify(randomizer).choose(List.of(hospital));
        verify(store).put(7L, 0L, agent.actor(), 22L);
    }

    private GroupChatMember member(Long actorId, boolean enabled) {
        return new GroupChatMember()
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(actorId)
                .setEnabled(enabled);
    }

    private TrpgSceneSelectionService selectionService(
            GroupConversationService conversationService,
            CocModuleLocationMapper locationMapper,
            GroupConversationMapper conversationMapper,
            TrpgSceneSelectionStore store) {
        return new TrpgSceneSelectionService(
                conversationService,
                locationMapper,
                conversationMapper,
                mock(GroupReplyPlanMapper.class),
                mock(GroupReplyPlanItemMapper.class),
                store,
                mock(TrpgParticipantService.class),
                mock(TrpgSelectionRandomizer.class));
    }

    private GroupConversation activeTrpgConversation() {
        return new GroupConversation()
                .setId(7L)
                .setModuleId(3L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
    }

    private CocModuleLocation location(Long id, String name) {
        return new CocModuleLocation()
                .setId(id)
                .setModuleId(3L)
                .setName(name);
    }

    private TrpgParticipantService.Participant participant(
            String actorType, Long actorId, Long cardId,
            String investigatorName, String controllerName) {
        return new TrpgParticipantService.Participant(
                new com.me.galchat.groupchat.runtime.GroupActorRef(
                        actorType, actorId),
                cardId, investigatorName, controllerName);
    }
}

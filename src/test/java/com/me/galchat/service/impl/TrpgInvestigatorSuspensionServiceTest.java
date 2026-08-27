package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.po.TrpgInvestigatorSuspension;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.TrpgInvestigatorSuspensionMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgInvestigatorSuspensionServiceTest {

    @Test
    void suspendsAStorylineWithoutChangingSceneParticipantStatus() {
        Fixture fixture = fixture();

        String result = fixture.service().suspendInvestigators(
                7L, 51L, List.of("艾琳"),
                "艾琳被林中的陌生人带走，镜头停在她失去意识时。");

        assertThat(result).contains("艾琳").contains("切换镜头");
        assertThat(fixture.elaineItem().getParticipantStatus())
                .isEqualTo(GroupChatConstant.PARTICIPANT_ACTIVE);
        verify(fixture.suspensionMapper()).insert(any(
                TrpgInvestigatorSuspension.class));
    }

    @Test
    void refusesToSuspendEveryInvestigatorBecauseThereIsNoStorylineToCutTo() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service().suspendInvestigators(
                7L, 51L, List.of("亨利", "艾琳"), "两人都失去意识。"))
                .hasMessageContaining("至少保留一名");
    }

    @Test
    void mergingIntoCurrentSceneMakesInvestigatorAvailableNextTurn() {
        Fixture fixture = fixture();
        fixture.existingSuspensions().add(new TrpgInvestigatorSuspension()
                .setId(81L).setConversationId(7L)
                .setSubjectCharacterId(109L)
                .setState(TrpgInvestigatorSuspension.STATE_SUSPENDED)
                .setSuspensionContext("艾琳在林中被带走。")
                .setOriginContextId(21L));

        String result = fixture.service().resumeSuspendedInvestigators(
                7L, 51L, List.of("艾琳"), "CURRENT_SCENE",
                "艾琳在废弃小屋醒来并自行返回营地。", null);

        assertThat(result).contains("艾琳").contains("下一轮");
        assertThat(fixture.existingSuspensions().getFirst().getState())
                .isEqualTo(TrpgInvestigatorSuspension.STATE_REENTRY_PENDING);
        assertThat(fixture.service().isUnavailable(7L, 109L, 31L))
                .isFalse();
        assertThat(fixture.service().reentryPrompt(7L, 109L, 31L))
                .contains("艾琳在林中被带走")
                .contains("艾琳在废弃小屋醒来并自行返回营地")
                .contains("系统没有主持该调查员在停镜期间的其他行动");
    }

    @Test
    void independentRecoverySceneIsAppendedAfterTheExistingMainSceneChain() {
        Fixture fixture = fixture();
        fixture.existingSuspensions().add(new TrpgInvestigatorSuspension()
                .setId(81L).setConversationId(7L)
                .setSubjectCharacterId(109L)
                .setState(TrpgInvestigatorSuspension.STATE_SUSPENDED)
                .setSuspensionContext("艾琳被带离营地。")
                .setOriginContextId(21L));
        GroupReplyPlan next = new GroupReplyPlan()
                .setId(41L).setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(22L).setExecutionKey("scene:41")
                .setDisplayName("农舍");
        fixture.scene().setNextPlanId(41L);
        when(fixture.planMapper().selectById(41L)).thenReturn(next);
        when(fixture.planMapper().insert(any(GroupReplyPlan.class)))
                .thenAnswer(invocation -> {
                    invocation.<GroupReplyPlan>getArgument(0).setId(51L);
                    return 1;
                });

        String result = fixture.service().resumeSuspendedInvestigators(
                7L, 51L, List.of("艾琳"), "INDEPENDENT_SCENE",
                "艾琳在陌生地窖中醒来。", "陌生地窖");

        assertThat(result).contains("陌生地窖").contains("排入");
        assertThat(next.getNextPlanId()).isEqualTo(51L);
        TrpgInvestigatorSuspension suspension =
                fixture.existingSuspensions().getFirst();
        assertThat(suspension.getState()).isEqualTo(
                TrpgInvestigatorSuspension.STATE_RECOVERY_QUEUED);
        assertThat(suspension.getRecoveryPlanId()).isEqualTo(51L);
        assertThat(fixture.service().isUnavailable(7L, 109L, 31L))
                .isTrue();
        assertThat(fixture.service().isUnavailable(7L, 109L, 51L))
                .isFalse();
    }

    @Test
    void completedFirstRestoredActionConsumesTheTemporaryBridge() {
        Fixture fixture = fixture();
        fixture.existingSuspensions().add(new TrpgInvestigatorSuspension()
                .setId(81L).setConversationId(7L)
                .setSubjectCharacterId(109L)
                .setState(TrpgInvestigatorSuspension.STATE_REENTRY_PENDING)
                .setSuspensionContext("艾琳被带离营地。")
                .setReentryContext("艾琳已经返回营地。")
                .setRecoveryPlanId(31L));
        when(fixture.stepMapper().selectList(any())).thenReturn(List.of(
                new GroupChatReplyStep().setTurnId(61L)
                        .setSpeakerType(GroupChatConstant.ACTOR_CHARACTER)
                        .setSubjectCharacterId(109L)
                        .setStatus(GroupChatConstant.STATUS_COMPLETED)));

        fixture.service().completeReentriesAfterTurn(
                new GroupChatTurn().setId(61L).setConversationId(7L)
                        .setPlanId(31L)
                        .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE));

        verify(fixture.suspensionMapper()).deleteById(81L);
    }

    private Fixture fixture() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        TrpgParticipantService participantService =
                mock(TrpgParticipantService.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatTurnMapper turnMapper =
                mock(GroupChatTurnMapper.class);
        GroupReplyPlanMapper planMapper =
                mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper =
                mock(GroupReplyPlanItemMapper.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        TrpgInvestigatorSuspensionMapper suspensionMapper =
                mock(TrpgInvestigatorSuspensionMapper.class);
        TrpgSceneProgressStore progressStore =
                mock(TrpgSceneProgressStore.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setMode(GroupChatConstant.MODE_TRPG)
                .setActiveReplyPlanId(31L).setModuleId(5L);
        GroupReplyPlan scene = new GroupReplyPlan()
                .setId(31L).setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(21L).setExecutionKey("scene:31")
                .setDisplayName("林中营地");
        GroupChatReplyStep step = new GroupChatReplyStep()
                .setId(51L).setTurnId(61L)
                .setSpeakerType(GroupChatConstant.ACTOR_KP);
        GroupChatTurn turn = new GroupChatTurn()
                .setId(61L).setConversationId(7L).setPlanId(31L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE);
        GroupReplyPlanItem henry = item(1L, 101L, "亨利",
                GroupChatConstant.ACTOR_USER, 101L, 1);
        GroupReplyPlanItem elaine = item(2L, 109L, "艾琳",
                GroupChatConstant.ACTOR_CHARACTER, 9L, 2);
        java.util.ArrayList<TrpgInvestigatorSuspension> suspensions =
                new java.util.ArrayList<>();
        when(conversationService.requireActive(7L)).thenReturn(conversation);
        when(participantService.listInvestigators(conversation)).thenReturn(
                List.of(
                        new TrpgParticipantService.Participant(
                                new GroupActorRef(GroupChatConstant.ACTOR_USER,
                                        101L), 101L, "亨利", "用户"),
                        new TrpgParticipantService.Participant(
                                new GroupActorRef(GroupChatConstant.ACTOR_CHARACTER,
                                        9L), 109L, "艾琳", "Agent")));
        when(stepMapper.selectById(51L)).thenReturn(step);
        when(turnMapper.selectById(61L)).thenReturn(turn);
        when(planMapper.selectById(31L)).thenReturn(scene);
        when(itemMapper.selectList(any())).thenReturn(
                List.of(henry, elaine));
        when(suspensionMapper.selectList(any())).thenAnswer(
                ignored -> suspensions);
        when(suspensionMapper.selectOne(any())).thenAnswer(ignored ->
                suspensions.stream().findFirst().orElse(null));
        TrpgInvestigatorSuspensionService service =
                new TrpgInvestigatorSuspensionService(
                        conversationService, participantService,
                        stepMapper, turnMapper, planMapper, itemMapper,
                        conversationMapper, suspensionMapper,
                        progressStore);
        return new Fixture(service, suspensionMapper, suspensions, elaine,
                planMapper, scene, stepMapper);
    }

    private GroupReplyPlanItem item(Long id, Long characterId, String name,
                                    String actorType, Long actorId, int order) {
        return new GroupReplyPlanItem().setId(id).setPlanId(31L)
                .setSubjectCharacterId(characterId)
                .setSubjectCharacterName(name).setActorType(actorType)
                .setActorId(actorId).setItemOrder(order)
                .setParticipantStatus(GroupChatConstant.PARTICIPANT_ACTIVE);
    }

    private record Fixture(
            TrpgInvestigatorSuspensionService service,
            TrpgInvestigatorSuspensionMapper suspensionMapper,
            java.util.ArrayList<TrpgInvestigatorSuspension>
                    existingSuspensions,
            GroupReplyPlanItem elaineItem,
            GroupReplyPlanMapper planMapper,
            GroupReplyPlan scene,
            GroupChatReplyStepMapper stepMapper) {
    }
}

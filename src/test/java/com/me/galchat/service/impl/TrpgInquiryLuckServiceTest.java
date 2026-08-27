package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.KpDiceRequestDTOs;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import com.me.galchat.domain.vo.KpDiceToolResult;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.groupchat.tool.GroupToolCallStore;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.service.ICocDiceOrchestrationService;
import com.me.galchat.service.ICharacterCardService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgInquiryLuckServiceTest {

    @Test
    void groupScopeRollsLuckForLowestActiveInvestigator() {
        Fixture fixture = new Fixture();
        KpDiceToolResult expected = mock(KpDiceToolResult.class);
        when(fixture.orchestration.requestCheck(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(expected);

        KpDiceToolResult actual = fixture.service.request(
                fixture.conversation, 101L, 301L,
                "当前街道在短时间内有一辆可见的空载出租车经过",
                "CURRENT_INVESTIGATOR_GROUP");

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<KpDiceRequestDTOs.Check> request =
                ArgumentCaptor.forClass(KpDiceRequestDTOs.Check.class);
        verify(fixture.orchestration).requestCheck(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(7L), request.capture());
        assertThat(request.getValue().reason())
                .contains("空载出租车经过");
        assertThat(request.getValue().target().characterName())
                .isEqualTo("陈默");
        assertThat(request.getValue().target().checkNames())
                .containsExactly("幸运");
    }

    @Test
    void requesterScopeUsesInquiryRequester() {
        Fixture fixture = new Fixture();

        fixture.service.request(
                fixture.conversation, 101L, 301L,
                "路口恰好亮起绿灯", "REQUESTER");

        ArgumentCaptor<KpDiceRequestDTOs.Check> request =
                ArgumentCaptor.forClass(KpDiceRequestDTOs.Check.class);
        verify(fixture.orchestration).requestCheck(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(7L), request.capture());
        assertThat(request.getValue().target().characterName())
                .isEqualTo("林恩");
    }

    @Test
    void requesterScopeSupportsHumanInvestigatorInquiry() {
        Fixture fixture = new Fixture();
        fixture.parent
                .setSpeakerType(GroupChatConstant.ACTOR_USER)
                .setSpeakerId(32L)
                .setSubjectCharacterId(32L);
        fixture.child.setSubjectCharacterId(32L);

        fixture.service.request(
                fixture.conversation, 101L, 301L,
                "路口恰好亮起绿灯", "REQUESTER");

        ArgumentCaptor<KpDiceRequestDTOs.Check> request =
                ArgumentCaptor.forClass(KpDiceRequestDTOs.Check.class);
        verify(fixture.orchestration).requestCheck(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(7L), request.capture());
        assertThat(request.getValue().target().characterName())
                .isEqualTo("陈默");
    }

    @Test
    void rejectsSecondLuckRollInSameInquiryAnswer() {
        Fixture fixture = new Fixture();
        when(fixture.toolCalls.hasExecution(
                301L, "requestInquiryLuck")).thenReturn(true);

        assertThatThrownBy(() -> fixture.service.request(
                fixture.conversation, 101L, 301L,
                "有出租车经过", "CURRENT_INVESTIGATOR_GROUP"))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("一次");
    }

    @Test
    void rejectsLuckAfterInquiryAnswerIsNoLongerRunning() {
        Fixture fixture = new Fixture();
        fixture.child.setStatus(GroupChatConstant.STATUS_COMPLETED);

        assertThatThrownBy(() -> fixture.service.request(
                fixture.conversation, 101L, 301L,
                "有出租车经过", "REQUESTER"))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("当前步骤");
    }

    private static final class Fixture {
        private final GroupChatReplyStepMapper steps =
                mock(GroupChatReplyStepMapper.class);
        private final GroupChatTurnMapper turns =
                mock(GroupChatTurnMapper.class);
        private final TrpgParticipantService participants =
                mock(TrpgParticipantService.class);
        private final TrpgSceneParticipantService sceneParticipants =
                mock(TrpgSceneParticipantService.class);
        private final ICharacterCardService cards =
                mock(ICharacterCardService.class);
        private final ICocDiceOrchestrationService orchestration =
                mock(ICocDiceOrchestrationService.class);
        private final GroupToolCallStore toolCalls =
                mock(GroupToolCallStore.class);
        private final TrpgInquiryLuckService service =
                new TrpgInquiryLuckService(
                        steps, turns, participants, sceneParticipants,
                        cards, orchestration, toolCalls);
        private final GroupConversation conversation =
                new GroupConversation().setId(7L)
                        .setMode(GroupChatConstant.MODE_TRPG);
        private final GroupChatReplyStep parent;
        private final GroupChatReplyStep child;

        private Fixture() {
            parent = new GroupChatReplyStep()
                    .setId(201L).setTurnId(101L)
                    .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                    .setSpeakerType(GroupChatConstant.ACTOR_CHARACTER)
                    .setSpeakerId(9L).setSubjectCharacterId(31L)
                    .setInteractionType(TrpgStepInteractionService
                            .INVESTIGATOR_KP_INQUIRY)
                    .setStatus(GroupChatConstant
                            .STATUS_WAITING_INTERACTION);
            child = new GroupChatReplyStep()
                    .setId(301L).setTurnId(101L)
                    .setParentStepId(201L).setRootStepId(201L)
                    .setActionType(GroupChatConstant
                            .ACTION_TRPG_INTERACTION_RESPONSE)
                    .setSpeakerType(GroupChatConstant.ACTOR_KP)
                    .setInteractionType(TrpgStepInteractionService
                            .INVESTIGATOR_KP_INQUIRY)
                    .setSubjectCharacterId(31L)
                    .setStatus(GroupChatConstant.STATUS_RUNNING);
            when(turns.selectById(101L)).thenReturn(
                    new GroupChatTurn().setId(101L)
                            .setConversationId(7L));
            when(steps.selectById(301L)).thenReturn(child);
            when(steps.selectById(201L)).thenReturn(parent);
            when(participants.listInvestigators(conversation))
                    .thenReturn(List.of(
                            new TrpgParticipantService.Participant(
                                    new GroupActorRef(
                                            GroupChatConstant
                                                    .ACTOR_CHARACTER,
                                            9L), 31L, "林恩", "Agent甲"),
                            new TrpgParticipantService.Participant(
                                    new GroupActorRef(
                                            GroupChatConstant.ACTOR_USER,
                                            32L), 32L, "陈默", "用户")));
            when(sceneParticipants.state(conversation)).thenReturn(
                    new TrpgSceneParticipantService.SceneState(
                            51L, "深夜街道", List.of("林恩", "陈默"),
                            List.of(31L, 32L), List.of()));
            when(cards.listDiceCharacters(7L)).thenReturn(List.of(
                    card(31L, "林恩", 60),
                    card(32L, "陈默", 35)));
        }

        private CocDiceCharacterVO card(
                Long id, String name, int luck) {
            return new CocDiceCharacterVO(
                    id, "BOT", 9L, name,
                    Map.of("幸运", luck),
                    10, 10, 50, 50, 50, 0,
                    false, false, false, false,
                    false, null, null);
        }
    }
}

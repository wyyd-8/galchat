package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgStepInteractionServiceTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        com.me.galchat.support.MybatisPlusTestSupport.initialize(
                GroupChatReplyStep.class);
    }

    @Test
    void individualClarificationCreatesAgentChildAndSuspendsRoot() {
        GroupChatReplyStepMapper steps =
                mock(GroupChatReplyStepMapper.class);
        GroupChatTurnMapper turns = mock(GroupChatTurnMapper.class);
        TrpgParticipantService participants =
                mock(TrpgParticipantService.class);
        TrpgStepInteractionService service = service(
                steps, turns, participants);
        GroupChatTurn turn = runningTurn();
        GroupChatReplyStep root = rootStep();
        when(turns.selectById(101L)).thenReturn(turn);
        when(steps.selectById(201L)).thenReturn(root);
        when(steps.selectList(any()))
                .thenReturn(List.of(), List.of(root));
        when(participants.listInvestigators(any()))
                .thenReturn(List.of(
                        participant(GroupChatConstant.ACTOR_USER,
                                31L, 31L, "林恩"),
                        participant(GroupChatConstant.ACTOR_CHARACTER,
                                9L, 32L, "陈默")));
        doAnswer(invocation -> {
            invocation.<GroupChatReplyStep>getArgument(0).setId(301L);
            return 1;
        }).when(steps).insert(any(GroupChatReplyStep.class));

        TrpgStepInteractionService.InteractionRequest result =
                service.askForClarification(
                        conversation(), 101L, 201L,
                        "INDIVIDUAL", "陈默",
                        "你具体要检查书桌的哪一部分？", "METHOD");

        assertThat(result.childStepId()).isEqualTo(301L);
        assertThat(result.question())
                .isEqualTo("你具体要检查书桌的哪一部分？");
        assertThat(root.getStatus()).isEqualTo("waiting_interaction");
        verify(steps).updateById(root);
        assertThat(result.targetActor())
                .isEqualTo(new GroupActorRef(
                        GroupChatConstant.ACTOR_CHARACTER, 9L));
        assertThat(result.targetCharacterId()).isEqualTo(32L);
        assertThat(result.interactionSeq()).isEqualTo(1);
    }

    @Test
    void teamClarificationAlwaysTargetsHumanPlayer() {
        GroupChatReplyStepMapper steps =
                mock(GroupChatReplyStepMapper.class);
        GroupChatTurnMapper turns = mock(GroupChatTurnMapper.class);
        TrpgParticipantService participants =
                mock(TrpgParticipantService.class);
        TrpgStepInteractionService service = service(
                steps, turns, participants);
        when(turns.selectById(101L)).thenReturn(runningTurn());
        when(steps.selectById(201L)).thenReturn(rootStep());
        when(steps.selectList(any()))
                .thenReturn(List.of(), List.of(rootStep()));
        when(participants.listInvestigators(any()))
                .thenReturn(List.of(
                        participant(GroupChatConstant.ACTOR_USER,
                                31L, 31L, "林恩"),
                        participant(GroupChatConstant.ACTOR_CHARACTER,
                                9L, 32L, "陈默")));
        doAnswer(invocation -> {
            invocation.<GroupChatReplyStep>getArgument(0).setId(302L);
            return 1;
        }).when(steps).insert(any(GroupChatReplyStep.class));

        TrpgStepInteractionService.InteractionRequest result =
                service.askForClarification(
                        conversation(), 101L, 201L,
                        "TEAM", null,
                        "你们确认要一起穿过正在坍塌的走廊吗？", "RISK");

        assertThat(result.targetActor())
                .isEqualTo(new GroupActorRef(
                        GroupChatConstant.ACTOR_USER, 31L));
        assertThat(result.targetCharacterId()).isEqualTo(31L);
        assertThat(result.interactionType())
                .isEqualTo("TEAM_RISK_CONFIRMATION");
    }

    @Test
    void seventhClarificationIsRejected() {
        GroupChatReplyStepMapper steps =
                mock(GroupChatReplyStepMapper.class);
        GroupChatTurnMapper turns = mock(GroupChatTurnMapper.class);
        TrpgParticipantService participants =
                mock(TrpgParticipantService.class);
        TrpgStepInteractionService service = service(
                steps, turns, participants);
        when(turns.selectById(101L)).thenReturn(runningTurn());
        when(steps.selectById(201L)).thenReturn(rootStep());
        when(steps.selectList(any())).thenReturn(List.of(
                child(1), child(2), child(3),
                child(4), child(5), child(6)));

        assertThatThrownBy(() -> service.askForClarification(
                conversation(), 101L, 201L,
                "TEAM", null, "还需要确认吗？", "RISK"))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("最多追问6次");
    }

    private TrpgStepInteractionService service(
            GroupChatReplyStepMapper steps,
            GroupChatTurnMapper turns,
            TrpgParticipantService participants) {
        return new TrpgStepInteractionService(
                steps, turns, participants,
                immediateTransactionTemplate());
    }

    private GroupConversation conversation() {
        return new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG);
    }

    private GroupChatTurn runningTurn() {
        return new GroupChatTurn()
                .setId(101L)
                .setConversationId(7L)
                .setStatus(GroupChatConstant.STATUS_RUNNING);
    }

    private GroupChatReplyStep rootStep() {
        return new GroupChatReplyStep()
                .setId(201L)
                .setTurnId(101L)
                .setStepNo(4)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setGroupKey("scene:1")
                .setGroupName("书房")
                .setGroupOrder(1)
                .setItemOrder(4)
                .setOutputMessageId(401L)
                .setStatus(GroupChatConstant.STATUS_RUNNING);
    }

    private GroupChatReplyStep child(int seq) {
        return new GroupChatReplyStep()
                .setId(300L + seq)
                .setParentStepId(201L)
                .setRootStepId(201L)
                .setInteractionType("KP_CLARIFICATION")
                .setInteractionSeq(seq)
                .setStatus(GroupChatConstant.STATUS_COMPLETED);
    }

    private TrpgParticipantService.Participant participant(
            String actorType, Long actorId, Long cardId, String name) {
        return new TrpgParticipantService.Participant(
                new GroupActorRef(actorType, actorId),
                cardId, name, "控制者");
    }

    private TransactionTemplate immediateTransactionTemplate() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return template;
    }
}

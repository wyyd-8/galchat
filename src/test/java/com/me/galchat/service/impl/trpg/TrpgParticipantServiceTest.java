package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrpgParticipantServiceTest {

    @Test
    void listsPlayerBeforeEnabledAgentsUsingStableActorIdentities() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        TrpgParticipantService service = new TrpgParticipantService(
                conversationService, characterMapper);
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setUserWorldId(3L)
                .setMode(GroupChatConstant.MODE_TRPG);
        when(conversationService.listMembers(7L)).thenReturn(List.of(
                member(11L, true),
                member(12L, false),
                member(13L, true)));
        when(characterMapper.selectList(any())).thenReturn(List.of(
                card(101L, null, "PLAYER", "林登", "用户"),
                card(102L, 11L, "BOT", "玛格丽特", "爱丽丝"),
                card(103L, 12L, "BOT", "已禁用", "鲍勃")
                        .setLuckCurrent(null),
                card(104L, 13L, "BOT", "陈默", "夏洛特")));

        List<TrpgParticipantService.Participant> result =
                service.listInvestigators(conversation);

        assertThat(result)
                .extracting(
                        participant -> participant.actor().type(),
                        participant -> participant.actor().id(),
                        TrpgParticipantService.Participant::cardId,
                        TrpgParticipantService.Participant::investigatorName,
                        TrpgParticipantService.Participant::controllerName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.ACTOR_USER, 101L,
                                101L, "林登", "用户"),
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.ACTOR_CHARACTER, 11L,
                                102L, "玛格丽特", "爱丽丝"),
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.ACTOR_CHARACTER, 13L,
                                104L, "陈默", "夏洛特"));
    }

    @Test
    void rejectsRunWhenPlayerLuckIsMissing() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        TrpgParticipantService service = new TrpgParticipantService(
                conversationService, characterMapper);
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setUserWorldId(3L)
                .setMode(GroupChatConstant.MODE_TRPG);
        when(characterMapper.selectList(any())).thenReturn(List.of(
                card(101L, null, "PLAYER", "林登", "用户")
                        .setLuckCurrent(null)));

        assertThatThrownBy(() -> service.listInvestigators(conversation))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("调查员尚未投掷幸运：林登");
    }

    @Test
    void rejectsRunWhenEnabledAgentLuckIsMissing() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        TrpgParticipantService service = new TrpgParticipantService(
                conversationService, characterMapper);
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setUserWorldId(3L)
                .setMode(GroupChatConstant.MODE_TRPG);
        when(conversationService.listMembers(7L)).thenReturn(List.of(
                member(11L, true)));
        when(characterMapper.selectList(any())).thenReturn(List.of(
                card(101L, null, "PLAYER", "林登", "用户"),
                card(102L, 11L, "BOT", "玛格丽特", "爱丽丝")
                        .setLuckCurrent(null)));

        assertThatThrownBy(() -> service.listInvestigators(conversation))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("调查员尚未投掷幸运：玛格丽特");
    }

    private GroupChatMember member(Long actorId, boolean enabled) {
        return new GroupChatMember()
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(actorId)
                .setEnabled(enabled);
    }

    private CocCharacter card(
            Long id, Long participantId, String actorType,
            String name, String playerName) {
        return new CocCharacter()
                .setId(id)
                .setRunId(3L)
                .setParticipantId(participantId)
                .setActorType(actorType)
                .setName(name)
                .setPlayerName(playerName)
                .setLuckCurrent(50);
    }
}

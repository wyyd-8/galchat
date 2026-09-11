package com.me.galchat.groupchat.dice;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupChatMessageMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupDiceMessageRoundAppenderTest {

    @Test
    void mergesRoundsIntoLatestDiceMessage() {
        GroupChatMessageMapper mapper = mock(GroupChatMessageMapper.class);
        GroupDiceMessageRoundAppender appender = appender(mapper);
        when(mapper.selectOne(any())).thenReturn(message(501L));

        appender.appendRounds(7L, 501L, List.of(3, 2, 3));

        ArgumentCaptor<GroupChatMessage> captor =
                ArgumentCaptor.forClass(GroupChatMessage.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getContent())
                .isEqualTo("{\"summaryId\":501,\"roundNos\":[1,2,3]}");
        assertThat(captor.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    void rejectsMissingLatestDiceMessage() {
        GroupChatMessageMapper mapper = mock(GroupChatMessageMapper.class);
        GroupDiceMessageRoundAppender appender = appender(mapper);
        when(mapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(
                () -> appender.appendRounds(7L, 501L, List.of(2)))
                .isInstanceOf(UserRequestException.class);

        verify(mapper, never()).updateById(any(GroupChatMessage.class));
    }

    @Test
    void rejectsSummaryMismatch() {
        GroupChatMessageMapper mapper = mock(GroupChatMessageMapper.class);
        GroupDiceMessageRoundAppender appender = appender(mapper);
        when(mapper.selectOne(any())).thenReturn(message(502L));

        assertThatThrownBy(
                () -> appender.appendRounds(7L, 501L, List.of(2)))
                .isInstanceOf(UserRequestException.class);

        verify(mapper, never()).updateById(any(GroupChatMessage.class));
    }

    private GroupDiceMessageRoundAppender appender(GroupChatMessageMapper mapper) {
        return new GroupDiceMessageRoundAppender(
                mapper,
                new DiceRollMessageCodec(JsonMapper.builder().build()));
    }

    private GroupChatMessage message(Long summaryId) {
        return new GroupChatMessage()
                .setId(91L)
                .setConversationId(7L)
                .setMessageKind(GroupChatConstant.MESSAGE_DICE_ROLL)
                .setContent("{\"summaryId\":" + summaryId
                        + ",\"roundNos\":[1]}");
    }
}

package com.me.galchat.service.impl;

import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupContextSummaryMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrpgExplorationRecordServiceTest {

    @Test
    void replacesCoveredMessagesWithMaximalSummaryAndKeepsGaps() {
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupContextSummaryMapper summaryMapper =
                mock(GroupContextSummaryMapper.class);
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message(1L, "m1"),
                message(2L, "m2"),
                message(3L, "m3"),
                message(4L, "m4"),
                message(5L, "m5")));
        when(summaryMapper.selectList(any())).thenReturn(List.of(
                summary(2L, 4L, "子场景摘要")));
        TrpgExplorationRecordService service =
                new TrpgExplorationRecordService(
                        messageMapper,
                        summaryMapper,
                        new TrpgSummaryIntervalSelector());

        List<TrpgExplorationRecordService.Part> parts =
                service.assemble(7L, 1L, 5L);

        assertThat(parts).hasSize(3);
        assertThat(parts.get(0).messages())
                .extracting(GroupChatMessage::getContent)
                .containsExactly("m1");
        assertThat(parts.get(1).summary().getSummary())
                .isEqualTo("子场景摘要");
        assertThat(parts.get(2).messages())
                .extracting(GroupChatMessage::getContent)
                .containsExactly("m5");
    }

    private GroupChatMessage message(Long sequence, String content) {
        return new GroupChatMessage()
                .setSequenceNo(sequence)
                .setContent(content);
    }

    private GroupContextSummary summary(
            Long start, Long end, String content) {
        return new GroupContextSummary()
                .setId(start)
                .setStartSequence(start)
                .setEndSequence(end)
                .setVersion(1)
                .setSummary(content);
    }
}

package com.me.galchat.service.impl;

import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupContextSummaryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgSceneSummaryServiceTest {

    @Test
    void parentSummaryCoversItsSequenceRangeAndConsumesChildSummaries() {
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupContextSummaryMapper summaryMapper =
                mock(GroupContextSummaryMapper.class);
        TrpgExplorationRecordService recordService =
                mock(TrpgExplorationRecordService.class);
        TrpgSummaryTextGenerator generator =
                mock(TrpgSummaryTextGenerator.class);
        GroupChatMessage first = message(1L, "进入教堂");
        GroupChatMessage last = message(5L, "离开教堂");
        GroupContextSummary child = new GroupContextSummary()
                .setStartSequence(2L)
                .setEndSequence(4L)
                .setSummary("艾琳在阁楼发现了旧账本");
        when(messageMapper.selectCompletedPublicByPlanId(7L, 10L))
                .thenReturn(List.of(first, last));
        when(summaryMapper.selectList(any())).thenReturn(List.of());
        when(recordService.assemble(7L, 1L, 5L)).thenReturn(List.of(
                TrpgExplorationRecordService.Part.messages(List.of(first)),
                TrpgExplorationRecordService.Part.summary(child),
                TrpgExplorationRecordService.Part.messages(List.of(last))));
        when(generator.summarize(any())).thenReturn("教堂调查摘要");
        TrpgSceneSummaryService service = new TrpgSceneSummaryService(
                messageMapper, summaryMapper, recordService, generator);

        GroupContextSummary result = service.summarize(7L, 21L, 10L);

        assertThat(result)
                .extracting(
                        GroupContextSummary::getSceneId,
                        GroupContextSummary::getScenePlanId,
                        GroupContextSummary::getStartSequence,
                        GroupContextSummary::getEndSequence,
                        GroupContextSummary::getSummary)
                .containsExactly(
                        21L, 10L, 1L, 5L, "教堂调查摘要");
        ArgumentCaptor<String> history = ArgumentCaptor.forClass(
                String.class);
        verify(generator).summarize(history.capture());
        assertThat(history.getValue())
                .contains("进入教堂")
                .contains("艾琳在阁楼发现了旧账本")
                .contains("离开教堂");
        verify(summaryMapper).insert(result);
    }

    private GroupChatMessage message(long sequence, String content) {
        return new GroupChatMessage()
                .setSequenceNo(sequence)
                .setSpeakerType("character")
                .setContent(content);
    }
}

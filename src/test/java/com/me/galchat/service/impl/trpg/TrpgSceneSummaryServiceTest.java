package com.me.galchat.service.impl.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.groupchat.material.MaterialMessageCodec;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupContextSummaryMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        GroupReplyPlanMapper planMapper =
                mock(GroupReplyPlanMapper.class);
        TrpgSceneParticipantService participantService =
                mock(TrpgSceneParticipantService.class);
        MaterialMessageCodec materialMessageCodec =
                mock(MaterialMessageCodec.class);
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
        when(planMapper.selectById(10L)).thenReturn(
                new GroupReplyPlan().setId(10L));
        TrpgSceneSummaryService service = new TrpgSceneSummaryService(
                messageMapper, summaryMapper, recordService, generator,
                conversationMapper, planMapper, participantService,
                materialMessageCodec);

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
        verify(generator, never()).summarizeChildClues(any());
    }

    @Test
    void childSummaryUsesStructuredSceneDataAndOnlyKpClueEvidence() {
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupContextSummaryMapper summaryMapper =
                mock(GroupContextSummaryMapper.class);
        TrpgExplorationRecordService recordService =
                mock(TrpgExplorationRecordService.class);
        TrpgSummaryTextGenerator generator =
                mock(TrpgSummaryTextGenerator.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        GroupReplyPlanMapper planMapper =
                mock(GroupReplyPlanMapper.class);
        TrpgSceneParticipantService participantService =
                mock(TrpgSceneParticipantService.class);
        MaterialMessageCodec materialMessageCodec =
                mock(MaterialMessageCodec.class);
        GroupReplyPlan childPlan = new GroupReplyPlan()
                .setId(31L)
                .setConversationId(7L)
                .setParentPlanId(20L);
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setModuleId(5L)
                .setGameDayNo(2)
                .setGameTimePeriod("AFTERNOON");
        GroupChatMessage investigatorIntent = message(
                11L, GroupChatConstant.ACTOR_CHARACTER,
                GroupChatConstant.MESSAGE_DIALOGUE,
                "我赶去钟楼和其他人会合");
        GroupChatMessage kpClue = message(
                12L, GroupChatConstant.ACTOR_KP,
                GroupChatConstant.MESSAGE_NARRATION,
                "钟楼门锁上有新鲜划痕，地面留着黑色羽毛");
        GroupChatMessage dice = message(
                13L, GroupChatConstant.ACTOR_KP,
                GroupChatConstant.MESSAGE_DICE_ROLL,
                "侦查检定成功");
        GroupChatMessage material = message(
                14L, GroupChatConstant.ACTOR_KP,
                GroupChatConstant.MESSAGE_MATERIAL,
                "{\"title\":\"旧通缉令\",\"description\":\"落款是三年前\"}");
        when(messageMapper.selectCompletedPublicByPlanId(7L, 31L))
                .thenReturn(List.of(
                        investigatorIntent, kpClue, dice, material));
        when(summaryMapper.selectList(any())).thenReturn(List.of());
        when(planMapper.selectById(31L)).thenReturn(childPlan);
        when(conversationMapper.selectById(7L)).thenReturn(conversation);
        when(participantService.summaryState(conversation, childPlan))
                .thenReturn(new TrpgSceneParticipantService.SceneSummaryState(
                        "第一天场景 - 钟楼",
                        List.of("艾琳", "亨利")));
        when(materialMessageCodec.toAgentText(material.getContent()))
                .thenReturn("<shown-material title=\"旧通缉令\">\n"
                        + "落款是三年前\n</shown-material>");
        when(generator.summarizeChildClues(any())).thenReturn(
                "- 钟楼门锁上有新鲜划痕，地面留着黑色羽毛\n"
                        + "- 旧通缉令的落款是三年前");
        when(generator.summarize(any())).thenReturn("旧的动作摘要");
        TrpgSceneSummaryService service = new TrpgSceneSummaryService(
                messageMapper, summaryMapper, recordService, generator,
                conversationMapper, planMapper, participantService,
                materialMessageCodec);

        GroupContextSummary result = service.summarize(7L, 21L, 31L);

        assertThat(result.getSummary()).isEqualTo("""
                参与者：艾琳、亨利
                时间：第二天 - 下午
                地点：第一天场景 - 钟楼
                可用线索：
                - 钟楼门锁上有新鲜划痕，地面留着黑色羽毛
                - 旧通缉令的落款是三年前""");
        ArgumentCaptor<String> evidence = ArgumentCaptor.forClass(
                String.class);
        verify(generator).summarizeChildClues(evidence.capture());
        assertThat(evidence.getValue())
                .contains("钟楼门锁上有新鲜划痕")
                .contains("旧通缉令")
                .doesNotContain("赶去钟楼")
                .doesNotContain("侦查检定成功");
        verify(generator, never()).summarize(any());
        verify(recordService, never()).assemble(
                any(), anyLong(), anyLong());
        verify(summaryMapper).insert(result);
    }

    private GroupChatMessage message(long sequence, String content) {
        return message(sequence, GroupChatConstant.ACTOR_CHARACTER,
                GroupChatConstant.MESSAGE_DIALOGUE, content);
    }

    private GroupChatMessage message(
            long sequence,
            String speakerType,
            String messageKind,
            String content) {
        return new GroupChatMessage()
                .setSequenceNo(sequence)
                .setSpeakerType(speakerType)
                .setMessageKind(messageKind)
                .setContent(content);
    }
}

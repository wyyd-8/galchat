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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgSceneSummaryServiceTest {

    @Test
    void finalParentSummaryExtendsThroughThePostCombatReply() {
        var messages = mock(GroupChatMessageMapper.class);
        var summaries = mock(GroupContextSummaryMapper.class);
        var records = mock(TrpgExplorationRecordService.class);
        var generator = mock(TrpgSummaryTextGenerator.class);
        when(messages.selectCompletedPublicByPlanId(7L, 10L)).thenReturn(List.of(message(1L, "进入教堂"), message(5L, "遭遇敌人")));
        when(records.assemble(7L, 1L, 9L)).thenReturn(List.of(
                TrpgExplorationRecordService.Part.messages(List.of(message(9L, "战斗结束，众人带着伤痕离开教堂")))));
        when(generator.summarize(any())).thenReturn("众人完成调查并离开教堂");
        var service = new TrpgSceneSummaryService(messages, summaries, records, generator,
                mock(GroupConversationMapper.class), mock(GroupReplyPlanMapper.class),
                mock(TrpgSceneParticipantService.class), mock(MaterialMessageCodec.class));
        var result = service.summarizeThrough(7L, 21L, 10L, 9L);
        assertThat(result.getEndSequence()).isEqualTo(9L);
        var text = ArgumentCaptor.forClass(String.class);
        verify(generator).summarize(text.capture());
        assertThat(text.getValue()).contains("战斗结束，众人带着伤痕离开教堂");
    }

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
                .setSummary("可用线索：艾琳在阁楼发现了旧账本\n"
                        + "剧情经过：艾琳向守卫借走账本，约定次日归还");
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
                .contains("剧情经过：艾琳向守卫借走账本，约定次日归还")
                .contains("离开教堂");
        verify(summaryMapper).insert(result);
        verify(generator, never()).summarizeChildClues(any());
    }

    @ParameterizedTest
    @MethodSource("plotResults")
    void childSummaryKeepsPlotSeparateAndExcludesInvestigatorIntent(
            String plotResult, String expectedPlot) {
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupContextSummaryMapper summaryMapper =
                mock(GroupContextSummaryMapper.class);
        TrpgExplorationRecordService recordService =
                mock(TrpgExplorationRecordService.class);
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        TrpgSummaryTextGenerator generator =
                new TrpgSummaryTextGenerator(client);
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
        GroupChatMessage kpEvent = message(
                17L, GroupChatConstant.ACTOR_KP,
                GroupChatConstant.MESSAGE_NARRATION,
                "艾琳试图说服守卫但失败了；守卫仍将旧通缉令交给亨利，约定次日归还。");
        GroupChatMessage userIntent = message(
                18L, GroupChatConstant.ACTOR_USER,
                GroupChatConstant.MESSAGE_DIALOGUE,
                "我要去仓库找医生");
        when(messageMapper.selectCompletedPublicByPlanId(7L, 31L))
                .thenReturn(List.of(
                        investigatorIntent, kpClue, dice, material,
                        kpEvent, userIntent));
        GroupChatMessage combatResult = message(
                15L, GroupChatConstant.ACTOR_KP,
                GroupChatConstant.MESSAGE_COMBAT_RESULT,
                "两人逃出暗室，俘虏获救"); // Combat results have no turnId.
        GroupChatMessage postCombat = message(
                16L, GroupChatConstant.ACTOR_KP,
                GroupChatConstant.MESSAGE_DIALOGUE,
                "两人重伤，装备遗留在棚屋；沃尔顿仍在画家营地")
                .setTurnId(99L); // A separate POST_COMBAT plan.
        when(messageMapper.selectList(any())).thenReturn(List.of(
                investigatorIntent, kpClue, dice, material,
                combatResult, postCombat, kpEvent, userIntent));
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
        when(client.prompt(any(Prompt.class)).call().content()).thenReturn(
                "- 钟楼门锁上有新鲜划痕，地面留着黑色羽毛\n"
                        + "- 旧通缉令的落款是三年前", plotResult);
        clearInvocations(client);
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
                - 旧通缉令的落款是三年前
                剧情经过：
                """ + expectedPlot);
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(
                Prompt.class);
        verify(client, times(2)).prompt(prompts.capture());
        assertThat(prompts.getAllValues()).allSatisfy(prompt ->
                assertThat(prompt.getUserMessage().getText())
                .contains("钟楼门锁上有新鲜划痕")
                .contains("旧通缉令")
                .contains("艾琳试图说服守卫但失败了")
                .contains("约定次日归还")
                .contains("两人逃出暗室，俘虏获救")
                .contains("两人重伤，装备遗留在棚屋；沃尔顿仍在画家营地")
                .doesNotContain("赶去钟楼")
                .doesNotContain("我要去仓库")
                .doesNotContain("侦查检定成功"));
        verify(recordService, never()).assemble(
                any(), anyLong(), anyLong());
        verify(summaryMapper).insert(result);
    }

    private static Stream<Arguments> plotResults() {
        return Stream.of(
                Arguments.of("  - 艾琳说服守卫失败；亨利借走通缉令，约定次日归还。\n",
                        "- 艾琳说服守卫失败；亨利借走通缉令，约定次日归还。"),
                Arguments.of(null, "无"),
                Arguments.of("", "无"),
                Arguments.of("  \n", "无"));
    }

    @Test
    void childWithOnlyInvestigatorIntentDoesNotAskModelToInventPlot() {
        GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        GroupContextSummaryMapper summaryMapper = mock(GroupContextSummaryMapper.class);
        TrpgExplorationRecordService recordService = mock(TrpgExplorationRecordService.class);
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        TrpgSceneParticipantService participantService = mock(TrpgSceneParticipantService.class);
        ChatClient client = mock(ChatClient.class);
        GroupConversation conversation = new GroupConversation().setId(7L);
        GroupReplyPlan childPlan = new GroupReplyPlan().setId(31L).setParentPlanId(20L);
        when(messageMapper.selectCompletedPublicByPlanId(7L, 31L)).thenReturn(List.of(
                message(11L, "我要去仓库")));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message(11L, "我要去仓库")));
        when(summaryMapper.selectList(any())).thenReturn(List.of());
        when(planMapper.selectById(31L)).thenReturn(childPlan);
        when(conversationMapper.selectById(7L)).thenReturn(conversation);
        when(participantService.summaryState(conversation, childPlan)).thenReturn(
                new TrpgSceneParticipantService.SceneSummaryState("钟楼", List.of("艾琳")));
        TrpgSceneSummaryService service = new TrpgSceneSummaryService(
                messageMapper, summaryMapper, recordService, new TrpgSummaryTextGenerator(client),
                conversationMapper, planMapper, participantService, mock(MaterialMessageCodec.class));

        GroupContextSummary result = service.summarize(7L, 21L, 31L);

        assertThat(result.getSummary())
                .contains("参与者：艾琳\n", "地点：钟楼\n")
                .endsWith("可用线索：\n无\n剧情经过：\n无")
                .doesNotContain("仓库");
        verify(client, never()).prompt(any(Prompt.class));
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

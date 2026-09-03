package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.dice.GroupDiceMessageFormatter;
import com.me.galchat.groupchat.material.MaterialMessageCodec;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.groupchat.tool.GroupToolHistoryAssembler;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrpgExplorationContextAssemblerTest {

    @Test
    void emitsSummaryInSequenceBetweenRawMessageGaps() {
        TrpgExplorationRecordService recordService =
                mock(TrpgExplorationRecordService.class);
        GroupContextAssembler groupAssembler =
                mock(GroupContextAssembler.class);
        GroupConversation conversation =
                new GroupConversation().setId(7L);
        GroupActorRef actor = new GroupActorRef("kp", null);
        GroupChatMessage before = new GroupChatMessage()
                .setSequenceNo(1L).setContent("before");
        GroupChatMessage after = new GroupChatMessage()
                .setSequenceNo(5L).setContent("after");
        GroupContextSummary summary = new GroupContextSummary()
                .setStartSequence(2L)
                .setEndSequence(4L)
                .setSummary("阁楼摘要");
        when(recordService.assemble(7L, 1L, Long.MAX_VALUE))
                .thenReturn(List.of(
                        TrpgExplorationRecordService.Part.messages(
                                List.of(before)),
                        TrpgExplorationRecordService.Part.summary(summary),
                        TrpgExplorationRecordService.Part.messages(
                                List.of(after))));
        when(groupAssembler.assembleMessages(
                any(), any(), any(), any())).thenAnswer(invocation -> {
            List<GroupChatMessage> messages = invocation.getArgument(2);
            return List.of(new UserMessage(
                    messages.getFirst().getContent()));
        });
        TrpgExplorationContextAssembler assembler =
                new TrpgExplorationContextAssembler(
                        recordService, groupAssembler,
                        mock(TrpgParticipantService.class));

        assertThat(assembler.assemble(conversation, actor))
                .extracting(message -> message.getText())
                .containsExactly(
                        "before",
                        "<context-summary start=\"2\" end=\"4\" status=\"completed\">\n"
                                + "【已结束场景记录】\n"
                                + "这个场景已经结束，不再是当前场景。\n\n"
                                + "处理本记录时：\n"
                                + "1. 仅将其中已经公开的事实和获得的线索作为历史信息。\n"
                                + "2. 不得继续、重新引入或补写这个场景。\n"
                                + "3. 不得把其中的地点和参与者当作当前地点、当前参与者。\n"
                                + "4. 生成下一条回复时，必须以 <current-scene-runtime> 指定的场景和参与者为准。\n\n"
                                + "阁楼摘要\n</context-summary>",
                        "after");
    }

    @Test
    void boundedAssemblyUsesTheSameHistoryShapeAsKpContext() {
        TrpgExplorationRecordService recordService =
                mock(TrpgExplorationRecordService.class);
        GroupContextAssembler groupAssembler =
                mock(GroupContextAssembler.class);
        GroupConversation conversation =
                new GroupConversation().setId(7L);
        GroupActorRef kp = new GroupActorRef(
                GroupChatConstant.ACTOR_KP, null);
        GroupContextSummary summary = new GroupContextSummary()
                .setStartSequence(1L)
                .setEndSequence(8L)
                .setSummary("压缩后的场景记录");
        when(recordService.assemble(7L, 1L, 12L)).thenReturn(List.of(
                TrpgExplorationRecordService.Part.summary(summary)));
        TrpgExplorationContextAssembler assembler =
                new TrpgExplorationContextAssembler(
                        recordService, groupAssembler,
                        mock(TrpgParticipantService.class));

        assertThat(assembler.assemble(conversation, kp, 12L))
                .extracting(message -> message.getText())
                .singleElement()
                .asString()
                .contains("压缩后的场景记录");
    }

    @Test
    void rendersTrpgControllerMessagesWithCanonicalInvestigatorSpeakers() {
        TrpgExplorationRecordService recordService =
                mock(TrpgExplorationRecordService.class);
        TrpgParticipantService participantService =
                mock(TrpgParticipantService.class);
        GroupToolHistoryAssembler toolHistoryAssembler =
                mock(GroupToolHistoryAssembler.class);
        UserCharacterInfoMapper userCharacterInfoMapper =
                mock(UserCharacterInfoMapper.class);
        GroupContextAssembler groupAssembler = new GroupContextAssembler(
                mock(GroupChatMessageMapper.class),
                mock(GroupConversationService.class),
                mock(ChatServiceImpl.class),
                userCharacterInfoMapper,
                toolHistoryAssembler,
                mock(GroupDiceMessageFormatter.class),
                new MaterialMessageCodec(
                        tools.jackson.databind.json.JsonMapper.builder()
                                .build()));
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setUserWorldId(5L)
                .setMode(GroupChatConstant.MODE_TRPG);
        GroupActorRef kp = new GroupActorRef(
                GroupChatConstant.ACTOR_KP, null);
        GroupChatMessage currentUserAction = message(
                GroupChatConstant.ACTOR_USER, 71L,
                "我也跟上去。");
        GroupChatMessage agentAction = message(
                GroupChatConstant.ACTOR_CHARACTER, 9L,
                "我留在岔口。");
        GroupChatMessage legacyUserAction = message(
                GroupChatConstant.ACTOR_USER, null,
                "我检查门锁。");
        when(recordService.assemble(7L, 1L, Long.MAX_VALUE))
                .thenReturn(List.of(
                        TrpgExplorationRecordService.Part.messages(
                                List.of(currentUserAction, agentAction,
                                        legacyUserAction))));
        when(participantService.listInvestigators(conversation))
                .thenReturn(List.of(
                        new TrpgParticipantService.Participant(
                                new GroupActorRef(
                                        GroupChatConstant.ACTOR_USER, 71L),
                                71L, "林恩", "用户"),
                        new TrpgParticipantService.Participant(
                                new GroupActorRef(
                                        GroupChatConstant.ACTOR_CHARACTER,
                                        9L),
                                72L, "艾琳", "Agent艾琳")));
        when(userCharacterInfoMapper.selectList(any()))
                .thenReturn(List.of());
        when(toolHistoryAssembler.beforeMessages(any(), any()))
                .thenReturn(Map.of());
        TrpgExplorationContextAssembler assembler =
                new TrpgExplorationContextAssembler(
                        recordService, groupAssembler,
                        participantService);

        assertThat(assembler.assemble(conversation, kp))
                .extracting(message -> message.getText())
                .containsExactly(
                        "<message speaker=\"林恩\" actor=\"user:71\">\n"
                                + "我也跟上去。\n</message>",
                        "<message speaker=\"艾琳\" actor=\"character:9\">\n"
                                + "我留在岔口。\n</message>",
                        "<message speaker=\"林恩\" actor=\"user\">\n"
                                + "我检查门锁。\n</message>");
    }

    private GroupChatMessage message(
            String speakerType, Long speakerId, String content) {
        return new GroupChatMessage()
                .setSpeakerType(speakerType)
                .setSpeakerId(speakerId)
                .setMessageKind(GroupChatConstant.MESSAGE_DIALOGUE)
                .setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setContent(content);
    }
}

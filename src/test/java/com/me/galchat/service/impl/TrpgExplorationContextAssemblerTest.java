package com.me.galchat.service.impl;

import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

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
                any(), any(), any())).thenAnswer(invocation -> {
            List<GroupChatMessage> messages = invocation.getArgument(2);
            return List.of(new UserMessage(
                    messages.getFirst().getContent()));
        });
        TrpgExplorationContextAssembler assembler =
                new TrpgExplorationContextAssembler(
                        recordService, groupAssembler);

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
}

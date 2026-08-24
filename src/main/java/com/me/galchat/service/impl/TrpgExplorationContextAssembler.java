package com.me.galchat.service.impl;

import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TrpgExplorationContextAssembler {

    private final TrpgExplorationRecordService recordService;
    private final GroupContextAssembler groupContextAssembler;

    public List<Message> assemble(
            GroupConversation conversation,
            GroupActorRef currentActor) {
        List<Message> result = new ArrayList<>();
        for (TrpgExplorationRecordService.Part part :
                recordService.assemble(
                        conversation.getId(), 1L, Long.MAX_VALUE)) {
            if (part.isSummary()) {
                result.add(summaryMessage(part.summary()));
            } else {
                result.addAll(groupContextAssembler.assembleMessages(
                        conversation, currentActor, part.messages()));
            }
        }
        return List.copyOf(result);
    }

    private Message summaryMessage(GroupContextSummary summary) {
        return new UserMessage("<context-summary start=\""
                + summary.getStartSequence() + "\" end=\""
                + summary.getEndSequence()
                + "\" status=\"completed\">\n"
                + """
                【已结束场景记录】
                这个场景已经结束，不再是当前场景。

                处理本记录时：
                1. 仅将其中已经公开的事实和获得的线索作为历史信息。
                2. 不得继续、重新引入或补写这个场景。
                3. 不得把其中的地点和参与者当作当前地点、当前参与者。
                4. 生成下一条回复时，必须以 <current-scene-runtime> 指定的场景和参与者为准。

                """ + summary.getSummary()
                + "\n</context-summary>");
    }
}

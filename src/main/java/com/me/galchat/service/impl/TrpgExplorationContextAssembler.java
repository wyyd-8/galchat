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
                + summary.getEndSequence() + "\">\n"
                + summary.getSummary()
                + "\n</context-summary>");
    }
}

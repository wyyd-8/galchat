package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TrpgExplorationContextAssembler {

    private final TrpgExplorationRecordService recordService;
    private final GroupContextAssembler groupContextAssembler;
    private final TrpgParticipantService participantService;

    public List<Message> assemble(
            GroupConversation conversation,
            GroupActorRef currentActor) {
        return assemble(conversation, currentActor, Long.MAX_VALUE);
    }

    public List<Message> assemble(
            GroupConversation conversation,
            GroupActorRef currentActor,
            long endSequence) {
        List<Message> result = new ArrayList<>();
        Map<GroupActorRef, String> investigatorNames =
                investigatorNames(conversation);
        for (TrpgExplorationRecordService.Part part :
                recordService.assemble(
                        conversation.getId(), 1L, endSequence)) {
            if (part.isSummary()) {
                result.add(summaryMessage(part.summary()));
            } else {
                result.addAll(groupContextAssembler.assembleMessages(
                        conversation, currentActor, part.messages(),
                        investigatorNames));
            }
        }
        return List.copyOf(result);
    }

    private Map<GroupActorRef, String> investigatorNames(
            GroupConversation conversation) {
        List<TrpgParticipantService.Participant> participants =
                participantService.listInvestigators(conversation);
        if (participants == null || participants.isEmpty()) {
            return Map.of();
        }
        Map<GroupActorRef, String> result = new LinkedHashMap<>();
        for (TrpgParticipantService.Participant participant :
                participants) {
            if (participant == null || participant.actor() == null
                    || !StringUtils.hasText(
                    participant.investigatorName())) {
                continue;
            }
            String name = participant.investigatorName().trim();
            result.putIfAbsent(participant.actor(), name);
            if (GroupChatConstant.ACTOR_USER.equals(
                    participant.actor().type())) {
                result.putIfAbsent(new GroupActorRef(
                        GroupChatConstant.ACTOR_USER, null), name);
            }
        }
        return Map.copyOf(result);
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

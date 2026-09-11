package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.decision.GroupAgentDecisionStore;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TrpgAgentDecisionContextAssembler {

    private final GroupAgentDecisionStore decisionStore;

    public String format(
            GroupConversation conversation,
            GroupActionSpec action) {
        List<String> decisions =
                decisionStore.completedContentForActorContext(
                        conversation.getId(),
                        action.actionType(),
                        action.actorId(),
                        action.groupKey());
        if (decisions.isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder(
                "<private-decision-history>\n");
        for (String decision : decisions) {
            context.append("- ").append(decision).append('\n');
        }
        return context.append("</private-decision-history>")
                .toString();
    }
}

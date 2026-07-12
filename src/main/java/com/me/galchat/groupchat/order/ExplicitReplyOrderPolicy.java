package com.me.galchat.groupchat.order;

import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.constant.GroupChatConstant;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ExplicitReplyOrderPolicy implements ReplyOrderPolicy {

    @Override
    public String name() {
        return "explicit";
    }

    @Override
    public List<ReplyPlanItem> plan(GroupChatRequestDTO request, List<GroupChatMember> members) {
        if (request.getReplyPlan() == null) {
            return List.of();
        }
        return request.getReplyPlan().stream()
                .map(target -> new ReplyPlanItem(target.getSpeakerType() == null
                                ? GroupChatConstant.ACTOR_CHARACTER : target.getSpeakerType().trim().toLowerCase(),
                        target.getSpeakerId(),
                        Boolean.TRUE.equals(target.getForce())))
                .toList();
    }
}

package com.me.galchat.groupchat.order;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.exception.UserRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class GroupReplyOrderController {

    private final ExplicitReplyOrderPolicy explicitPolicy;
    private final ListReplyOrderPolicy listPolicy;

    public PlannedReplies plan(GroupChatRequestDTO request, List<GroupChatMember> members) {
        ReplyOrderPolicy policy = request.getReplyPlan() == null ? listPolicy : explicitPolicy;
        List<ReplyPlanItem> items = policy.plan(request, members);
        if (items.size() > GroupChatConstant.MAX_REPLY_STEPS) {
            throw new UserRequestException("单轮回复角色数量不能超过" + GroupChatConstant.MAX_REPLY_STEPS);
        }
        Set<String> speakers = new HashSet<>();
        for (ReplyPlanItem item : items) {
            String key = item.speakerType() + ":" + item.speakerId();
            if (!speakers.add(key)) {
                throw new UserRequestException("同一角色不能在显式回复顺序中重复出现");
            }
        }
        return new PlannedReplies(policy.name(), items);
    }

    public record PlannedReplies(String policy, List<ReplyPlanItem> items) {
    }
}

package com.me.galchat.groupchat.order;

import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.po.GroupChatMember;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ListReplyOrderPolicy implements ReplyOrderPolicy {

    @Override
    public String name() {
        return "list";
    }

    @Override
    public List<ReplyPlanItem> plan(GroupChatRequestDTO request, List<GroupChatMember> members) {
        return members.stream()
                .filter(member -> Boolean.TRUE.equals(member.getEnabled()))
                .map(member -> new ReplyPlanItem(member.getActorType(), member.getActorId(), false))
                .toList();
    }
}

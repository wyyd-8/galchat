package com.me.galchat.groupchat.order;

import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.po.GroupChatMember;

import java.util.List;

public interface ReplyOrderPolicy {
    String name();

    List<ReplyPlanItem> plan(GroupChatRequestDTO request, List<GroupChatMember> members);
}

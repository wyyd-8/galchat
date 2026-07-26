package com.me.galchat.groupchat.runtime;

import com.me.galchat.domain.po.GroupReplyPlanItem;

import java.util.List;

public record GroupReplyPlanSelection(
        String source,
        Long contextId,
        String groupKey,
        String groupName,
        Integer groupOrder,
        List<GroupReplyPlanItem> items
) {
}

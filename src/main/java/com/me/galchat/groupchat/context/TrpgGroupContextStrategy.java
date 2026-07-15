package com.me.galchat.groupchat.context;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import org.springframework.stereotype.Service;

@Service
public class TrpgGroupContextStrategy implements GroupContextStrategy {

    @Override
    public boolean supports(String mode) {
        return GroupChatConstant.MODE_TRPG.equals(mode);
    }

    @Override
    public void compactIfNeeded(GroupConversation conversation) {
        // TRPG 将按场景边界压缩；第一版不复用普通群聊的滚动压缩。
    }

    @Override
    public GroupContextSummary latestSummary(Long conversationId) {
        return null;
    }
}

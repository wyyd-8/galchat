package com.me.galchat.groupchat.context;

import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GroupContextStrategyRouter {

    private final List<GroupContextStrategy> strategies;

    public void compactIfNeeded(GroupConversation conversation) {
        strategy(conversation).compactIfNeeded(conversation);
    }

    public GroupContextSummary latestSummary(GroupConversation conversation) {
        return strategy(conversation).latestSummary(conversation.getId());
    }

    private GroupContextStrategy strategy(GroupConversation conversation) {
        return strategies.stream()
                .filter(candidate -> candidate.supports(conversation.getMode()))
                .findFirst()
                .orElseThrow(() -> new UserRequestException("未配置群聊上下文策略: " + conversation.getMode()));
    }
}

package com.me.galchat.groupchat.runtime.chat;

import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.context.GroupTopicService;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupContextMaterial;
import com.me.galchat.groupchat.runtime.GroupContextPolicy;
import com.me.galchat.service.impl.GroupContextAssembler;
import com.me.galchat.vector.GroupTopicVectorService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChatGroupContextPolicy implements GroupContextPolicy {

    private static final int RETRIEVAL_QUERY_MESSAGE_COUNT = 3;

    private final GroupTopicService topicService;
    private final GroupTopicVectorService vectorService;
    private final GroupContextAssembler contextAssembler;

    public ChatGroupContextPolicy(GroupTopicService topicService,
                                  GroupTopicVectorService vectorService,
                                  GroupContextAssembler contextAssembler) {
        this.topicService = topicService;
        this.vectorService = vectorService;
        this.contextAssembler = contextAssembler;
    }

    @Override
    public void onTurnStarted(GroupConversation conversation, GroupChatMessage userMessage) {
        topicService.onTurnStarted(conversation, userMessage);
    }

    @Override
    public GroupContextMaterial load(GroupConversation conversation, GroupActionSpec action) {
        long windowStart = topicService.windowStartSequence(conversation);
        List<Message> context = contextAssembler.assembleContextFrom(
                conversation, action.actor(), windowStart);
        String memory = vectorService.search(conversation.getId(), windowStart, retrievalQuery(context));
        if (!StringUtils.hasText(memory)) {
            return new GroupContextMaterial(context);
        }

        List<Message> enriched = new ArrayList<>(context.size() + 1);
        enriched.add(new UserMessage(
                "<retrieved-group-memory>\n" + memory.trim() + "\n</retrieved-group-memory>"));
        enriched.addAll(context);
        return new GroupContextMaterial(enriched);
    }

    private String retrievalQuery(List<Message> context) {
        int start = Math.max(0, context.size() - RETRIEVAL_QUERY_MESSAGE_COUNT);
        return context.subList(start, context.size()).stream()
                .map(Message::getText)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }
}

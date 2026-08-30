package com.me.galchat.groupchat.runtime;

import com.me.galchat.groupchat.tool.GroupToolCallStore;
import com.me.galchat.groupchat.tool.RecordingGroupToolCallingManager;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class GroupChatClientFactory {

    private final ToolCallingManager toolCallingManager;
    private final GroupToolCallStore groupToolCallStore;
    private final TransactionTemplate transactionTemplate;

    public GroupChatClientFactory(
            ToolCallingManager toolCallingManager,
            GroupToolCallStore groupToolCallStore,
            TransactionTemplate transactionTemplate) {
        this.toolCallingManager = toolCallingManager;
        this.groupToolCallStore = groupToolCallStore;
        this.transactionTemplate = transactionTemplate;
    }

    public ChatClient create(ChatClient.Builder builder) {
        RecordingGroupToolCallingManager recordingManager =
                new RecordingGroupToolCallingManager(
                        toolCallingManager,
                        groupToolCallStore,
                        transactionTemplate);
        ToolCallingAdvisor toolCallingAdvisor =
                ToolCallingAdvisor.builder()
                        .toolCallingManager(recordingManager)
                        .build();
        return builder
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultAdvisors(toolCallingAdvisor)
                .build();
    }
}

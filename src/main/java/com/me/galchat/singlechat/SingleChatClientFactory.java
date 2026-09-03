package com.me.galchat.singlechat;

import com.me.galchat.memory.TopicAwareMessageChatMemoryAdvisor;
import com.me.galchat.memory.TopicBoundaryService;
import com.me.galchat.memory.UserChatMemory;
import com.me.galchat.tool.RecordingToolCallingManager;
import com.me.galchat.tool.TrpgRunMemoryTools;
import com.me.galchat.tool.UserCharacterFavorTools;
import com.me.galchat.tool.UserCharacterInfoTools;
import com.me.galchat.tool.VectorTools;
import com.me.galchat.vector.MutiSearchService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class SingleChatClientFactory {

    private final TopicBoundaryService topicBoundaryService;
    private final UserChatMemory chatMemory;
    private final MutiSearchService mutiSearchService;
    private final VectorTools vectorTools;
    private final UserCharacterFavorTools userCharacterFavorTools;
    private final UserCharacterInfoTools userCharacterInfoTools;
    private final TrpgRunMemoryTools trpgRunMemoryTools;
    private final ToolCallingManager toolCallingManager;

    public SingleChatClientFactory(
            TopicBoundaryService topicBoundaryService,
            @Qualifier("defaultChatMemory") UserChatMemory chatMemory,
            MutiSearchService mutiSearchService,
            VectorTools vectorTools,
            UserCharacterFavorTools userCharacterFavorTools,
            UserCharacterInfoTools userCharacterInfoTools,
            TrpgRunMemoryTools trpgRunMemoryTools,
            ToolCallingManager toolCallingManager) {
        this.topicBoundaryService = topicBoundaryService;
        this.chatMemory = chatMemory;
        this.mutiSearchService = mutiSearchService;
        this.vectorTools = vectorTools;
        this.userCharacterFavorTools = userCharacterFavorTools;
        this.userCharacterInfoTools = userCharacterInfoTools;
        this.trpgRunMemoryTools = trpgRunMemoryTools;
        this.toolCallingManager = toolCallingManager;
    }

    public ChatClient create(ChatClient.Builder builder) {
        ToolCallingAdvisor toolAdvisor = ToolCallingAdvisor.builder()
                .toolCallingManager(new RecordingToolCallingManager(toolCallingManager, chatMemory))
                .build();
        return builder
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultAdvisors(TopicAwareMessageChatMemoryAdvisor.builder(
                        chatMemory, topicBoundaryService, mutiSearchService).build())
                .defaultAdvisors(toolAdvisor)
                .defaultTools(vectorTools, userCharacterFavorTools,
                        userCharacterInfoTools, trpgRunMemoryTools)
                .build();
    }
}

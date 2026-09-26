package com.me.galchat.groupchat.context;

import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.memory.TopicCompressionPrompts;
import com.me.galchat.memory.TopicSplitDecision;
import com.me.galchat.memory.TopicModelCall;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ModelGroupTopicClassifier implements GroupTopicClassifier {

    private final ChatClient classifierClient;

    public ModelGroupTopicClassifier(@Qualifier("topicClient") ChatClient classifierClient) {
        this.classifierClient = classifierClient;
    }

    @Override
    public int boundaryScore(List<GroupChatMessage> currentTopic, GroupChatMessage userMessage) {
        String prompt = """
                当前群聊话题：
                %s

                新的用户消息：
                %s
                """.formatted(format(currentTopic), format(userMessage));
        String result = TopicModelCall.read(() -> classifierClient.prompt()
                .system(TopicCompressionPrompts.SCORE)
                .user(prompt)
                .call()
                .content(), TopicModelCall.SCORE_TIMEOUT);
        return TopicSplitDecision.parseScore(result);
    }

    private String format(List<GroupChatMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (GroupChatMessage message : messages) {
            builder.append(format(message)).append('\n');
        }
        return builder.toString();
    }

    private String format(GroupChatMessage message) {
        String speaker = message.getSpeakerType()
                + (message.getSpeakerId() == null ? "" : ":" + message.getSpeakerId());
        return "[" + speaker + "] " + message.getContent();
    }
}

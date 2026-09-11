package com.me.galchat.groupchat.context;

import com.me.galchat.domain.po.GroupChatMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ModelGroupTopicClassifier implements GroupTopicClassifier {

    private final ChatClient classifierClient;

    public ModelGroupTopicClassifier(@Qualifier("groupNonThinkingChatClient") ChatClient classifierClient) {
        this.classifierClient = classifierClient;
    }

    @Override
    public boolean isSameTopic(List<GroupChatMessage> currentTopic, GroupChatMessage userMessage) {
        String prompt = """
                当前群聊话题：
                %s

                新的用户消息：
                %s
                """.formatted(format(currentTopic), format(userMessage));
        String result = classifierClient.prompt()
                .system("""
                        判断新的用户消息是否延续当前群聊话题。
                        延续同一目标、问题或叙事焦点时只输出 true；
                        明显转向新的独立话题时只输出 false。无法确定时输出 true。
                        不要输出解释。
                        """)
                .user(prompt)
                .call()
                .content();
        return result != null && result.trim().equalsIgnoreCase("true");
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

package com.me.galchat.groupchat.runtime.chat;

import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupAgentPolicy;
import com.me.galchat.groupchat.runtime.GroupContextMaterial;
import com.me.galchat.groupchat.runtime.GroupModelInvocation;
import com.me.galchat.service.impl.GroupContextAssembler;
import com.me.galchat.tool.UserCharacterFavorTools;
import com.me.galchat.tool.VectorTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChatGroupAgentPolicy implements GroupAgentPolicy {

    private final ChatClient chatClient;
    private final GroupContextAssembler contextAssembler;
    private final VectorTools vectorTools;
    private final UserCharacterFavorTools favorTools;

    public ChatGroupAgentPolicy(@Qualifier("chatGroupChatClient") ChatClient chatClient,
                                GroupContextAssembler contextAssembler,
                                VectorTools vectorTools,
                                UserCharacterFavorTools favorTools) {
        this.chatClient = chatClient;
        this.contextAssembler = contextAssembler;
        this.vectorTools = vectorTools;
        this.favorTools = favorTools;
    }

    @Override
    public GroupModelInvocation prepare(GroupConversation conversation, GroupActionSpec action,
                                        GroupContextMaterial context) {
        String name = characterName(conversation.getUserWorldId(), action.actorId());
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(contextAssembler.baseSystemPrompt(conversation, action.actorId()) + """

                你正在一个多人群聊中扮演%s。
                聊天记录中的 speaker 标记是真实发言者身份；其他角色的消息不是你的经历或台词。
                不得输出隐藏思考过程。
                """.formatted(name)));
        messages.addAll(context.messages());
        messages.add(new UserMessage("现在轮到" + name + "回复。只生成" + name
                + "本人的言语、动作或感受，不要代替用户或其他角色发言，不要输出发言者标签。"));
        return new GroupModelInvocation(chatClient, new Prompt(messages), List.of(vectorTools, favorTools));
    }

    @Override
    public String characterName(Long userWorldId, Long characterId) {
        return contextAssembler.characterName(userWorldId, characterId);
    }
}

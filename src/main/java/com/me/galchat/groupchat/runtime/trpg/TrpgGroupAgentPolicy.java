package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupAgentPolicy;
import com.me.galchat.groupchat.runtime.GroupContextMaterial;
import com.me.galchat.groupchat.runtime.GroupModelInvocation;
import com.me.galchat.service.impl.GroupContextAssembler;
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
public class TrpgGroupAgentPolicy implements GroupAgentPolicy {

    private final ChatClient chatClient;
    private final GroupContextAssembler contextAssembler;

    public TrpgGroupAgentPolicy(@Qualifier("trpgGroupChatClient") ChatClient chatClient,
                                GroupContextAssembler contextAssembler) {
        this.chatClient = chatClient;
        this.contextAssembler = contextAssembler;
    }

    @Override
    public GroupModelInvocation prepare(GroupConversation conversation, GroupActionSpec action,
                                        GroupContextMaterial context) {
        String name = characterName(conversation.getUserWorldId(), action.actorId());
        String phase = GroupChatConstant.ACTION_TRPG_COMBAT.equals(action.actionType()) ? "战斗" : "场景探索";
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(contextAssembler.baseSystemPrompt(conversation, action.actorId()) + """

                你正在 TRPG 群聊中扮演%s，当前阶段是%s。
                只能基于可见场景事实行动；不得替其他角色或用户决定行动，不得把推测写成已确认事实。
                不得输出隐藏思考过程。
                """.formatted(name, phase)));
        messages.addAll(context.messages());
        messages.add(new UserMessage("现在轮到" + name + "执行当前" + phase
                + "行动。只输出该角色的公开言语和行动，不要输出发言者标签。"));
        return new GroupModelInvocation(chatClient, new Prompt(messages), List.of());
    }

    @Override
    public String characterName(Long userWorldId, Long characterId) {
        return contextAssembler.characterName(userWorldId, characterId);
    }
}

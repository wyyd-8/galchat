package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.groupchat.runtime.GroupAgentPolicy;
import com.me.galchat.groupchat.runtime.GroupContextMaterial;
import com.me.galchat.groupchat.runtime.GroupModelInvocation;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.service.impl.CharacterCardContextFormatter;
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
    private final ICharacterCardService characterCardService;
    private final CharacterCardContextFormatter characterCardFormatter;

    public TrpgGroupAgentPolicy(@Qualifier("trpgGroupChatClient") ChatClient chatClient,
                                GroupContextAssembler contextAssembler,
                                ICharacterCardService characterCardService,
                                CharacterCardContextFormatter characterCardFormatter) {
        this.chatClient = chatClient;
        this.contextAssembler = contextAssembler;
        this.characterCardService = characterCardService;
        this.characterCardFormatter = characterCardFormatter;
    }

    @Override
    public GroupModelInvocation prepare(GroupConversation conversation, GroupActionSpec action,
                                        GroupContextMaterial context) {
        GroupActorRef actor = action.actor();
        String name = actorName(conversation.getUserWorldId(), actor);
        String phase = GroupChatConstant.ACTION_TRPG_COMBAT.equals(action.actionType()) ? "战斗" : "场景探索";
        List<CocDiceCharacterVO> cards = characterCardService.listDiceCharacters(
                conversation.getUserWorldId());
        List<CocDiceCharacterVO> visibleCards = GroupChatConstant.ACTOR_KP.equals(actor.type())
                ? cards
                : cards.stream()
                        .filter(card -> java.util.Objects.equals(card.participantId(), actor.id()))
                        .toList();
        List<Message> messages = new ArrayList<>();
        String cardContext = characterCardFormatter.format(visibleCards);
        if (GroupChatConstant.ACTOR_KP.equals(actor.type())) {
            messages.add(new SystemMessage(contextAssembler.baseSystemPrompt(conversation, actor) + "\n"
                    + cardContext + """

                    你是当前 TRPG 群聊唯一的KP，当前阶段是%s。KP不是可见的调查员。
                    你负责描述场景、裁定规则并在需要时发起掷骰；不得替用户决定调查员行动。
                    每次响应最多调用一个会改变状态的掷骰工具，且不得与其他工具并行调用。
                    调用掷骰工具后必须立即结束响应，不得继续输出叙事或JSON。
                    不得输出隐藏思考过程。
                    """.formatted(phase)));
        } else {
            messages.add(new SystemMessage(contextAssembler.baseSystemPrompt(conversation, actor) + "\n"
                    + cardContext + """

                    你正在 TRPG 群聊中扮演%s，当前阶段是%s。
                    只能基于可见场景事实行动；不得替其他角色或用户决定行动，不得把推测写成已确认事实。
                    不得输出隐藏思考过程。
                    """.formatted(name, phase)));
        }
        messages.addAll(context.messages());
        if (GroupChatConstant.ACTOR_KP.equals(actor.type())) {
            messages.add(new UserMessage("现在轮到KP推进当前" + phase
                    + "。根据公开上下文裁定并行动；需要掷骰时只调用一个对应工具。"));
        } else {
            messages.add(new UserMessage("现在轮到" + name + "执行当前" + phase
                    + "行动。只输出该角色的公开言语和行动，不要输出发言者标签。"));
        }
        return new GroupModelInvocation(chatClient, new Prompt(messages), List.of());
    }

    @Override
    public String actorName(Long userWorldId, GroupActorRef actor) {
        if (GroupChatConstant.ACTOR_KP.equals(actor.type())) {
            return "KP";
        }
        return contextAssembler.actorName(userWorldId, actor);
    }
}

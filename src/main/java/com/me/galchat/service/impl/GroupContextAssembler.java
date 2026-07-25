package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.groupchat.tool.GroupToolHistoryAssembler;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.service.IUserCharacterInfoService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GroupContextAssembler {

    private final GroupChatMessageMapper messageMapper;
    private final GroupConversationService conversationService;
    private final ChatServiceImpl chatService;
    private final IUserCharacterInfoService userCharacterInfoService;
    private final GroupToolHistoryAssembler toolHistoryAssembler;

    public List<Message> assembleContext(GroupConversation conversation, Long currentCharacterId,
                                         GroupContextSummary summary) {
        List<Message> prompt = new ArrayList<>();
        long coveredSequence = summary == null ? 0L : summary.getEndSequence();
        if (summary != null && StringUtils.hasText(summary.getSummary())) {
            prompt.add(new UserMessage("<context-summary>\n" + summary.getSummary() + "\n</context-summary>"));
        }
        prompt.addAll(assembleContextFrom(conversation, currentCharacterId, coveredSequence + 1));
        return prompt;
    }

    public List<Message> assembleContextFrom(GroupConversation conversation, Long currentCharacterId,
                                             long startSequence) {
        Map<Long, UserCharacterInfo> characterById = characterById(conversation.getUserWorldId());
        List<Message> prompt = new ArrayList<>();
        List<GroupChatMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<GroupChatMessage>()
                .eq(GroupChatMessage::getConversationId, conversation.getId())
                .ge(GroupChatMessage::getSequenceNo, startSequence)
                .eq(GroupChatMessage::getStatus, GroupChatConstant.STATUS_COMPLETED)
                .eq(GroupChatMessage::getVisibility, "public")
                .orderByAsc(GroupChatMessage::getSequenceNo));
        Map<Long, List<Message>> toolMessages =
                toolHistoryAssembler.beforeMessages(messages, currentCharacterId);
        for (GroupChatMessage message : messages) {
            if (message.getReplyStepId() != null) {
                prompt.addAll(toolMessages.getOrDefault(message.getReplyStepId(), List.of()));
            }
            if (GroupChatConstant.ACTOR_CHARACTER.equals(message.getSpeakerType())
                    && currentCharacterId.equals(message.getSpeakerId())) {
                prompt.add(new AssistantMessage(message.getContent()));
            } else {
                prompt.add(new UserMessage(formatOtherSpeakerMessage(message, characterById)));
            }
        }
        return prompt;
    }

    public String baseSystemPrompt(GroupConversation conversation, Long currentCharacterId) {
        StringBuilder builder = new StringBuilder(chatService.buildSystemPrompt(conversation.getWorldId(),
                conversation.getUserWorldId(), currentCharacterId));
        builder.append("\n群聊成员：");
        Map<Long, UserCharacterInfo> characterById = characterById(conversation.getUserWorldId());
        for (GroupChatMember member : conversationService.listMembers(conversation.getId())) {
            UserCharacterInfo character = characterById.get(member.getActorId());
            String name = character == null ? "角色" + member.getActorId() : character.getCharacterName();
            builder.append("\n- ").append(name).append(" (character:").append(member.getActorId()).append(')');
        }
        return builder.toString();
    }

    public String characterName(Long userWorldId, Long characterId) {
        UserCharacterInfo character = characterById(userWorldId).get(characterId);
        return character == null || !StringUtils.hasText(character.getCharacterName())
                ? "角色" + characterId : character.getCharacterName();
    }

    private String formatOtherSpeakerMessage(GroupChatMessage message,
                                             Map<Long, UserCharacterInfo> characterById) {
        String name;
        if (GroupChatConstant.ACTOR_USER.equals(message.getSpeakerType())) {
            name = "用户";
        } else if (GroupChatConstant.ACTOR_CHARACTER.equals(message.getSpeakerType())) {
            UserCharacterInfo character = characterById.get(message.getSpeakerId());
            name = character == null ? "角色" + message.getSpeakerId() : character.getCharacterName();
        } else {
            name = "旁白";
        }
        return "<message speaker=\"" + name + "\" actor=\"" + message.getSpeakerType()
                + (message.getSpeakerId() == null ? "" : ":" + message.getSpeakerId()) + "\">\n"
                + message.getContent() + "\n</message>";
    }

    private Map<Long, UserCharacterInfo> characterById(Long userWorldId) {
        Map<Long, UserCharacterInfo> result = new HashMap<>();
        for (UserCharacterInfo character : userCharacterInfoService.listByUserWorldId(userWorldId)) {
            result.putIfAbsent(character.getCharacterId(), character);
        }
        return result;
    }

}

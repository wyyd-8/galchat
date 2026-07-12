package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.service.IUserWorldPrefixService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
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
    private final GroupContextCompactionService compactionService;
    private final IUserWorldPrefixService userWorldPrefixService;
    private final IUserCharacterInfoService userCharacterInfoService;

    /**
     * 这里只读取 group_chat_message/group_context_summary。thinking 表刻意不作为依赖，避免未来误拼入 prompt。
     */
    public List<Message> assemble(GroupConversation conversation, Long currentCharacterId) {
        Map<Long, UserCharacterInfo> characterById = characterById(conversation.getUserWorldId());
        UserCharacterInfo currentCharacter = characterById.get(currentCharacterId);
        String currentName = currentCharacter == null || !StringUtils.hasText(currentCharacter.getCharacterName())
                ? "角色" + currentCharacterId : currentCharacter.getCharacterName();

        List<Message> prompt = new ArrayList<>();
        prompt.add(new SystemMessage(buildSystemPrompt(conversation, currentCharacterId, currentName, characterById)));

        GroupContextSummary summary = compactionService.latestSummary(conversation.getId());
        long coveredSequence = summary == null ? 0L : summary.getEndSequence();
        if (summary != null && StringUtils.hasText(summary.getSummary())) {
            prompt.add(new UserMessage("<context-summary>\n" + summary.getSummary() + "\n</context-summary>"));
        }

        List<GroupChatMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<GroupChatMessage>()
                .eq(GroupChatMessage::getConversationId, conversation.getId())
                .gt(GroupChatMessage::getSequenceNo, coveredSequence)
                .eq(GroupChatMessage::getStatus, GroupChatConstant.STATUS_COMPLETED)
                .orderByAsc(GroupChatMessage::getSequenceNo));
        for (GroupChatMessage message : messages) {
            if (GroupChatConstant.ACTOR_CHARACTER.equals(message.getSpeakerType())
                    && currentCharacterId.equals(message.getSpeakerId())) {
                prompt.add(new AssistantMessage(message.getContent()));
            } else {
                prompt.add(new UserMessage(formatOtherSpeakerMessage(message, characterById)));
            }
        }
        prompt.add(new UserMessage("现在轮到" + currentName + "回复。只生成" + currentName
                + "本人的言语、动作和感受，不要代替用户或其他角色发言，不要输出发言者标签。"));
        return prompt;
    }

    public String characterName(Long userWorldId, Long characterId) {
        UserCharacterInfo character = characterById(userWorldId).get(characterId);
        return character == null || !StringUtils.hasText(character.getCharacterName())
                ? "角色" + characterId : character.getCharacterName();
    }

    private String buildSystemPrompt(GroupConversation conversation, Long currentCharacterId, String currentName,
                                     Map<Long, UserCharacterInfo> characterById) {
        StringBuilder builder = new StringBuilder();
        builder.append("你正在一个多人群聊故事中扮演").append(currentName).append("。\n")
                .append("聊天记录中的 speaker 标记是真实发言者身份；其他角色的消息不是你的经历或台词。\n")
                .append("不得输出隐藏思考过程。\n");
        appendSection(builder, userWorldPrefixService.buildWorldPrompt(conversation.getWorldId()));
        appendSection(builder, userCharacterInfoService.buildCharacterPrompt(conversation.getUserWorldId(),
                currentCharacterId));
        builder.append("\n群聊成员：");
        List<GroupChatMember> members = conversationService.listMembers(conversation.getId());
        for (GroupChatMember member : members) {
            UserCharacterInfo character = characterById.get(member.getActorId());
            String name = character == null ? "角色" + member.getActorId() : character.getCharacterName();
            builder.append("\n- ").append(name).append(" (character:").append(member.getActorId()).append(')');
        }
        return builder.toString();
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

    private void appendSection(StringBuilder builder, String content) {
        if (StringUtils.hasText(content)) {
            builder.append('\n').append(content.trim()).append('\n');
        }
    }
}

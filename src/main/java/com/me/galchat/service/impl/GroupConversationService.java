package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupConversationCreateDTO;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.po.WorldStoryEvent;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupChatMemberMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.service.IUserWorldPrefixService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class GroupConversationService {

    private final GroupConversationMapper conversationMapper;
    private final GroupChatMemberMapper memberMapper;
    private final GroupChatMessageMapper messageMapper;
    private final IUserWorldPrefixService userWorldPrefixService;
    private final IUserCharacterInfoService userCharacterInfoService;

    @Transactional(rollbackFor = Exception.class)
    public GroupConversation create(GroupConversationCreateDTO dto) {
        if (dto == null || dto.getUserWorldId() == null) {
            throw new UserRequestException("用户世界id不能为空");
        }
        String mode = StringUtils.hasText(dto.getMode()) ? dto.getMode().trim().toLowerCase()
                : GroupChatConstant.MODE_STORY;
        return createConversation(dto.getUserWorldId(), mode, dto.getCharacterIds(), null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public GroupConversation createStoryConversation(WorldStoryEvent storyEvent, List<Long> characterIds,
                                                     String openingContent) {
        if (storyEvent == null || storyEvent.getId() == null) {
            throw new UserRequestException("故事事件不能为空");
        }
        return createConversation(storyEvent.getUserWorldId(), GroupChatConstant.MODE_STORY, characterIds,
                storyEvent.getId(), openingContent);
    }

    private GroupConversation createConversation(Long userWorldId, String mode, List<Long> characterIds,
                                                 Long storyEventId, String openingContent) {
        UserWorldPrefix userWorld = userWorldPrefixService.checkUserWorldAuth(userWorldId, true);
        List<Long> distinctCharacterIds = characterIds == null ? List.of() : characterIds.stream()
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (CollectionUtils.isEmpty(distinctCharacterIds)) {
            throw new UserRequestException("群聊参与角色不能为空");
        }
        checkCharacters(userWorldId, distinctCharacterIds);

        LocalDateTime now = LocalDateTime.now();
        GroupConversation conversation = new GroupConversation()
                .setUserWorldId(userWorldId)
                .setWorldId(userWorld.getWorldId())
                .setStoryEventId(storyEventId)
                .setMode(mode)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setVersion(0)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        conversationMapper.insert(conversation);

        for (int i = 0; i < distinctCharacterIds.size(); i++) {
            memberMapper.insert(new GroupChatMember()
                    .setConversationId(conversation.getId())
                    .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                    .setActorId(distinctCharacterIds.get(i))
                    .setPosition(i)
                    .setEnabled(true)
                    .setTalkativeness(0.5));
        }
        if (StringUtils.hasText(openingContent)) {
            appendSystemEvent(conversation.getId(), openingContent, GroupChatConstant.MESSAGE_SYSTEM_EVENT);
        }
        return conversation;
    }

    public GroupConversation requireActive(Long conversationId) {
        GroupConversation conversation = requireAuthorized(conversationId);
        if (!GroupChatConstant.STATUS_ACTIVE.equals(conversation.getStatus())) {
            throw new UserRequestException("群聊会话已结束");
        }
        return conversation;
    }

    public GroupConversation requireAuthorized(Long conversationId) {
        GroupConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new UserRequestException("群聊会话不存在");
        }
        userWorldPrefixService.checkUserWorldAuth(conversation.getUserWorldId(), false);
        return conversation;
    }

    public List<GroupChatMember> listMembers(Long conversationId) {
        return memberMapper.selectList(new LambdaQueryWrapper<GroupChatMember>()
                .eq(GroupChatMember::getConversationId, conversationId)
                .orderByAsc(GroupChatMember::getPosition)
                .orderByAsc(GroupChatMember::getId));
    }

    public void checkReplyMember(Long conversationId, String speakerType, Long speakerId, boolean force) {
        if (!GroupChatConstant.ACTOR_CHARACTER.equals(speakerType) || speakerId == null) {
            throw new UserRequestException("第一版仅支持指定角色回复");
        }
        GroupChatMember member = memberMapper.selectOne(new LambdaQueryWrapper<GroupChatMember>()
                .eq(GroupChatMember::getConversationId, conversationId)
                .eq(GroupChatMember::getActorType, speakerType)
                .eq(GroupChatMember::getActorId, speakerId)
                .last("limit 1"));
        if (member == null) {
            throw new UserRequestException("回复角色不属于当前群聊");
        }
        if (!force && !Boolean.TRUE.equals(member.getEnabled())) {
            throw new UserRequestException("回复角色已被禁用");
        }
    }

    public GroupChatMessage appendSystemEvent(Long conversationId, String content, String messageKind) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        GroupChatMessage message = new GroupChatMessage()
                .setConversationId(conversationId)
                .setSpeakerType(GroupChatConstant.ACTOR_NARRATOR)
                .setMessageKind(messageKind)
                .setVisibility("public")
                .setContent(content.trim())
                .setSequenceNo(nextSequence(conversationId))
                .setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        messageMapper.insert(message);
        return message;
    }

    public void close(Long conversationId) {
        GroupConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            return;
        }
        conversation.setStatus(GroupChatConstant.STATUS_CLOSED).setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);
    }

    public long nextSequence(Long conversationId) {
        GroupChatMessage latest = messageMapper.selectOne(new LambdaQueryWrapper<GroupChatMessage>()
                .select(GroupChatMessage::getSequenceNo)
                .eq(GroupChatMessage::getConversationId, conversationId)
                .orderByDesc(GroupChatMessage::getSequenceNo)
                .last("limit 1"));
        return latest == null || latest.getSequenceNo() == null ? 1L : latest.getSequenceNo() + 1L;
    }

    private void checkCharacters(Long userWorldId, List<Long> characterIds) {
        Set<Long> existing = new HashSet<>(userCharacterInfoService.listByUserWorldId(userWorldId).stream()
                .map(UserCharacterInfo::getCharacterId)
                .toList());
        if (!existing.containsAll(characterIds)) {
            throw new UserRequestException("群聊中存在未添加到当前用户世界的角色");
        }
    }
}

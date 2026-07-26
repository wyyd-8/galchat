package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupConversationCreateDTO;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.vo.GroupConversationVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupChatMemberMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GroupConversationService {

    private final GroupConversationMapper conversationMapper;
    private final GroupChatMemberMapper memberMapper;
    private final GroupChatMessageMapper messageMapper;
    private final GroupReplyPlanMapper replyPlanMapper;
    private final GroupReplyPlanItemMapper replyPlanItemMapper;
    private final IUserWorldPrefixService userWorldPrefixService;
    private final IUserCharacterInfoService userCharacterInfoService;

    @Transactional(rollbackFor = Exception.class)
    public GroupConversation create(GroupConversationCreateDTO dto) {
        if (dto == null || dto.getUserWorldId() == null) {
            throw new UserRequestException("用户世界id不能为空");
        }
        String mode = StringUtils.hasText(dto.getMode()) ? dto.getMode().trim().toLowerCase()
                : GroupChatConstant.MODE_CHAT;
        if (!GroupChatConstant.MODE_CHAT.equals(mode) && !GroupChatConstant.MODE_TRPG.equals(mode)) {
            throw new UserRequestException("群聊模式仅支持chat或trpg");
        }
        return createConversation(dto.getUserWorldId(), mode, dto.getTitle(), dto.getCharacterIds());
    }

    private GroupConversation createConversation(Long userWorldId, String mode, String title,
                                                 List<Long> characterIds) {
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
                .setMode(mode)
                .setTitle(StringUtils.hasText(title) ? title.trim() : "群聊")
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
        if (GroupChatConstant.MODE_CHAT.equals(mode)) {
            createDefaultReplyPlan(conversation, distinctCharacterIds, now);
        }
        return conversation;
    }

    private void createDefaultReplyPlan(GroupConversation conversation, List<Long> characterIds,
                                        LocalDateTime now) {
        GroupReplyPlan plan = new GroupReplyPlan()
                .setConversationId(conversation.getId())
                .setSource(GroupChatConstant.PLAN_SOURCE_USER)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        replyPlanMapper.insert(plan);
        for (int i = 0; i < characterIds.size(); i++) {
            replyPlanItemMapper.insert(new GroupReplyPlanItem()
                    .setPlanId(plan.getId())
                    .setGroupKey("default")
                    .setGroupName("群聊")
                    .setGroupOrder(1)
                    .setItemOrder(i + 1)
                    .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                    .setActorId(characterIds.get(i))
                    .setCreatedAt(now)
                    .setUpdatedAt(now));
        }
        conversation.setActiveReplyPlanId(plan.getId()).setUpdatedAt(now);
        conversationMapper.updateById(conversation);
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

    public List<GroupConversationVO> list(Long userWorldId, String status) {
        userWorldPrefixService.checkUserWorldAuth(userWorldId, false);
        List<GroupConversation> conversations = conversationMapper.selectList(new LambdaQueryWrapper<GroupConversation>()
                .eq(GroupConversation::getUserWorldId, userWorldId)
                .eq(StringUtils.hasText(status), GroupConversation::getStatus, status)
                .orderByDesc(GroupConversation::getId));
        if (conversations.isEmpty()) {
            return List.of();
        }
        Map<Long, GroupChatMessage> latestMessages = messageMapper
                .selectLatestCompletedByConversationIds(conversations.stream().map(GroupConversation::getId).toList())
                .stream()
                .collect(Collectors.toMap(GroupChatMessage::getConversationId, Function.identity()));
        return conversations.stream().map(conversation -> toVO(conversation, latestMessages.get(conversation.getId())))
                .toList();
    }

    public GroupConversationVO get(Long conversationId) {
        GroupConversation conversation = requireAuthorized(conversationId);
        List<GroupChatMessage> messages = messageMapper
                .selectLatestCompletedByConversationIds(List.of(conversationId));
        return toVO(conversation, messages.isEmpty() ? null : messages.getFirst());
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
            throw new UserRequestException("只有已创建的角色才能加入群聊");
        }
    }

    private GroupConversationVO toVO(GroupConversation conversation, GroupChatMessage latestMessage) {
        GroupConversationVO result = GroupConversationVO.from(conversation);
        if (latestMessage != null) {
            result.setLastChatContent(latestMessage.getContent())
                    .setLastChatTime(latestMessage.getCreatedAt());
        }
        return result;
    }
}

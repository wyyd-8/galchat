package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.mapper.GroupChatMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class GroupMaterialMessageFeed {

    private final GroupChatMessageMapper messageMapper;

    public List<GroupChatMessage> listNew(
            Long replyStepId, Long afterMessageId) {
        return messageMapper.selectList(
                        new LambdaQueryWrapper<GroupChatMessage>()
                                .eq(GroupChatMessage::getReplyStepId,
                                        replyStepId)
                                .eq(GroupChatMessage::getMessageKind,
                                        GroupChatConstant.MESSAGE_MATERIAL)
                                .eq(GroupChatMessage::getStatus,
                                        GroupChatConstant.STATUS_COMPLETED)
                                .gt(afterMessageId != null,
                                        GroupChatMessage::getId,
                                        afterMessageId)
                                .orderByAsc(GroupChatMessage::getId))
                .stream()
                .filter(message -> afterMessageId == null
                        || message.getId() > afterMessageId)
                .toList();
    }
}

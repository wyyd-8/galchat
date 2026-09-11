package com.me.galchat.groupchat.dice;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.service.DiceMessageRoundAppender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class GroupDiceMessageRoundAppender implements DiceMessageRoundAppender {

    private final GroupChatMessageMapper messageMapper;
    private final DiceRollMessageCodec codec;

    @Override
    public void appendRounds(
            Long conversationId, Long summaryId, Collection<Integer> roundNos) {
        GroupChatMessage message = messageMapper.selectOne(
                new LambdaQueryWrapper<GroupChatMessage>()
                        .eq(GroupChatMessage::getConversationId, conversationId)
                        .eq(GroupChatMessage::getMessageKind,
                                GroupChatConstant.MESSAGE_DICE_ROLL)
                        .orderByDesc(GroupChatMessage::getSequenceNo)
                        .orderByDesc(GroupChatMessage::getId)
                        .last("limit 1"));
        if (message == null) {
            throw new UserRequestException("找不到当前群聊的掷骰消息");
        }
        DiceRollMessageContent reference = codec.decode(message.getContent());
        if (!Objects.equals(reference.summaryId(), summaryId)) {
            throw new UserRequestException("最近的掷骰消息不属于当前概要");
        }
        List<Integer> merged = new ArrayList<>(reference.roundNos());
        if (roundNos != null) {
            merged.addAll(roundNos);
        }
        message.setContent(codec.encode(summaryId, merged))
                .setUpdatedAt(LocalDateTime.now());
        messageMapper.updateById(message);
    }
}

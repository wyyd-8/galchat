package com.me.galchat.service.impl.character;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.UserWorldPrefixMapper;
import com.me.galchat.utils.CurrentHolder;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Ownership checks for HTTP requests; internal generation reads have no request identity. */
@Service
@RequiredArgsConstructor
public class CharacterCardAccessService {
    private final CocCharacterMapper characterMapper;
    private final GroupConversationMapper conversationMapper;
    private final UserWorldPrefixMapper worldMapper;

    public void requireCardAccess(Long cardId) {
        Long userId = currentUserId();
        if (cardId == null) throw new UserRequestException("人物卡id不能为空");
        CocCharacter card = characterMapper.selectById(cardId);
        if (card == null) throw new UserRequestException("人物卡不存在");
        requireRunAccess(userId, card.getRunId());
    }

    public void requireRunAccess(Long runId) {
        requireRunAccess(currentUserId(), runId);
    }

    private void requireRunAccess(Long userId, Long runId) {
        if (runId == null) throw new UserRequestException("runId不能为空");
        GroupConversation conversation = conversationMapper.selectById(runId);
        if (conversation == null || !GroupChatConstant.MODE_TRPG.equals(conversation.getMode())) {
            throw new UserRequestException("runId必须是TRPG群聊id");
        }
        UserWorldPrefix world = conversation.getUserWorldId() == null
                ? null : worldMapper.selectById(conversation.getUserWorldId());
        if (world == null || !Objects.equals(world.getUserId(), userId)) {
            throw new UserAuthException("无权访问该跑团");
        }
    }

    private Long currentUserId() {
        Integer userId = CurrentHolder.getCurrentId();
        if (userId == null) throw new UserAuthException("用户未登录");
        return userId.longValue();
    }
}

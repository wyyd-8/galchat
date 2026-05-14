package com.me.galchat.domain.po;

import com.me.galchat.exception.ConversationIdException;
import lombok.Getter;
import org.springframework.util.StringUtils;

@Getter
public class ConversationInfo {
    private final Long userWorldId;
    private final Long characterId;
    private final Long start;

    @Override
    public String toString() {
        return userWorldId + ":" + characterId + ":" + start;
    }

    public ConversationInfo(Long userWorldId, Long characterId, Long start) {
        if (userWorldId == null) {
            throw new ConversationIdException("会话id中的用户世界id不能为空");
        }
        if (characterId == null) {
            throw new ConversationIdException("会话id中的角色id不能为空");
        }
        this.userWorldId = userWorldId;
        this.characterId = characterId;
        this.start = start;
    }

    public ConversationInfo(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            throw new ConversationIdException("会话id不能为空");
        }
        String[] split = conversationId.split(":", -1);
        if (split.length != 3) {
            throw new ConversationIdException("会话id格式错误，应为 userWorldId:characterId:start");
        }
        this.userWorldId = parseRequiredLong(split[0], "用户世界id");
        this.characterId = parseRequiredLong(split[1], "角色id");
        this.start = parseOptionalLong(split[2], "起始消息id");
    }

    private Long parseRequiredLong(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new ConversationIdException("会话id中的" + fieldName + "不能为空");
        }
        return parseLong(value, fieldName);
    }

    private Long parseOptionalLong(String value, String fieldName) {
        if (!StringUtils.hasText(value) || "null".equalsIgnoreCase(value)) {
            return null;
        }
        return parseLong(value, fieldName);
    }

    private Long parseLong(String value, String fieldName) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new ConversationIdException("会话id中的" + fieldName + "必须是数字");
        }
    }
}

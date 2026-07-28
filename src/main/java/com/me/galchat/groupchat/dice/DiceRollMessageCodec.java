package com.me.galchat.groupchat.dice;

import com.me.galchat.exception.UserRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class DiceRollMessageCodec {

    private final ObjectMapper objectMapper;

    public String encode(Long summaryId, Collection<Integer> roundNos) {
        return write(normalize(summaryId, roundNos));
    }

    public DiceRollMessageContent decode(String content) {
        if (!StringUtils.hasText(content)) {
            throw new UserRequestException("掷骰消息内容不能为空");
        }
        try {
            DiceRollMessageContent parsed =
                    objectMapper.readValue(content, DiceRollMessageContent.class);
            if (parsed == null) {
                throw new UserRequestException("掷骰消息内容无法解析");
            }
            return normalize(parsed.summaryId(), parsed.roundNos());
        } catch (JacksonException exception) {
            throw new UserRequestException("掷骰消息内容无法解析");
        }
    }

    private DiceRollMessageContent normalize(
            Long summaryId, Collection<Integer> roundNos) {
        if (summaryId == null || summaryId <= 0) {
            throw new UserRequestException("掷骰消息缺少有效概要id");
        }
        List<Integer> normalized = roundNos == null
                ? List.of()
                : roundNos.stream()
                        .filter(Objects::nonNull)
                        .filter(round -> round > 0)
                        .distinct()
                        .sorted()
                        .toList();
        if (normalized.isEmpty()) {
            throw new UserRequestException("掷骰消息缺少有效轮次");
        }
        return new DiceRollMessageContent(summaryId, normalized);
    }

    private String write(DiceRollMessageContent content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JacksonException exception) {
            throw new IllegalStateException("序列化掷骰消息失败", exception);
        }
    }
}

package com.me.galchat.tool;

import com.me.galchat.domain.po.UserEventLog;
import com.me.galchat.service.IUserEventLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class UserEventLogTools {
    private final IUserEventLogService userEventLogService;

    @Tool(description = """
            工具描述：
            将用户在对话中提及的个人事件、计划、状态或情绪点存储到“用户事件库”，
            以便后续在恰当时机由AI主动发起自然的关心。
            使用流程：
            1. 在对话中，当用户提及个人计划、状态时，应当调用本工具，例如考试，面试，发烧或期待的游戏等。
            2. 整理事件描述字符串，尽量保留用户原话中的细节，同时根据当前时间戳、用户提及的时间、事件类型推断应该在何时主动提及。
            3. 调用本工具，传入上述信息，该方法没有返回值。
            注意事项：
            仅记录能够在将来主动关心的事件，忽略“我去倒杯水”、“今天天气不错”这类即时或非个人事件。
            若用户后续修改了同一事件的细节（如时间变化、事件取消），请再次调用本工具并附上新描述。
            不应对同一无变化事件连续多次调用本工具。
            """)
    public void addUserEventLog(@ToolParam(description = "事件描述") String eventDescription,
                                  @ToolParam(description = "事件发生时间，ISO-8601格式，例如 2026-05-20T14:30:00")
                                  String time,
                                  ToolContext context) {
        if (!StringUtils.hasText(eventDescription) || context == null || context.getContext() == null) {
            return;
        }

        Map<String, Object> map = context.getContext();
        Long userWorldId = asLong(map.get("userWorldId"));
        Long characterId = asLong(map.get("characterId"));
        if (userWorldId == null || characterId == null) {
            return;
        }

        LocalDateTime eventTime = parseTime(time);
        if (eventTime == null) {
            return;
        }

        UserEventLog userEventLog = new UserEventLog()
                .setUserWorldId(userWorldId)
                .setCharacterId(characterId)
                .setTime(eventTime)
                .setEventDescription(eventDescription);
        userEventLogService.addUserEventLog(userEventLog);
    }

    private LocalDateTime parseTime(String time) {
        if (!StringUtils.hasText(time)) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(time);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            try {
                return Long.valueOf(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}

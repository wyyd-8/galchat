package com.me.galchat.groupchat.material;

import com.me.galchat.exception.UserRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class MaterialMessageCodec {

    private final ObjectMapper objectMapper;

    public String toAgentText(String content) {
        try {
            Map<?, ?> value = objectMapper.readValue(content, Map.class);
            String title = stringValue(value.get("title"));
            String description = stringValue(value.get("description"));
            return "<shown-material title=\"" + escape(title) + "\">\n"
                    + description + "\n</shown-material>";
        } catch (JacksonException exception) {
            throw new UserRequestException("材料消息内容无法解析");
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}

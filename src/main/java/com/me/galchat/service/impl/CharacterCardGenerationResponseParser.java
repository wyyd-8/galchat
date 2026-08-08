package com.me.galchat.service.impl;

import com.me.galchat.domain.dto.CharacterCardGenerationModels;
import com.me.galchat.exception.UserRequestException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class CharacterCardGenerationResponseParser {

    private static final Set<String> BACKGROUND_TEXT_FIELDS = Set.of(
            "appearance", "ideology", "significantPeople", "meaningfulLocations",
            "treasuredPossessions", "traits", "keyConnectionText");
    private static final Logger logger = LoggerFactory.getLogger(
            CharacterCardGenerationResponseParser.class);

    private final ObjectMapper objectMapper;

    public <T> T read(String response, Class<T> type) {
        if (response == null || response.isBlank()) {
            logger.warn("AI人物卡响应为空：targetType={}", type.getSimpleName());
            throw generationFailure();
        }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) {
            logger.warn("AI人物卡响应缺少完整JSON对象边界：targetType={}, length={}, response={}",
                    type.getSimpleName(), response.length(), response);
            throw generationFailure();
        }
        try {
            String json = response.substring(start, end + 1);
            T result = CharacterCardGenerationModels.BackgroundPlan.class.equals(type)
                    ? objectMapper.treeToValue(normalizeBackgroundEntries(json), type)
                    : objectMapper.readValue(json, type);
            if (result == null) {
                logger.warn("AI人物卡JSON反序列化结果为空：targetType={}, json={}",
                        type.getSimpleName(), json);
                throw generationFailure();
            }
            return result;
        } catch (JacksonException exception) {
            logger.warn("AI人物卡JSON解析失败：targetType={}, start={}, end={}, response={}",
                    type.getSimpleName(), start, end, response, exception);
            throw generationFailure();
        }
    }

    private JsonNode normalizeBackgroundEntries(String json) throws JacksonException {
        JsonNode root = objectMapper.readTree(json);
        if (!root.isObject()) {
            return root;
        }
        ObjectNode object = root.asObject();
        for (String field : BACKGROUND_TEXT_FIELDS) {
            JsonNode value = object.get(field);
            if (value == null || !value.isContainer()) {
                continue;
            }
            List<String> parts = new ArrayList<>();
            collectText(value, parts);
            if (!parts.isEmpty()) {
                object.put(field, String.join("：", parts));
            }
        }
        return object;
    }

    private void collectText(JsonNode node, List<String> parts) {
        if (node.isTextual()) {
            String value = node.asString().trim();
            if (!value.isEmpty()) {
                parts.add(value);
            }
            return;
        }
        if (node.isContainer()) {
            node.values().forEach(child -> collectText(child, parts));
        }
    }

    private UserRequestException generationFailure() {
        return new UserRequestException("AI生成人物卡失败，请重新生成");
    }
}

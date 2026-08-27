package com.me.galchat.service.impl;

import com.me.galchat.domain.dto.TrpgEpilogueModels;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class TrpgEpilogueMessageCodec {

    private final ObjectMapper objectMapper;

    public String encode(TrpgEpilogueModels.Content content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JacksonException exception) {
            throw new IllegalStateException("人物后传消息序列化失败", exception);
        }
    }
}

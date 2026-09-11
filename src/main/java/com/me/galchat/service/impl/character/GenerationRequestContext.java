package com.me.galchat.service.impl.character;

import java.util.Map;

public record GenerationRequestContext(
        String operation,
        String method,
        String path,
        Map<String, Object> body) {
}

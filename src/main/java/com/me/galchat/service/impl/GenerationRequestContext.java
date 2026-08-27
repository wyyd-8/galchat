package com.me.galchat.service.impl;

import java.util.Map;

public record GenerationRequestContext(
        String operation,
        String method,
        String path,
        Map<String, Object> body) {
}

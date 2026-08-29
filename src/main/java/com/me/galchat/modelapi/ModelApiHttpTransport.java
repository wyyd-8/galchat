package com.me.galchat.modelapi;

import java.net.URI;
import java.util.List;

public interface ModelApiHttpTransport {

    Response post(Request request);

    record Request(
            URI uri,
            String apiKey,
            String body,
            boolean streaming) {
    }

    record Response(int statusCode, String body, List<String> lines) {
    }
}

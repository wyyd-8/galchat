package com.me.galchat.modelapi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Component
public class JdkModelApiHttpTransport implements ModelApiHttpTransport {

    private final HttpClient client;
    private final Duration requestTimeout;
    private final int maxResponseBytes;

    public JdkModelApiHttpTransport(
            @Value("${galchat.model-api.connect-timeout:5s}")
            Duration connectTimeout,
            @Value("${galchat.model-api.request-timeout:30s}")
            Duration requestTimeout,
            @Value("${galchat.model-api.max-response-bytes:1048576}")
            int maxResponseBytes) {
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("响应大小上限必须大于 0");
        }
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.requestTimeout = requestTimeout;
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public Response post(Request request) {
        HttpRequest httpRequest = HttpRequest.newBuilder(request.uri())
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + request.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", request.streaming()
                        ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        request.body(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = client.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = readLimited(input);
            }
            String body = new String(bytes, StandardCharsets.UTF_8);
            return new Response(response.statusCode(),
                    request.streaming() ? "" : body,
                    request.streaming() ? splitLines(body) : List.of());
        } catch (HttpTimeoutException exception) {
            throw new ModelApiTransportException(
                    "TIMEOUT", "连接上游模型服务超时", exception);
        } catch (SSLException exception) {
            throw new ModelApiTransportException(
                    "TLS_ERROR", "上游模型服务 TLS 校验失败", exception);
        } catch (IOException exception) {
            throw new ModelApiTransportException(
                    "NETWORK_ERROR", "无法连接上游模型服务", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelApiTransportException(
                    "INTERRUPTED", "模型能力测试被中断", exception);
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                Math.min(maxResponseBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maxResponseBytes) {
                throw new ModelApiTransportException(
                        "RESPONSE_TOO_LARGE", "上游响应超过大小限制", null);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private List<String> splitLines(String body) {
        if (body.isEmpty()) {
            return List.of();
        }
        String[] lines = body.split("\\R", -1);
        int length = lines.length;
        if (length > 0 && lines[length - 1].isEmpty()) {
            length--;
        }
        return Arrays.asList(Arrays.copyOf(lines, length));
    }
}

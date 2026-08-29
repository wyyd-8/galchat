package com.me.galchat.modelapi;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Service
public class ModelApiProbeService {

    private final ModelApiHttpTransport transport;
    private final ModelApiProbeResponseInterpreter interpreter;
    private final ObjectMapper objectMapper;

    public ModelApiProbeService(
            ModelApiHttpTransport transport,
            ModelApiProbeResponseInterpreter interpreter,
            ObjectMapper objectMapper) {
        this.transport = transport;
        this.interpreter = interpreter;
        this.objectMapper = objectMapper;
    }

    public ModelApiProbeResult probe(URI baseUrl, String apiKey, String model) {
        URI endpoint = URI.create(baseUrl.toString() + "/chat/completions");
        boolean reasoningDetected = false;
        ModelApiHttpTransport.Response chat;
        try {
            chat = transport.post(new ModelApiHttpTransport.Request(
                    endpoint, apiKey, chatBody(model), false));
        } catch (ModelApiTransportException exception) {
            return failed(exception.getCode(), exception.getMessage());
        }
        if (!successful(chat.statusCode())) {
            return failed(statusCode(chat.statusCode()),
                    statusMessage(chat.statusCode()));
        }
        ModelApiProbeResponseInterpreter.ChatObservation chatObservation =
                interpreter.inspectChat(chat.body());
        if (!chatObservation.valid()) {
            return failed("INVALID_RESPONSE", "上游未返回有效的 assistant 消息");
        }
        reasoningDetected = chatObservation.reasoningDetected();

        ModelApiCapability streaming = ModelApiCapability.INCONCLUSIVE;
        try {
            ModelApiHttpTransport.Response stream = transport.post(
                    new ModelApiHttpTransport.Request(endpoint, apiKey,
                            streamingBody(model), true));
            if (successful(stream.statusCode())) {
                ModelApiProbeResponseInterpreter.StreamObservation observation =
                        interpreter.inspectStream(stream.lines());
                streaming = observation.validChunkCount() > 0
                        && observation.completed()
                        ? ModelApiCapability.SUPPORTED
                        : ModelApiCapability.INCONCLUSIVE;
                reasoningDetected = reasoningDetected
                        || observation.reasoningDetected();
            } else if (stream.statusCode() >= 400
                    && stream.statusCode() < 500) {
                streaming = ModelApiCapability.UNSUPPORTED;
            }
        } catch (ModelApiTransportException ignored) {
            streaming = ModelApiCapability.INCONCLUSIVE;
        }

        ModelApiCapability toolCalling = ModelApiCapability.INCONCLUSIVE;
        try {
            ModelApiHttpTransport.Response tool = transport.post(
                    new ModelApiHttpTransport.Request(endpoint, apiKey,
                            toolBody(model), false));
            if (successful(tool.statusCode())) {
                toolCalling = interpreter.inspectToolCall(tool.body());
                reasoningDetected = reasoningDetected
                        || interpreter.inspectChat(tool.body()).reasoningDetected();
            } else if (tool.statusCode() >= 400 && tool.statusCode() < 500
                    && explicitlyRejectsTools(tool.body())) {
                toolCalling = ModelApiCapability.UNSUPPORTED;
            }
        } catch (ModelApiTransportException ignored) {
            toolCalling = ModelApiCapability.INCONCLUSIVE;
        }

        boolean allSupported = streaming == ModelApiCapability.SUPPORTED
                && toolCalling == ModelApiCapability.SUPPORTED;
        return new ModelApiProbeResult(
                allSupported ? ModelApiTestStatus.SUCCESS
                        : ModelApiTestStatus.PARTIAL,
                ModelApiCapability.SUPPORTED,
                streaming,
                toolCalling,
                reasoningDetected ? ReasoningOutputStatus.DETECTED
                        : ReasoningOutputStatus.NOT_DETECTED,
                allSupported ? "OK" : "CAPABILITY_PARTIAL",
                allSupported ? "聊天、流式与工具调用均可用"
                        : "基础聊天可用，部分能力不支持或本次无法确认");
    }

    private ModelApiProbeResult failed(String code, String message) {
        return new ModelApiProbeResult(
                ModelApiTestStatus.FAILED,
                ModelApiCapability.UNSUPPORTED,
                ModelApiCapability.UNKNOWN,
                ModelApiCapability.UNKNOWN,
                ReasoningOutputStatus.UNKNOWN,
                code,
                message);
    }

    private String chatBody(String model) {
        return objectMapper.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", "Reply with OK.")),
                "stream", false,
                "max_tokens", 8));
    }

    private String streamingBody(String model) {
        return objectMapper.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", "Reply with OK.")),
                "stream", true,
                "max_tokens", 8));
    }

    private String toolBody(String model) {
        Map<String, Object> function = Map.of(
                "name", "get_test_value",
                "description", "Return a harmless test value",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(),
                        "additionalProperties", false));
        return objectMapper.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", "Call get_test_value now.")),
                "tools", List.of(Map.of(
                        "type", "function", "function", function)),
                "tool_choice", Map.of(
                        "type", "function",
                        "function", Map.of("name", "get_test_value")),
                "stream", false,
                "max_tokens", 32));
    }

    private boolean explicitlyRejectsTools(String body) {
        if (body == null) {
            return false;
        }
        String normalized = body.toLowerCase();
        boolean mentionsTool = normalized.contains("tool")
                || normalized.contains("function");
        boolean rejects = normalized.contains("not support")
                || normalized.contains("unsupported")
                || normalized.contains("not allowed")
                || normalized.contains("unknown field")
                || normalized.contains("unrecognized");
        return mentionsTool && rejects;
    }

    private boolean successful(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private String statusCode(int statusCode) {
        return switch (statusCode) {
            case 401, 403 -> "AUTH_FAILED";
            case 404 -> "MODEL_NOT_FOUND";
            case 429 -> "RATE_LIMITED";
            default -> statusCode >= 500 ? "UPSTREAM_5XX" : "REQUEST_REJECTED";
        };
    }

    private String statusMessage(int statusCode) {
        return switch (statusCode) {
            case 401, 403 -> "API Key 无效或无权访问该模型";
            case 404 -> "接口路径或模型不存在";
            case 429 -> "上游服务请求过于频繁";
            default -> statusCode >= 500 ? "上游服务暂时不可用"
                    : "上游拒绝了基础聊天请求";
        };
    }
}

package com.me.galchat.modelapi;

import com.openai.errors.OpenAIServiceException;
import com.me.galchat.memory.AssistantReasoning;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Locale;

@Service
public class ModelApiProbeService {

    public ModelApiProbeResult probe(ResolvedUserModelRuntime runtime) {
        ChatClient chatClient = runtime.newChatClientBuilder().build();
        ChatResponse chatResponse;
        try {
            chatResponse = chatClient.prompt()
                    .user("Reply with OK.")
                    .call()
                    .chatResponse();
        } catch (RuntimeException exception) {
            Failure failure = classify(exception);
            return failed(failure.code(), failure.message());
        }
        if (!valid(chatResponse)) {
            return failed("INVALID_RESPONSE",
                    "上游未返回有效的 assistant 消息");
        }
        boolean reasoningDetected = reasoningDetected(chatResponse);

        ModelApiCapability streaming = ModelApiCapability.INCONCLUSIVE;
        try {
            List<ChatResponse> chunks = chatClient.prompt()
                    .user("Reply with OK.")
                    .stream()
                    .chatResponse()
                    .collectList()
                    .block();
            if (chunks != null && chunks.stream().anyMatch(this::valid)) {
                streaming = ModelApiCapability.SUPPORTED;
                reasoningDetected = reasoningDetected
                        || chunks.stream().anyMatch(this::reasoningDetected);
            }
        } catch (RuntimeException ignored) {
            streaming = ModelApiCapability.INCONCLUSIVE;
        }

        ModelApiCapability toolCalling = ModelApiCapability.INCONCLUSIVE;
        ProbeTool tool = new ProbeTool();
        try {
            ChatResponse toolResponse = chatClient.prompt()
                    .user("Call get_test_value exactly once, then briefly confirm the returned value.")
                    .tools(tool)
                    .call()
                    .chatResponse();
            if (tool.called() && valid(toolResponse)) {
                toolCalling = ModelApiCapability.SUPPORTED;
            }
            reasoningDetected = reasoningDetected
                    || reasoningDetected(toolResponse);
        } catch (RuntimeException exception) {
            if (explicitlyRejectsTools(exception)) {
                toolCalling = ModelApiCapability.UNSUPPORTED;
            }
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

    private boolean valid(ChatResponse response) {
        return response != null
                && response.getResult() != null
                && response.getResult().getOutput() != null
                && (response.getResult().getOutput().getText() != null
                || response.getResult().getOutput().hasToolCalls());
    }

    private boolean reasoningDetected(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return false;
        }
        return response.getResults().stream()
                .filter(generation -> generation != null
                        && generation.getOutput() != null)
                .map(generation -> AssistantReasoning.get(
                        generation.getOutput()))
                .anyMatch(text -> text != null && !text.isBlank());
    }

    private boolean explicitlyRejectsTools(Throwable exception) {
        String message = allMessages(exception).toLowerCase(Locale.ROOT);
        boolean mentionsTool = message.contains("tool")
                || message.contains("function");
        boolean rejects = message.contains("not support")
                || message.contains("unsupported")
                || message.contains("not allowed")
                || message.contains("unknown field")
                || message.contains("unrecognized");
        return mentionsTool && rejects;
    }

    private Failure classify(Throwable exception) {
        OpenAIServiceException serviceException = findCause(
                exception, OpenAIServiceException.class);
        if (serviceException != null) {
            return statusFailure(serviceException.statusCode());
        }
        String message = allMessages(exception).toLowerCase(Locale.ROOT);
        if (message.contains("401") || message.contains("403")
                || message.contains("unauthorized")) {
            return statusFailure(401);
        }
        if (message.contains("404") || message.contains("not found")) {
            return statusFailure(404);
        }
        if (message.contains("429") || message.contains("rate limit")) {
            return statusFailure(429);
        }
        if (findCause(exception, SocketTimeoutException.class) != null
                || message.contains("timeout")
                || message.contains("timed out")) {
            return new Failure("TIMEOUT", "连接上游模型服务超时");
        }
        if (findCause(exception, javax.net.ssl.SSLException.class) != null) {
            return new Failure("TLS_ERROR", "上游模型服务 TLS 校验失败");
        }
        if (findCause(exception, ConnectException.class) != null) {
            return new Failure("NETWORK_ERROR", "无法连接上游模型服务");
        }
        return new Failure("REQUEST_FAILED", "上游模型调用失败");
    }

    private Failure statusFailure(int statusCode) {
        return switch (statusCode) {
            case 401, 403 -> new Failure("AUTH_FAILED",
                    "API Key 无效或无权访问该模型");
            case 404 -> new Failure("MODEL_NOT_FOUND",
                    "接口路径或模型不存在");
            case 429 -> new Failure("RATE_LIMITED",
                    "上游服务请求过于频繁");
            default -> statusCode >= 500
                    ? new Failure("UPSTREAM_5XX", "上游服务暂时不可用")
                    : new Failure("REQUEST_REJECTED",
                    "上游拒绝了基础聊天请求");
        };
    }

    private String allMessages(Throwable throwable) {
        StringBuilder value = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null) {
                value.append(' ').append(current.getMessage());
            }
            current = current.getCause();
        }
        return value.toString();
    }

    private <T extends Throwable> T findCause(
            Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
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

    public static final class ProbeTool {
        private boolean called;

        @Tool(name = "get_test_value",
                description = "Return a harmless test value")
        public String getTestValue() {
            called = true;
            return "test-value";
        }

        boolean called() {
            return called;
        }
    }

    private record Failure(String code, String message) {
    }
}

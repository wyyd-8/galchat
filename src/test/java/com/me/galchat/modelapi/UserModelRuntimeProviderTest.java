package com.me.galchat.modelapi;

import com.me.galchat.domain.po.UserModelApi;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserModelApiMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class UserModelRuntimeProviderTest {

    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes());

    private ModelApiKeyCipher cipher;
    private UserModelApi existing;
    private CapturingFactory factory;
    private UserModelRuntimeProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        cipher = new ModelApiKeyCipher(MASTER_KEY);
        existing = new UserModelApi()
                .setId(41L)
                .setUserId(7L)
                .setBaseUrl("https://models.example.com/v1")
                .setModelName("model-a")
                .setApiKeyEncrypted(cipher.encrypt("sk-secret"))
                .setRequestOverrides(Map.of(
                        "thinking", Map.of("type", "enabled"),
                        "reasoning_effort", "high"))
                .setStatus(ModelApiTestStatus.FAILED.name());
        factory = new CapturingFactory();
        PublicHttpsUrlValidator validator = new PublicHttpsUrlValidator(
                host -> List.of(InetAddress.getByName("8.8.8.8")));
        provider = new UserModelRuntimeProvider(
                mapper(), cipher, validator, factory);
    }

    @Test
    void resolvesByExplicitUserAndModelIdsEvenWhenLastTestFailed() {
        ResolvedUserModelRuntime runtime = provider.resolve(7L, 41L);

        assertThat(runtime.userId()).isEqualTo(7L);
        assertThat(runtime.modelApiId()).isEqualTo(41L);
        assertThat(runtime.configuration()).isSameAs(existing);
        assertThat(factory.baseUrl).isEqualTo(
                URI.create("https://models.example.com/v1"));
        assertThat(factory.apiKey).isEqualTo("sk-secret");
        assertThat(factory.model).isEqualTo("model-a");
        assertThat(factory.requestOverrides).isEqualTo(
                existing.getRequestOverrides());
    }

    @Test
    void rejectsWhenTheExplicitUserDoesNotOwnTheConfiguration() {
        assertThatThrownBy(() -> provider.resolve(8L, 41L))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("无权");
        assertThat(factory.created).isFalse();
    }

    @Test
    void resolveIfPresentReturnsEmptyWhenTheConfigurationWasDeleted() {
        existing = null;

        assertThat(provider.resolveIfPresent(7L, 41L)).isEmpty();
        assertThat(factory.created).isFalse();
    }

    @Test
    void revalidatesDnsBeforeCreatingTheModel() throws Exception {
        PublicHttpsUrlValidator validator = new PublicHttpsUrlValidator(
                host -> List.of(InetAddress.getByName("127.0.0.1")));
        provider = new UserModelRuntimeProvider(
                mapper(), cipher, validator, factory);

        assertThatThrownBy(() -> provider.resolve(7L, 41L))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("公网");
        assertThat(factory.created).isFalse();
    }

    private UserModelApiMapper mapper() {
        return (UserModelApiMapper) Proxy.newProxyInstance(
                UserModelApiMapper.class.getClassLoader(),
                new Class<?>[]{UserModelApiMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectOwned" -> existing != null
                            && existing.getId().equals(args[0])
                            && existing.getUserId().equals(args[1])
                            ? existing : null;
                    case "toString" -> "UserModelApiMapperFake";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            method.getName());
                });
    }

    private static final class CapturingFactory
            implements UserModelChatModelFactory {
        private final ChatModel modelInstance = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of());
            }
        };
        private boolean created;
        private URI baseUrl;
        private String apiKey;
        private String model;
        private Map<String, Object> requestOverrides;

        @Override
        public ChatModel create(
                URI baseUrl, String apiKey, String model,
                Map<String, Object> requestOverrides) {
            this.created = true;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.requestOverrides = requestOverrides;
            return modelInstance;
        }
    }
}

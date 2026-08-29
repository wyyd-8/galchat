package com.me.galchat.modelapi;

import com.me.galchat.domain.po.UserModelApi;
import com.me.galchat.mapper.UserModelApiMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultModelApiProvisionerTest {

    @Test
    void provisionsAnUntestedDeepSeekProWithoutAnApiKey() {
        AtomicReference<UserModelApi> inserted = new AtomicReference<>();
        UserModelApiMapper mapper = (UserModelApiMapper) Proxy.newProxyInstance(
                UserModelApiMapper.class.getClassLoader(),
                new Class<?>[]{UserModelApiMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "insert" -> {
                        inserted.set((UserModelApi) args[0]);
                        yield 1;
                    }
                    case "toString" -> "UserModelApiMapperFake";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            method.getName());
                });

        new DefaultModelApiProvisioner(mapper).provision(42L);

        UserModelApi model = inserted.get();
        assertThat(model.getUserId()).isEqualTo(42L);
        assertThat(model.getName()).isEqualTo("DeepSeek 主模型");
        assertThat(model.getBaseUrl())
                .isEqualTo("https://api.deepseek.com");
        assertThat(model.getModelName()).isEqualTo("deepseek-v4-pro");
        assertThat(model.getApiKeyEncrypted()).isNull();
        assertThat(model.getApiKeyHint()).isNull();
        assertThat(model.getStatus()).isEqualTo("UNTESTED");
        assertThat(model.getChatCapability()).isEqualTo("UNKNOWN");
        assertThat(model.getStreamingCapability()).isEqualTo("UNKNOWN");
        assertThat(model.getToolCallingCapability()).isEqualTo("UNKNOWN");
        assertThat(model.getReasoningOutputStatus()).isEqualTo("UNKNOWN");
        assertThat(model.getCreatedAt()).isNotNull();
        assertThat(model.getUpdatedAt()).isEqualTo(model.getCreatedAt());
    }
}

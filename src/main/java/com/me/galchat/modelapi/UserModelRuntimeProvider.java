package com.me.galchat.modelapi;

import com.me.galchat.domain.po.UserModelApi;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserModelApiMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

@Component
public class UserModelRuntimeProvider {

    private final UserModelApiMapper mapper;
    private final ApiKeyCipher cipher;
    private final PublicHttpsUrlValidator validator;
    private final UserModelChatModelFactory chatModelFactory;

    public UserModelRuntimeProvider(
            UserModelApiMapper mapper,
            ApiKeyCipher cipher,
            PublicHttpsUrlValidator validator,
            UserModelChatModelFactory chatModelFactory) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.validator = validator;
        this.chatModelFactory = chatModelFactory;
    }

    public ResolvedUserModelRuntime resolve(Long userId, Long modelApiId) {
        return resolveIfPresent(userId, modelApiId)
                .orElseThrow(() -> new UserRequestException(
                        "模型 API 配置不存在或无权操作"));
    }

    public Optional<ResolvedUserModelRuntime> resolveIfPresent(
            Long userId, Long modelApiId) {
        requireIds(userId, modelApiId);
        UserModelApi configuration = mapper.selectOwned(modelApiId, userId);
        if (configuration == null) {
            return Optional.empty();
        }
        URI safeBaseUrl = validator.validateAndNormalize(
                configuration.getBaseUrl());
        ChatModel chatModel = chatModelFactory.create(
                safeBaseUrl,
                cipher.decrypt(configuration.getApiKeyEncrypted()),
                configuration.getModelName(),
                configuration.getRequestOverrides() == null
                        ? Map.of() : configuration.getRequestOverrides());
        return Optional.of(new ResolvedUserModelRuntime(
                userId, modelApiId, configuration, chatModel));
    }

    private void requireIds(Long userId, Long modelApiId) {
        if (userId == null) {
            throw new UserRequestException("用户 id 不能为空");
        }
        if (modelApiId == null || modelApiId <= 0) {
            throw new UserRequestException("模型 API 配置 id 不正确");
        }
    }
}

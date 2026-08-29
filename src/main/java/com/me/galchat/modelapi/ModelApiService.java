package com.me.galchat.modelapi;

import com.me.galchat.domain.dto.ModelApiSaveDTO;
import com.me.galchat.domain.po.UserModelApi;
import com.me.galchat.domain.vo.ModelApiVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserModelApiMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class ModelApiService {

    private final UserModelApiMapper mapper;
    private final ApiKeyCipher cipher;
    private final PublicHttpsUrlValidator validator;
    private final ModelApiProbeService probeService;

    public ModelApiService(
            UserModelApiMapper mapper,
            ApiKeyCipher cipher,
            PublicHttpsUrlValidator validator,
            ModelApiProbeService probeService) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.validator = validator;
        this.probeService = probeService;
    }

    public ModelApiVO create(Long userId, ModelApiSaveDTO dto) {
        requireUserId(userId);
        ValidatedInput input = validate(dto, true);
        LocalDateTime now = LocalDateTime.now();
        UserModelApi modelApi = new UserModelApi()
                .setUserId(userId)
                .setName(input.name())
                .setBaseUrl(input.baseUrl().toString())
                .setModelName(input.modelName())
                .setApiKeyEncrypted(cipher.encrypt(input.apiKey()))
                .setApiKeyHint(keyHint(input.apiKey()))
                .setCreatedAt(now)
                .setUpdatedAt(now);
        resetTestResult(modelApi);
        mapper.insert(modelApi);
        return toVO(modelApi);
    }

    public ModelApiVO update(Long userId, Long id, ModelApiSaveDTO dto) {
        UserModelApi existing = requireOwned(userId, id);
        ValidatedInput input = validate(dto, false);
        boolean hasNewKey = StringUtils.hasText(input.apiKey());
        boolean keyChanged = hasNewKey
                && (!StringUtils.hasText(existing.getApiKeyEncrypted())
                || !Objects.equals(cipher.decrypt(existing.getApiKeyEncrypted()),
                input.apiKey()));
        boolean connectionChanged = !Objects.equals(existing.getBaseUrl(),
                input.baseUrl().toString())
                || !Objects.equals(existing.getModelName(), input.modelName())
                || keyChanged;
        existing.setName(input.name())
                .setBaseUrl(input.baseUrl().toString())
                .setModelName(input.modelName())
                .setUpdatedAt(LocalDateTime.now());
        if (hasNewKey) {
            existing.setApiKeyEncrypted(cipher.encrypt(input.apiKey()))
                    .setApiKeyHint(keyHint(input.apiKey()));
        }
        if (connectionChanged) {
            resetTestResult(existing);
        }
        if (mapper.updateOwned(existing) != 1) {
            throw new UserRequestException("模型 API 配置不存在或无权操作");
        }
        return toVO(existing);
    }

    public void delete(Long userId, Long id) {
        requireUserId(userId);
        requireId(id);
        if (mapper.deleteOwned(id, userId) != 1) {
            throw new UserRequestException("模型 API 配置不存在或无权操作");
        }
    }

    public ModelApiVO test(Long userId, Long id) {
        UserModelApi existing = requireOwned(userId, id);
        if (!StringUtils.hasText(existing.getApiKeyEncrypted())) {
            throw new UserRequestException("请先配置 API Key");
        }
        URI safeBaseUrl = validator.validateAndNormalize(existing.getBaseUrl());
        ModelApiProbeResult result = probeService.probe(
                safeBaseUrl,
                cipher.decrypt(existing.getApiKeyEncrypted()),
                existing.getModelName());
        existing.setStatus(result.status().name())
                .setChatCapability(result.chat().name())
                .setStreamingCapability(result.streaming().name())
                .setToolCallingCapability(result.toolCalling().name())
                .setReasoningOutputStatus(result.reasoningOutput().name())
                .setLastTestCode(result.code())
                .setLastTestMessage(result.message())
                .setLastTestAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        if (mapper.updateTestResult(existing) != 1) {
            throw new UserRequestException("模型 API 配置不存在或无权操作");
        }
        return toVO(existing);
    }

    public List<ModelApiVO> list(Long userId) {
        requireUserId(userId);
        return mapper.selectByUserId(userId).stream()
                .map(this::toVO)
                .toList();
    }

    private UserModelApi requireOwned(Long userId, Long id) {
        requireUserId(userId);
        requireId(id);
        UserModelApi existing = mapper.selectOwned(id, userId);
        if (existing == null) {
            throw new UserRequestException("模型 API 配置不存在或无权操作");
        }
        return existing;
    }

    private ValidatedInput validate(ModelApiSaveDTO dto, boolean keyRequired) {
        if (dto == null) {
            throw new UserRequestException("请求参数不能为空");
        }
        String name = trimmed(dto.getName());
        String model = trimmed(dto.getModelName());
        String apiKey = dto.getApiKey() == null ? null : dto.getApiKey().trim();
        if (!StringUtils.hasText(name) || name.length() > 100) {
            throw new UserRequestException("配置名称不能为空且不能超过 100 字符");
        }
        if (!StringUtils.hasText(model) || model.length() > 255) {
            throw new UserRequestException("模型名称不能为空且不能超过 255 字符");
        }
        if (keyRequired && !StringUtils.hasText(apiKey)) {
            throw new UserRequestException("API Key 不能为空");
        }
        if (apiKey != null && apiKey.length() > 10000) {
            throw new UserRequestException("API Key 过长");
        }
        return new ValidatedInput(name,
                validator.validateAndNormalize(dto.getBaseUrl()),
                model, apiKey);
    }

    private void resetTestResult(UserModelApi modelApi) {
        modelApi.setStatus(ModelApiTestStatus.UNTESTED.name())
                .setChatCapability(ModelApiCapability.UNKNOWN.name())
                .setStreamingCapability(ModelApiCapability.UNKNOWN.name())
                .setToolCallingCapability(ModelApiCapability.UNKNOWN.name())
                .setReasoningOutputStatus(ReasoningOutputStatus.UNKNOWN.name())
                .setLastTestCode(null)
                .setLastTestMessage(null)
                .setLastTestAt(null);
    }

    private ModelApiVO toVO(UserModelApi value) {
        return new ModelApiVO()
                .setId(value.getId())
                .setName(value.getName())
                .setBaseUrl(value.getBaseUrl())
                .setModelName(value.getModelName())
                .setHasApiKey(StringUtils.hasText(value.getApiKeyEncrypted()))
                .setApiKeyHint(value.getApiKeyHint())
                .setStatus(ModelApiTestStatus.valueOf(value.getStatus()))
                .setChatCapability(ModelApiCapability.valueOf(
                        value.getChatCapability()))
                .setStreamingCapability(ModelApiCapability.valueOf(
                        value.getStreamingCapability()))
                .setToolCallingCapability(ModelApiCapability.valueOf(
                        value.getToolCallingCapability()))
                .setReasoningOutputStatus(ReasoningOutputStatus.valueOf(
                        value.getReasoningOutputStatus()))
                .setLastTestCode(value.getLastTestCode())
                .setLastTestMessage(value.getLastTestMessage())
                .setLastTestAt(value.getLastTestAt())
                .setCreatedAt(value.getCreatedAt())
                .setUpdatedAt(value.getUpdatedAt());
    }

    private String keyHint(String apiKey) {
        int start = Math.max(0, apiKey.length() - 4);
        return "…" + apiKey.substring(start);
    }

    private String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private void requireUserId(Long userId) {
        if (userId == null) {
            throw new UserRequestException("用户未登录");
        }
    }

    private void requireId(Long id) {
        if (id == null || id <= 0) {
            throw new UserRequestException("模型 API 配置 id 不正确");
        }
    }

    private record ValidatedInput(
            String name, URI baseUrl, String modelName, String apiKey) {
    }
}

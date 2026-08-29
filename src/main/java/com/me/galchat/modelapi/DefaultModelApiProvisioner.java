package com.me.galchat.modelapi;

import com.me.galchat.domain.po.UserModelApi;
import com.me.galchat.mapper.UserModelApiMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class DefaultModelApiProvisioner {

    private static final String DEFAULT_NAME = "DeepSeek 主模型";
    private static final String DEFAULT_BASE_URL =
            "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-v4-pro";

    private final UserModelApiMapper mapper;

    public DefaultModelApiProvisioner(UserModelApiMapper mapper) {
        this.mapper = mapper;
    }

    public void provision(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        UserModelApi model = new UserModelApi()
                .setUserId(userId)
                .setName(DEFAULT_NAME)
                .setBaseUrl(DEFAULT_BASE_URL)
                .setModelName(DEFAULT_MODEL)
                .setStatus(ModelApiTestStatus.UNTESTED.name())
                .setChatCapability(ModelApiCapability.UNKNOWN.name())
                .setStreamingCapability(ModelApiCapability.UNKNOWN.name())
                .setToolCallingCapability(ModelApiCapability.UNKNOWN.name())
                .setReasoningOutputStatus(
                        ReasoningOutputStatus.UNKNOWN.name())
                .setCreatedAt(now)
                .setUpdatedAt(now);
        if (mapper.insert(model) != 1) {
            throw new IllegalStateException("默认模型配置创建失败");
        }
    }
}

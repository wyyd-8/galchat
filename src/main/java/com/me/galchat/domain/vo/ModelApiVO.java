package com.me.galchat.domain.vo;

import com.me.galchat.modelapi.ModelApiCapability;
import com.me.galchat.modelapi.ModelApiTestStatus;
import com.me.galchat.modelapi.ReasoningOutputStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class ModelApiVO {
    private Long id;
    private String name;
    private String baseUrl;
    private String modelName;
    private Boolean hasApiKey;
    private String apiKeyHint;
    private ModelApiTestStatus status;
    private ModelApiCapability chatCapability;
    private ModelApiCapability streamingCapability;
    private ModelApiCapability toolCallingCapability;
    private ReasoningOutputStatus reasoningOutputStatus;
    private String lastTestCode;
    private String lastTestMessage;
    private LocalDateTime lastTestAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

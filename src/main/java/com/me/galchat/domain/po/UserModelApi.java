package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("user_model_api")
public class UserModelApi {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String baseUrl;
    private String modelName;
    private String apiKeyEncrypted;
    private String apiKeyHint;
    private String status;
    private String chatCapability;
    private String streamingCapability;
    private String toolCallingCapability;
    private String reasoningOutputStatus;
    private String lastTestCode;
    private String lastTestMessage;
    private LocalDateTime lastTestAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

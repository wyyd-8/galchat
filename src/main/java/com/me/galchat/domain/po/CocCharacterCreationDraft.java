package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.me.galchat.domain.dto.CharacterCardGenerationModels;
import com.me.galchat.typehandler.JsonbTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName(value = "coc_character_creation_draft", autoResultMap = true)
public class CocCharacterCreationDraft {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long ownerUserId;
    private Long runId;
    private Long participantId;
    private String creationMode;
    private String status;
    private String currentStep;
    private String nextAction;
    private String operationStatus;
    private Integer version;
    private Integer rulesVersion;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private CharacterCardGenerationModels.DraftState state;
    private String lastRequestId;
    private String lastAction;
    private String lastErrorCode;
    private Long resultCharacterId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

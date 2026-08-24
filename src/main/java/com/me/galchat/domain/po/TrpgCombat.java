package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.me.galchat.typehandler.JsonbTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;
import tools.jackson.databind.JsonNode;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName(value = "trpg_combat", autoResultMap = true)
public class TrpgCombat implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private Long sourceSceneId;
    private String status;
    private String orderMode;
    private Integer currentRound;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private JsonNode participants;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private JsonNode quickNpcSpecs;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private JsonNode activeTurnResults;
    private Long startRequestedStepId;
    private Long finishRequestedStepId;
    private Long startSequence;
    private Long endSequence;
    private String summary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime endedAt;
}

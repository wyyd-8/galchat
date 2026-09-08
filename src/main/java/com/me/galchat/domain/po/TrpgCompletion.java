package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.*;
import com.me.galchat.domain.dto.TrpgCompletionModels;
import com.me.galchat.typehandler.JsonbTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Accessors(chain = true)
@TableName(value = "trpg_completion", autoResultMap = true)
public class TrpgCompletion {
    @TableId(value = "conversation_id", type = IdType.INPUT)
    private Long conversationId;
    private Long turnId;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private TrpgCompletionModels.Data data;
}

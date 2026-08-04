package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("group_turn_checkpoint")
public class GroupTurnCheckpoint implements Serializable {

    @TableId(value = "conversation_id", type = IdType.INPUT)
    private Long conversationId;
    private Long turnId;
    private Long replyStepId;
    private String checkpointType;
    private Long messageId;
    private Long toolCallId;
    private LocalDateTime updatedAt;
}

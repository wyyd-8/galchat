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
@TableName("group_chat_turn")
public class GroupChatTurn implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private Long triggerMessageId;
    private String clientRequestId;
    private String planSource;
    private Long planContextId;
    private String status;
    private Integer revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

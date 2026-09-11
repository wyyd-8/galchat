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
@TableName("group_chat_agent_decision")
public class GroupChatAgentDecision implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long replyStepId;
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

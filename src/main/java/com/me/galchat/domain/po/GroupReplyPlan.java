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
@TableName("group_reply_plan")
public class GroupReplyPlan implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String source;
    private Long contextId;
    private Long resumePlanId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

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
@TableName("group_conversation")
public class GroupConversation implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userWorldId;
    private Long worldId;
    private Long moduleId;
    private Long activeReplyPlanId;
    private String mode;
    private String title;
    private String summary;
    private String status;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime closedAt;
}

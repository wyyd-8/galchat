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
@TableName("group_reply_plan_item")
public class GroupReplyPlanItem implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long planId;
    private Integer itemOrder;
    private String actorType;
    private Long actorId;
    private Long subjectCharacterId;
    private String subjectCharacterName;
    private String participantStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

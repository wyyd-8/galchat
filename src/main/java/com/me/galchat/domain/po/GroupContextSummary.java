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
@TableName("group_context_summary")
public class GroupContextSummary implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private Long sceneId;
    private Long scenePlanId;
    private Long startSequence;
    private Long endSequence;
    private String summary;
    private Integer version;
    private LocalDateTime createdAt;
}

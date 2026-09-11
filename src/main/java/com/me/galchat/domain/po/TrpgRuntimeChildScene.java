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
@TableName("trpg_runtime_child_scene")
public class TrpgRuntimeChildScene implements Serializable {

    @TableId(value = "plan_id", type = IdType.INPUT)
    private Long planId;
    private Long conversationId;
    private String sceneName;
    private Long createdStepId;
    private LocalDateTime createdAt;
}

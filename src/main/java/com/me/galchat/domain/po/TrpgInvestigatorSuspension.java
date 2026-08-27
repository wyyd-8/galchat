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
@TableName("trpg_investigator_suspension")
public class TrpgInvestigatorSuspension implements Serializable {

    public static final String STATE_SUSPENDED = "SUSPENDED";
    public static final String STATE_RECOVERY_QUEUED = "RECOVERY_QUEUED";
    public static final String STATE_REENTRY_PENDING = "REENTRY_PENDING";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private Long subjectCharacterId;
    private String state;
    private String suspensionContext;
    private Long originContextId;
    private String reentryContext;
    private String recoverySceneName;
    private Long recoveryPlanId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

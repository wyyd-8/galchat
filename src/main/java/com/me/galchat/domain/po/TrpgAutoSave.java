package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;
import com.me.galchat.typehandler.JsonbTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName(value = "trpg_auto_save", autoResultMap = true)
public class TrpgAutoSave implements Serializable {

    @TableId(value = "conversation_id", type = IdType.INPUT)
    private Long conversationId;
    private LocalDateTime savedAt;
    private Integer formatVersion;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private TrpgSaveSnapshotDTO snapshot;
}

package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
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
@TableName(value = "trpg_save", autoResultMap = true)
public class TrpgSave implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long conversationId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String remark;
    private LocalDateTime savedAt;
    private Integer formatVersion;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private TrpgSaveSnapshotDTO snapshot;
}

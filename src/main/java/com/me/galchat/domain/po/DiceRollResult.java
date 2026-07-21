package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.me.galchat.domain.vo.DiceRollResultVO;
import com.me.galchat.typehandler.JsonbTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName(value = "dice_roll_result", autoResultMap = true)
public class DiceRollResult implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long summaryId;
    private Long characterId;
    private Integer roundNo;
    private Integer displayOrder;
    private String displayType;
    private String reason;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private DiceRollResultVO resultData;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

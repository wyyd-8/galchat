package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TableName("coc_character_skill")
public class CocCharacterSkill {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long characterId;
    private Long skillDefId;
    private String displayName;
    private String category;
    private String specialization;
    private Integer baseValue;
    private Integer value;
    private Boolean isCustom;
}

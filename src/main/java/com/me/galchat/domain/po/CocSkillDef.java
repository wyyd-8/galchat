package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("coc_skill_def")
public class CocSkillDef {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String name;
    private String category;
    private Integer baseValue;
    private String baseFormula;
    private Boolean allowSpecialization;
    private String parentName;
    private Boolean isCore;
}

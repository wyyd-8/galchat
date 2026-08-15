package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.me.galchat.typehandler.JsonbTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
@TableName(value = "coc_character_weapon", autoResultMap = true)
public class CocCharacterWeapon {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long characterId;
    private String name;
    private String skillName;
    private String damage;
    private String range;
    private String attacksPerRound;
    private Integer ammoCapacity;
    private Integer remainingAmmo;
    private String malfunction;
    private Boolean isBroken;
    private Boolean abnormal;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<String> riskTags;
    private String notes;
}

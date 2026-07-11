package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TableName("coc_character_weapon")
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
    private String notes;
}

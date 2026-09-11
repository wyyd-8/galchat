package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.me.galchat.typehandler.JsonbTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Accessors(chain = true)
@TableName(value = "trpg_weapon_stash", autoResultMap = true)
public class TrpgWeaponStash {

    @TableId(value = "weapon_id", type = IdType.INPUT)
    private Long weaponId;
    private Long runId;
    private String sourceCharacterName;
    private String locationName;
    private String stashReason;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private WeaponSnapshot weaponSnapshot;
    private LocalDateTime stashedAt;

    @Data
    @Accessors(chain = true)
    public static class WeaponSnapshot {
        private String name;
        private String skillName;
        private String damage;
        private String range;
        private String attacksPerRound;
        private Integer ammoCapacity;
        private Integer remainingAmmo;
        private String malfunction;
        private Boolean canImpale;
        private Boolean isBroken;
        private Boolean abnormal;
        private List<String> riskTags;
        private String notes;
    }
}

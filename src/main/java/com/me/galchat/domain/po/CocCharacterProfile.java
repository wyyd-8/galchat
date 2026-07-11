package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TableName("coc_character_profile")
public class CocCharacterProfile {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long characterId;
    private String appearance;
    private String ideology;
    private String significantPeople;
    private String meaningfulLocations;
    private String treasuredPossessions;
    private String traits;
    private String injuriesAndScars;
    private String phobiasAndManias;
    private String equipmentText;
    private String assetsText;
    private String spendingLevel;
    private String cash;
    private String notes;
}

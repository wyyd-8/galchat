package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("coc_character")
public class CocCharacter {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long runId;
    private String actorType;
    private Long participantId;
    private String name;
    private String occupation;
    private String sex;
    private Integer age;
    private String era;
    private String birthplace;
    private String residence;
    private String creationMethod;
    private Integer str;
    private Integer con;
    private Integer siz;
    private Integer dex;
    private Integer app;
    private Integer intValue;
    private Integer pow;
    private Integer edu;
    private String damageBonus;
    private Integer build;
    private Integer mov;
    private Integer hpCurrent;
    private Integer hpMax;
    private Integer sanCurrent;
    private Integer sanMax;
    private Integer mpCurrent;
    private Integer mpMax;
    private Integer luckCurrent;
    private Integer armor;
    private Boolean majorWound;
    private Boolean unconscious;
    private Boolean dying;
    private Boolean dead;
    private Boolean temporaryInsanity;
    private String temporaryInsanityPhase;
    private Integer temporaryInsanityRemainingRounds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

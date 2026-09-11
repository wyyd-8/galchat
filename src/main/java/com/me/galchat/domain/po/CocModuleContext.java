package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("coc_module_context")
public class CocModuleContext implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long moduleId;
    private String truthBackground;
    private String investigatorIntro;
    private String timeline;
    private String specialRules;
    private String keeperGuidance;
    private String endingContent;
    private String extraContent;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

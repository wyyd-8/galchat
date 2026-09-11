package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.me.galchat.typehandler.JsonbTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName(value = "coc_module_character", autoResultMap = true)
public class CocModuleCharacter {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long moduleId;
    private Integer sortOrder;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private JsonNode cardData;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

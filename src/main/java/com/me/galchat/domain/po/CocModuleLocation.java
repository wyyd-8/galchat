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
@TableName("coc_module_location")
public class CocModuleLocation implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long moduleId;
    private Long parentLocationId;
    private String name;
    private String summary;
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

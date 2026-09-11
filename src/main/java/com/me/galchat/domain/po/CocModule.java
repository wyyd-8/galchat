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
@TableName("coc_module")
public class CocModule implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String name;
    private String author;
    private String era;
    private String introduction;
    private String investigatorCreation;
    private String coverUrl;
    private String playerCount;
    private String estimatedDuration;
    private Boolean visible;
    private Long ownerUserId;
    private Boolean editLocked;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 
 * </p>
 *
 * @author author
 * @since 2026-05-08
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_character_info")
public class UserCharacterInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "user_world_id", type = IdType.INPUT)
    private Long userWorldId;

    private Long characterId;

    private String characterName;

    private String characterImage;

    private LocalDateTime lastChatTime;

    private String lastChatContent;

    private Integer favorValue;

    private String userInfoPrompt;

    private Long modelApiId;


}

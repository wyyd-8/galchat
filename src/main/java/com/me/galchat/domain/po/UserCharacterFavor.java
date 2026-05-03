package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
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
 * @since 2026-05-03
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_character_favor")
public class UserCharacterFavor implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "user_world_id", type = IdType.AUTO)
    private Long userWorldId;

    private Long characterId;

    private Integer favorValue;


}

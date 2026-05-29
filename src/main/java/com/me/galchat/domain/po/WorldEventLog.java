package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.apache.ibatis.type.ArrayTypeHandler;

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
@TableName(value = "world_event_log", autoResultMap = true)
public class WorldEventLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userWorldId;

    private String eventDescription;

    @TableField(typeHandler = ArrayTypeHandler.class)
    private Long[] visibleCharacters;

    private LocalDateTime timestamp;

    private String title;

    private Long storyEventId;
}

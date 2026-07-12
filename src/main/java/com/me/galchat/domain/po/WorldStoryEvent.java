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
 * @since 2026-05-27
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("world_story_event")
public class WorldStoryEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userWorldId;

    private String title;

    private String theme;

    private String currentScene;

    private String opening;

    private String summary;

    private Long conversationId;

    private String status;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;


}

package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.me.galchat.domain.dto.UserWorldSaveSnapshotDTO;
import com.me.galchat.domain.vo.UserWorldSaveOverviewVO;
import com.me.galchat.typehandler.JsonbTypeHandler;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "user_world_save", autoResultMap = true)
public class UserWorldSave implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long userWorldId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String remark;

    private LocalDateTime savedAt;

    private Integer formatVersion;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<UserWorldSaveOverviewVO.CharacterFavorVO> characterFavors;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private UserWorldSaveSnapshotDTO snapshot;
}

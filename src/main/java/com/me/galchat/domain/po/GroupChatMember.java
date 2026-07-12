package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
@TableName("group_chat_member")
public class GroupChatMember implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String actorType;
    private Long actorId;
    private Integer position;
    private Boolean enabled;
    private Double talkativeness;
}

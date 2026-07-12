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
@TableName("group_chat_thinking")
public class GroupChatThinking implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long messageId;
    private String reasoningContent;
    private LocalDateTime createdAt;
}

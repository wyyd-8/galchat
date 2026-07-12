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
@TableName("group_chat_reply_step")
public class GroupChatReplyStep implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long turnId;
    private Integer stepNo;
    private String speakerType;
    private Long speakerId;
    private Boolean forceReply;
    private String status;
    private Long outputMessageId;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

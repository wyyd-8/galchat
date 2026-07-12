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
@TableName("group_chat_message")
public class GroupChatMessage implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private Long sceneId;
    private Long turnId;
    private Long replyStepId;
    private String speakerType;
    private Long speakerId;
    private String messageKind;
    private String visibility;
    private String content;
    private Long sequenceNo;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

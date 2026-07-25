package com.me.galchat.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
@TableName("group_chat_tool_call")
public class GroupChatToolCall implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long replyStepId;
    private Integer toolStepNo;
    private String toolCallId;
    private String toolName;
    private String toolArguments;
    private String toolResult;
    private Long diceRollSummaryId;
}

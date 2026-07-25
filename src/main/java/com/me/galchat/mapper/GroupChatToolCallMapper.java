package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.GroupChatToolCall;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface GroupChatToolCallMapper extends BaseMapper<GroupChatToolCall> {

    @Select("""
            SELECT COALESCE(MAX(tool_step_no), 0) + 1
            FROM group_chat_tool_call
            WHERE reply_step_id = #{replyStepId}
            """)
    Integer nextToolStepNo(@Param("replyStepId") Long replyStepId);

    @Update("""
            UPDATE group_chat_tool_call
            SET dice_roll_summary_id = #{diceRollSummaryId}
            WHERE reply_step_id = #{replyStepId}
              AND tool_call_id = #{toolCallId}
            """)
    int bindDiceSummary(@Param("replyStepId") Long replyStepId,
                        @Param("toolCallId") String toolCallId,
                        @Param("diceRollSummaryId") Long diceRollSummaryId);
}

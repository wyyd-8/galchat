package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.GroupChatToolCall;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Set;

public interface GroupChatToolCallMapper extends BaseMapper<GroupChatToolCall> {

    @Delete("""
            DELETE FROM group_chat_tool_call
            WHERE reply_step_id = #{replyStepId}
              AND id > #{toolCallId}
            """)
    int deleteAfterCheckpoint(
            @Param("replyStepId") Long replyStepId,
            @Param("toolCallId") Long toolCallId);

    @Select("""
            SELECT COALESCE(MAX(id), 0)
            FROM group_chat_tool_call
            WHERE reply_step_id = #{replyStepId}
            """)
    Long selectMaxIdByReplyStepId(
            @Param("replyStepId") Long replyStepId);

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

    @Select("""
            SELECT tool_name
            FROM group_chat_tool_call
            WHERE dice_roll_summary_id = #{diceRollSummaryId}
            ORDER BY id DESC
            LIMIT 1
            """)
    String findToolNameByDiceRollSummaryId(
            @Param("diceRollSummaryId") Long diceRollSummaryId);

    @Select("""
            <script>
            SELECT tool_call.dice_roll_summary_id
            FROM group_chat_tool_call tool_call
            JOIN group_chat_reply_step reply_step
              ON reply_step.id = tool_call.reply_step_id
            JOIN group_chat_turn turn_row
              ON turn_row.id = reply_step.turn_id
            WHERE turn_row.conversation_id = #{conversationId}
              AND tool_call.dice_roll_summary_id IS NOT NULL
              AND tool_call.tool_name IN
              <foreach collection="toolNames" item="toolName"
                       open="(" separator="," close=")">
                #{toolName}
              </foreach>
            ORDER BY tool_call.id DESC
            LIMIT 1
            </script>
            """)
    Long findLatestDiceSummaryId(
            @Param("conversationId") Long conversationId,
            @Param("toolNames") Set<String> toolNames);
}

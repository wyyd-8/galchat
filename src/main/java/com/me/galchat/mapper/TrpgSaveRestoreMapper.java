package com.me.galchat.mapper;

import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TrpgSaveRestoreMapper {

    @Select("""
            SELECT
              COALESCE((SELECT MAX(id) FROM group_chat_message
                        WHERE conversation_id = #{conversationId}), 0) AS max_message_id,
              COALESCE((SELECT MAX(id) FROM group_chat_turn
                        WHERE conversation_id = #{conversationId}), 0) AS max_turn_id,
              COALESCE((SELECT MAX(step.id)
                        FROM group_chat_reply_step step
                        JOIN group_chat_turn turn_row ON turn_row.id = step.turn_id
                        WHERE turn_row.conversation_id = #{conversationId}), 0) AS max_reply_step_id,
              COALESCE((SELECT MAX(tool_call.id)
                        FROM group_chat_tool_call tool_call
                        JOIN group_chat_reply_step step ON step.id = tool_call.reply_step_id
                        JOIN group_chat_turn turn_row ON turn_row.id = step.turn_id
                        WHERE turn_row.conversation_id = #{conversationId}), 0) AS max_tool_call_id,
              COALESCE((SELECT MAX(decision.id)
                        FROM group_chat_agent_decision decision
                        JOIN group_chat_reply_step step ON step.id = decision.reply_step_id
                        JOIN group_chat_turn turn_row ON turn_row.id = step.turn_id
                        WHERE turn_row.conversation_id = #{conversationId}), 0) AS max_agent_decision_id,
              COALESCE((SELECT MAX(id) FROM group_context_summary
                        WHERE conversation_id = #{conversationId}), 0) AS max_context_summary_id,
              COALESCE((SELECT MAX(id) FROM group_chat_topic
                        WHERE conversation_id = #{conversationId}), 0) AS max_topic_id,
              COALESCE((SELECT MAX(id) FROM dice_roll_summary
                        WHERE conversation_id = #{conversationId}), 0) AS max_dice_summary_id,
              COALESCE((SELECT MAX(result.id)
                        FROM dice_roll_result result
                        JOIN dice_roll_summary summary ON summary.id = result.summary_id
                        WHERE summary.conversation_id = #{conversationId}), 0) AS max_dice_result_id,
              COALESCE((SELECT MAX(id) FROM world_event_log
                        WHERE conversation_id = #{conversationId}), 0) AS max_world_event_log_id
            """)
    TrpgSaveSnapshotDTO.CursorSnapshot selectCursors(
            @Param("conversationId") Long conversationId);

    int deleteAgentDecisionsAfter(
            @Param("conversationId") Long conversationId,
            @Param("maxId") Long maxId);

    int deleteToolCallsAfter(
            @Param("conversationId") Long conversationId,
            @Param("maxId") Long maxId);

    int deleteMessagesAfter(
            @Param("conversationId") Long conversationId,
            @Param("maxId") Long maxId);

    int deleteReplyStepsAfter(
            @Param("conversationId") Long conversationId,
            @Param("maxId") Long maxId);

    int deleteTurnsAfter(
            @Param("conversationId") Long conversationId,
            @Param("maxId") Long maxId);

    int deleteContextSummariesAfter(
            @Param("conversationId") Long conversationId,
            @Param("maxId") Long maxId);

    int deleteTopicsAfter(
            @Param("conversationId") Long conversationId,
            @Param("maxId") Long maxId);

    int deleteDiceResultsAfter(
            @Param("conversationId") Long conversationId,
            @Param("maxSummaryId") Long maxSummaryId,
            @Param("maxResultId") Long maxResultId);

    int deleteDiceSummariesAfter(
            @Param("conversationId") Long conversationId,
            @Param("maxId") Long maxId);

    int deleteWorldEventsAfter(
            @Param("conversationId") Long conversationId,
            @Param("maxId") Long maxId);
}

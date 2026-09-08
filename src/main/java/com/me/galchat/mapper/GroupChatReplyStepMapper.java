package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.GroupChatReplyStep;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface GroupChatReplyStepMapper extends BaseMapper<GroupChatReplyStep> {
    @Select("""
            SELECT COUNT(*) FROM group_chat_reply_step step
            JOIN group_chat_tool_call tool ON tool.reply_step_id = step.id
            WHERE step.turn_id = #{turnId} AND step.status = 'completed'
              AND tool.tool_name = 'finishRun' AND tool.tool_result IS NOT NULL
            """)
    Long countCompletedRunFinishByTurn(@Param("turnId") Long turnId);


    @Select("""
            SELECT COALESCE(MAX(step.id), 0)
            FROM group_chat_reply_step step
            JOIN group_chat_turn turn_row ON turn_row.id = step.turn_id
            JOIN group_conversation conversation ON conversation.id = turn_row.conversation_id
            WHERE conversation.user_world_id = #{userWorldId}
              AND conversation.mode = 'chat'
            """)
    Long selectMaxIdByUserWorldId(@Param("userWorldId") Long userWorldId);

    @Select("""
            SELECT COUNT(*)
            FROM group_chat_reply_step step
            JOIN group_chat_turn turn_row ON turn_row.id = step.turn_id
            WHERE turn_row.plan_id = #{planId}
              AND step.action_type = #{actionType}
              AND step.status = 'completed'
            """)
    Long countCompletedActionByPlanId(
            @Param("planId") Long planId,
            @Param("actionType") String actionType);
}

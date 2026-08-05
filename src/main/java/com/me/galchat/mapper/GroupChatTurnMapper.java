package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.GroupChatTurn;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface GroupChatTurnMapper extends BaseMapper<GroupChatTurn> {

    @Select("""
            SELECT COALESCE(MAX(turn_row.id), 0)
            FROM group_chat_turn turn_row
            JOIN group_conversation conversation ON conversation.id = turn_row.conversation_id
            WHERE conversation.user_world_id = #{userWorldId}
              AND conversation.mode = 'chat'
            """)
    Long selectMaxIdByUserWorldId(@Param("userWorldId") Long userWorldId);

    @Select("""
            SELECT COUNT(*)
            FROM group_chat_turn turn_row
            JOIN group_conversation conversation ON conversation.id = turn_row.conversation_id
            WHERE conversation.user_world_id = #{userWorldId}
              AND conversation.mode = 'chat'
              AND turn_row.status IN ('pending', 'running', 'waiting_input')
            """)
    Long countNonTerminalByUserWorldId(@Param("userWorldId") Long userWorldId);

    @Select("""
            SELECT COUNT(*)
            FROM group_chat_turn
            WHERE conversation_id = #{conversationId}
              AND status IN ('pending', 'running', 'waiting_input')
            """)
    Long countNonTerminalByConversationId(@Param("conversationId") Long conversationId);

    @Select("""
            SELECT COALESCE(MAX(turn_row.id), 0)
            FROM group_chat_turn turn_row
            WHERE turn_row.conversation_id = #{conversationId}
              AND turn_row.plan_source = 'SCENE'
              AND turn_row.status = 'completed'
              AND EXISTS (
                    SELECT 1
                    FROM group_chat_reply_step step
                    WHERE step.turn_id = turn_row.id
                      AND step.action_type = 'trpg_scene'
                      AND step.speaker_type IN ('user', 'character')
                      AND step.status = 'completed'
              )
            """)
    Long selectLatestCompletedSceneProposalTurnId(
            @Param("conversationId") Long conversationId);

}

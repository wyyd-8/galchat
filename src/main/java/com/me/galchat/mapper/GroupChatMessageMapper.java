package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.GroupChatMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface GroupChatMessageMapper extends BaseMapper<GroupChatMessage> {

    @Select("""
            SELECT COALESCE(MAX(id), 0)
            FROM group_chat_message
            WHERE reply_step_id = #{replyStepId}
            """)
    Long selectMaxIdByReplyStepId(
            @Param("replyStepId") Long replyStepId);

    @Delete("""
            DELETE FROM group_chat_message
            WHERE reply_step_id = #{replyStepId}
              AND id > #{messageId}
            """)
    int deleteAfterCheckpoint(
            @Param("replyStepId") Long replyStepId,
            @Param("messageId") Long messageId);

    @Select("""
            SELECT COALESCE(MAX(message.id), 0)
            FROM group_chat_message message
            JOIN group_conversation conversation ON conversation.id = message.conversation_id
            WHERE conversation.user_world_id = #{userWorldId}
              AND conversation.mode = 'chat'
            """)
    Long selectMaxIdByUserWorldId(@Param("userWorldId") Long userWorldId);

    @Select("""
            SELECT message.*
            FROM group_chat_message message
            JOIN group_chat_turn turn_row
              ON turn_row.id = message.turn_id
            WHERE message.conversation_id = #{conversationId}
              AND turn_row.plan_id = #{planId}
              AND message.status = 'completed'
              AND message.visibility = 'public'
            ORDER BY message.sequence_no ASC
            """)
    List<GroupChatMessage> selectCompletedPublicByPlanId(
            @Param("conversationId") Long conversationId,
            @Param("planId") Long planId);

    List<GroupChatMessage> selectLatestCompletedByConversationIds(
            @Param("conversationIds") List<Long> conversationIds);
}

package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.GroupTurnCheckpoint;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface GroupTurnCheckpointMapper
        extends BaseMapper<GroupTurnCheckpoint> {

    @Delete("""
            DELETE FROM group_turn_checkpoint checkpoint
            USING group_conversation conversation
            WHERE conversation.id = checkpoint.conversation_id
              AND conversation.user_world_id = #{userWorldId}
            """)
    int deleteByUserWorldId(@Param("userWorldId") Long userWorldId);

    @Insert("""
            INSERT INTO group_turn_checkpoint (
                conversation_id, turn_id, reply_step_id,
                checkpoint_type, message_id, tool_call_id,
                updated_at
            ) VALUES (
                #{checkpoint.conversationId}, #{checkpoint.turnId},
                #{checkpoint.replyStepId},
                #{checkpoint.checkpointType}, #{checkpoint.messageId},
                #{checkpoint.toolCallId}, #{checkpoint.updatedAt}
            )
            ON CONFLICT (conversation_id) DO UPDATE SET
                turn_id = EXCLUDED.turn_id,
                reply_step_id = EXCLUDED.reply_step_id,
                checkpoint_type = EXCLUDED.checkpoint_type,
                message_id = EXCLUDED.message_id,
                tool_call_id = EXCLUDED.tool_call_id,
                updated_at = EXCLUDED.updated_at
            """)
    int upsert(@Param("checkpoint") GroupTurnCheckpoint checkpoint);
}

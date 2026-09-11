package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.GroupChatAgentDecision;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface GroupChatAgentDecisionMapper
        extends BaseMapper<GroupChatAgentDecision> {

    @Select("""
            SELECT decision.*
            FROM group_chat_agent_decision decision
            JOIN group_chat_reply_step step
              ON step.id = decision.reply_step_id
            JOIN group_chat_turn turn_row
              ON turn_row.id = step.turn_id
            WHERE turn_row.conversation_id = #{conversationId}
              AND step.action_type = #{actionType}
              AND step.speaker_type = 'character'
              AND step.speaker_id = #{actorId}
              AND step.group_key = #{groupKey}
              AND step.status = 'completed'
            ORDER BY turn_row.id, step.step_no
            """)
    List<GroupChatAgentDecision> selectCompletedForActorContext(
            @Param("conversationId") Long conversationId,
            @Param("actionType") String actionType,
            @Param("actorId") Long actorId,
            @Param("groupKey") String groupKey);
}

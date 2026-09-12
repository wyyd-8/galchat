package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.GroupConversation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface GroupConversationMapper extends BaseMapper<GroupConversation> {

    @Select("""
            SELECT conversation.*
            FROM group_conversation conversation
            WHERE conversation.user_world_id = #{userWorldId}
              AND conversation.mode = 'trpg'
              AND EXISTS (SELECT 1 FROM group_chat_member member
                  WHERE member.conversation_id = conversation.id
                    AND member.actor_type = 'character' AND member.actor_id = #{characterId})
            ORDER BY conversation.updated_at DESC, conversation.id DESC
            LIMIT 3
            """)
    List<GroupConversation> selectRecentTrpgByCharacter(
            @Param("userWorldId") Long userWorldId, @Param("characterId") Long characterId);

    @Select("""
            SELECT COALESCE(MAX(id), 0)
            FROM group_conversation
            WHERE user_world_id = #{userWorldId}
              AND mode = 'chat'
            """)
    Long selectMaxIdByUserWorldId(@Param("userWorldId") Long userWorldId);

    @Select("""
            SELECT DISTINCT conversation.*
            FROM group_conversation conversation
            JOIN group_chat_member member ON member.conversation_id = conversation.id
            WHERE conversation.user_world_id = #{userWorldId}
              AND conversation.status = 'active'
              AND conversation.mode = 'chat'
              AND member.actor_type = 'character'
              AND member.actor_id = #{characterId}
              AND member.enabled = TRUE
            ORDER BY conversation.updated_at DESC NULLS LAST, conversation.id DESC
            """)
    List<GroupConversation> selectActiveChatByCharacter(@Param("userWorldId") Long userWorldId,
                                                        @Param("characterId") Long characterId);
}

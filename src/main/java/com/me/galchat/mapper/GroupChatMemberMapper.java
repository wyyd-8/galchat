package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.GroupChatMember;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface GroupChatMemberMapper extends BaseMapper<GroupChatMember> {

    @Select("""
            SELECT COUNT(*)
            FROM group_chat_member member
            JOIN group_conversation conversation ON conversation.id = member.conversation_id
            WHERE conversation.user_world_id = #{userWorldId}
              AND conversation.status = 'active'
              AND member.actor_type = 'character'
              AND member.actor_id = #{characterId}
            """)
    Long countActiveConversations(@Param("userWorldId") Long userWorldId,
                                  @Param("characterId") Long characterId);
}

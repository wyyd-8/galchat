package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.GroupChatMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface GroupChatMessageMapper extends BaseMapper<GroupChatMessage> {

    @Select("""
            SELECT COALESCE(MAX(message.id), 0)
            FROM group_chat_message message
            JOIN group_conversation conversation ON conversation.id = message.conversation_id
            WHERE conversation.user_world_id = #{userWorldId}
            """)
    Long selectMaxIdByUserWorldId(@Param("userWorldId") Long userWorldId);
}

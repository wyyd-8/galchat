package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.GroupChatThinking;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface GroupChatThinkingMapper extends BaseMapper<GroupChatThinking> {

    @Select("""
            SELECT COALESCE(MAX(thinking.id), 0)
            FROM group_chat_thinking thinking
            JOIN group_chat_message message ON message.id = thinking.message_id
            JOIN group_conversation conversation ON conversation.id = message.conversation_id
            WHERE conversation.user_world_id = #{userWorldId}
            """)
    Long selectMaxIdByUserWorldId(@Param("userWorldId") Long userWorldId);
}

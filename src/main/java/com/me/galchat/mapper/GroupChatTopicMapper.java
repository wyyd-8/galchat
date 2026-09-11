package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.GroupChatTopic;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface GroupChatTopicMapper extends BaseMapper<GroupChatTopic> {

    @Select("""
            SELECT COALESCE(MAX(topic.id), 0)
            FROM group_chat_topic topic
            JOIN group_conversation conversation ON conversation.id = topic.conversation_id
            WHERE conversation.user_world_id = #{userWorldId}
              AND conversation.mode = 'chat'
            """)
    Long selectMaxIdByUserWorldId(@Param("userWorldId") Long userWorldId);
}

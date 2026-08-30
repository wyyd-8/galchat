package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.GroupActorRuntimeConfig;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface GroupActorRuntimeConfigMapper
        extends BaseMapper<GroupActorRuntimeConfig> {

    @Select("""
            SELECT * FROM group_actor_runtime_config
            WHERE conversation_id = #{conversationId}
            ORDER BY id
            """)
    List<GroupActorRuntimeConfig> selectByConversationId(
            @Param("conversationId") Long conversationId);

    @Select("""
            SELECT * FROM group_actor_runtime_config
            WHERE conversation_id = #{conversationId}
              AND actor_key = #{actorKey}
            LIMIT 1
            """)
    GroupActorRuntimeConfig selectByActorKey(
            @Param("conversationId") Long conversationId,
            @Param("actorKey") String actorKey);
}

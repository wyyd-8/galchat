package com.me.galchat.mapper;

import com.me.galchat.domain.po.WorldEventLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
public interface WorldEventLogMapper extends BaseMapper<WorldEventLog> {

    @Select("""
            SELECT COALESCE(MAX(event.id), 0)
            FROM world_event_log event
            LEFT JOIN group_conversation conversation
              ON conversation.id = event.conversation_id
            WHERE event.user_world_id = #{userWorldId}
              AND (event.conversation_id IS NULL OR conversation.mode = 'chat')
            """)
    Long selectMaxRestorableId(@Param("userWorldId") Long userWorldId);

    @Select("""
            SELECT event.*
            FROM world_event_log event
            LEFT JOIN group_conversation conversation
              ON conversation.id = event.conversation_id
            WHERE event.user_world_id = #{userWorldId}
              AND (event.conversation_id IS NULL OR conversation.mode = 'chat')
            ORDER BY event.id DESC
            LIMIT 1
            """)
    WorldEventLog selectLastRestorable(@Param("userWorldId") Long userWorldId);

    int deleteByUserWorldId(@Param("userWorldId") Long userWorldId);
}

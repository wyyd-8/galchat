package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.GroupContextSummary;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface GroupContextSummaryMapper extends BaseMapper<GroupContextSummary> {

    @Select("""
            SELECT COALESCE(MAX(summary.id), 0)
            FROM group_context_summary summary
            JOIN group_conversation conversation ON conversation.id = summary.conversation_id
            WHERE conversation.user_world_id = #{userWorldId}
            """)
    Long selectMaxIdByUserWorldId(@Param("userWorldId") Long userWorldId);
}

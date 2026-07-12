package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.GroupConversation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface GroupConversationMapper extends BaseMapper<GroupConversation> {

    @Select("SELECT COALESCE(MAX(id), 0) FROM group_conversation WHERE user_world_id = #{userWorldId}")
    Long selectMaxIdByUserWorldId(@Param("userWorldId") Long userWorldId);
}

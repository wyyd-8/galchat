package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.TrpgSave;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TrpgSaveMapper extends BaseMapper<TrpgSave> {

    @Select("SELECT * FROM trpg_save WHERE conversation_id = #{conversationId} LIMIT 1")
    TrpgSave selectByConversationId(@Param("conversationId") Long conversationId);
}

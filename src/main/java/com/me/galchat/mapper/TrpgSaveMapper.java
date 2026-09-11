package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.TrpgSave;
import com.me.galchat.typehandler.JsonbTypeHandler;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

public interface TrpgSaveMapper extends BaseMapper<TrpgSave> {

    @Results(id = "trpgSaveResultMap", value = {
            @Result(column = "snapshot", property = "snapshot",
                    typeHandler = JsonbTypeHandler.class)
    })
    @Select("SELECT * FROM trpg_save WHERE conversation_id = #{conversationId} LIMIT 1")
    TrpgSave selectByConversationId(@Param("conversationId") Long conversationId);

    @Delete("DELETE FROM trpg_save WHERE conversation_id = #{conversationId}")
    int deleteByConversationId(@Param("conversationId") Long conversationId);
}

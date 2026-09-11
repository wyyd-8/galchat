package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.CocCharacter;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CocCharacterMapper extends BaseMapper<CocCharacter> {

    @Select("SELECT * FROM coc_character WHERE id = #{id} AND run_id = #{runId} FOR UPDATE")
    CocCharacter selectByIdAndRunIdForUpdate(
            @Param("runId") Long runId, @Param("id") Long id);
}

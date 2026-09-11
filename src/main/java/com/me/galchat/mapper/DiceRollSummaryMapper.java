package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.DiceRollSummary;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface DiceRollSummaryMapper extends BaseMapper<DiceRollSummary> {

    @Select("SELECT * FROM dice_roll_summary WHERE id = #{id} FOR UPDATE")
    DiceRollSummary selectByIdForUpdate(@Param("id") Long id);
}

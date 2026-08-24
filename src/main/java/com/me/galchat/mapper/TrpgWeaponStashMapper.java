package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.TrpgWeaponStash;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TrpgWeaponStashMapper
        extends BaseMapper<TrpgWeaponStash> {

    @Select("""
            SELECT * FROM trpg_weapon_stash
            WHERE run_id = #{runId} AND weapon_id = #{weaponId}
            FOR UPDATE
            """)
    TrpgWeaponStash selectByRunIdAndWeaponIdForUpdate(
            @Param("runId") Long runId,
            @Param("weaponId") Long weaponId);
}

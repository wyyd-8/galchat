package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.CocCharacterWeapon;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface CocCharacterWeaponMapper extends BaseMapper<CocCharacterWeapon> {

    @Select("""
            SELECT * FROM coc_character_weapon
            WHERE character_id = #{characterId} AND name = #{weaponName}
            ORDER BY id
            FOR UPDATE
            """)
    List<CocCharacterWeapon> selectByCharacterIdAndNameForUpdate(
            @Param("characterId") Long characterId,
            @Param("weaponName") String weaponName);
}

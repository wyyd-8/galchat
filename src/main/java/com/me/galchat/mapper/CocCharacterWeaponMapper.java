package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.CocCharacterWeapon;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface CocCharacterWeaponMapper extends BaseMapper<CocCharacterWeapon> {

    @Select("""
            SELECT * FROM coc_character_weapon
            WHERE character_id = #{characterId} AND name = #{weaponName}
            ORDER BY id
            FOR UPDATE
            """)
    @Results(value = {
            @Result(column = "risk_tags", property = "riskTags",
                    typeHandler = com.me.galchat.typehandler.JsonbTypeHandler.class)
    })
    List<CocCharacterWeapon> selectByCharacterIdAndNameForUpdate(
            @Param("characterId") Long characterId,
            @Param("weaponName") String weaponName);
}

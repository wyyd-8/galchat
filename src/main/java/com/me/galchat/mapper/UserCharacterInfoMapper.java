package com.me.galchat.mapper;

import com.me.galchat.domain.po.UserCharacterInfo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-05-08
 */
public interface UserCharacterInfoMapper extends BaseMapper<UserCharacterInfo> {

    Integer updateFavorValue(@Param("userWorldId") Long userWorldId,
                             @Param("characterId") Long characterId,
                             @Param("favorChange") Integer favorChange);
}

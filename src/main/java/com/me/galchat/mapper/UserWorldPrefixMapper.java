package com.me.galchat.mapper;

import com.me.galchat.domain.po.UserWorldPrefix;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
public interface UserWorldPrefixMapper extends BaseMapper<UserWorldPrefix> {

    @Select("SELECT background FROM world_template WHERE id = #{worldId}")
    String getBackground(Long worldId);
}

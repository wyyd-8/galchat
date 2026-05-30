package com.me.galchat.mapper;

import com.me.galchat.domain.po.UserWorldPrefix;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
public interface UserWorldPrefixMapper extends BaseMapper<UserWorldPrefix> {

    String getBackground(Long worldId);

    UserWorldPrefix selectByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}

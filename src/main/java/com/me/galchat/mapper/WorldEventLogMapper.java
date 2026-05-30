package com.me.galchat.mapper;

import com.me.galchat.domain.po.WorldEventLog;
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
public interface WorldEventLogMapper extends BaseMapper<WorldEventLog> {

    int deleteByUserWorldId(@Param("userWorldId") Long userWorldId);
}

package com.me.galchat.mapper;

import com.me.galchat.domain.po.WorldStoryEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-05-27
 */
public interface WorldStoryEventMapper extends BaseMapper<WorldStoryEvent> {

    int deleteByUserWorldIdWithCharacters(@Param("userWorldId") Long userWorldId);
}

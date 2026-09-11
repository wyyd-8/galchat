package com.me.galchat.mapper;

import com.me.galchat.domain.po.WorldTemplate;
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
public interface WorldTemplateMapper extends BaseMapper<WorldTemplate> {

    WorldTemplate selectAccessibleByIdForCreate(@Param("id") Long id, @Param("userId") Long userId);

    int deleteOwnedTemplateIfUnused(@Param("id") Long id, @Param("authorId") Long authorId);
}

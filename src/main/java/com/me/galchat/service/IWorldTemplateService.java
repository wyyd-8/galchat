package com.me.galchat.service;

import com.me.galchat.domain.po.WorldTemplate;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author author
 * @since 2026-05-08
 */
public interface IWorldTemplateService extends IService<WorldTemplate> {

    List<WorldTemplate> listWorldBaseInfo();

    WorldTemplate getWorldTemplateById(Long id);
}

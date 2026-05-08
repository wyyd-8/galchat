package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.WorldTemplateMapper;
import com.me.galchat.service.IWorldTemplateService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-08
 */
@Service
public class WorldTemplateServiceImpl extends ServiceImpl<WorldTemplateMapper, WorldTemplate> implements IWorldTemplateService {

    @Override
    public List<WorldTemplate> listWorldBaseInfo() {
        return lambdaQuery()
                .select(WorldTemplate::getId, WorldTemplate::getName, WorldTemplate::getImage)
                .list();
    }

    @Override
    public WorldTemplate getWorldTemplateById(Long id) {
        WorldTemplate template = getById(id);
        if (template == null) {
            throw new UserRequestException("世界模板不存在");
        }
        return template;
    }
}

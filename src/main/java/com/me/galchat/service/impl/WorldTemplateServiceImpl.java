package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.WorldTemplateMapper;
import com.me.galchat.service.IWorldTemplateService;
import com.me.galchat.utils.ImageSecurityUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

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
    public List<WorldTemplate> listWorldBaseInfo(Long userId) {
        return lambdaQuery()
                .select(WorldTemplate::getId, WorldTemplate::getName, WorldTemplate::getImage)
                .and(wrapper -> wrapper.ne(WorldTemplate::getVisible, false)
                        .or()
                        .isNull(WorldTemplate::getVisible)
                        .or()
                        .eq(WorldTemplate::getAuthorId, userId))
                .list();
    }

    @Override
    public WorldTemplate getWorldTemplateById(Long userId, Long id) {
        WorldTemplate template = getById(id);
        if (template == null) {
            throw new UserRequestException("世界模板不存在");
        }
        if (Boolean.FALSE.equals(template.getVisible()) && !Objects.equals(template.getAuthorId(), userId)) {
            throw new UserRequestException("世界模板不存在");
        }
        return template;
    }

    @Override
    public void createWorldTemplate(Long userId, WorldTemplate worldTemplate) {
        if (worldTemplate == null) {
            throw new UserRequestException("请求参数不能为空");
        }
        String image = ImageSecurityUtils.normalizeOssImageUrl(worldTemplate.getImage());
        WorldTemplate newWorldTemplate = new WorldTemplate()
                .setName(worldTemplate.getName())
                .setImage(image)
                .setAuthor(worldTemplate.getAuthor())
                .setAuthorId(userId)
                .setBackground(worldTemplate.getBackground())
                .setCharacterIds(worldTemplate.getCharacterIds())
                .setVisible(!Boolean.FALSE.equals(worldTemplate.getVisible()));
        save(newWorldTemplate);
    }
}

package com.me.galchat.service.impl.world;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.WorldTemplateMapper;
import com.me.galchat.service.IWorldTemplateService;
import com.me.galchat.utils.ImageSecurityUtils;
import org.springframework.cache.annotation.CacheEvict;
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
    public WorldTemplate getWorldTemplateForCreate(Long userId, Long id) {
        WorldTemplate template = baseMapper.selectAccessibleByIdForCreate(id, userId);
        if (template == null) {
            throw new UserRequestException("世界模板不存在");
        }
        return template;
    }

    @Override
    public WorldTemplate getOwnWorldTemplate(Long userId, Long id) {
        WorldTemplate template = getById(id);
        if (template == null) {
            throw new UserRequestException("世界模板不存在");
        }
        if (!Objects.equals(template.getAuthorId(), userId)) {
            throw new UserAuthException("无权操作该世界模板");
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
                .setVisible(!Boolean.FALSE.equals(worldTemplate.getVisible()));
        save(newWorldTemplate);
    }

    @Override
    @CacheEvict(cacheNames = "worldPrompt", key = "#id")
    public void updateWorldTemplate(Long userId, Long id, WorldTemplate worldTemplate) {
        if (worldTemplate == null) {
            throw new UserRequestException("请求参数不能为空");
        }
        WorldTemplate oldWorldTemplate = getOwnWorldTemplate(userId, id);
        boolean visible = worldTemplate.getVisible() == null
                ? !Boolean.FALSE.equals(oldWorldTemplate.getVisible()) : worldTemplate.getVisible();
        if (!visible && !Boolean.FALSE.equals(oldWorldTemplate.getVisible())) {
            throw new UserRequestException("世界模板公开后不能再改为私有");
        }
        String image = worldTemplate.getImage() == null ? null : ImageSecurityUtils.normalizeOssImageUrl(worldTemplate.getImage());
        int updated = baseMapper.update(null, new LambdaUpdateWrapper<WorldTemplate>()
                .eq(WorldTemplate::getId, oldWorldTemplate.getId())
                // A stale private editor must not undo a concurrent publication.
                .eq(!visible, WorldTemplate::getVisible, false)
                .set(WorldTemplate::getName, worldTemplate.getName())
                .set(WorldTemplate::getImage, image)
                .set(WorldTemplate::getAuthor, worldTemplate.getAuthor())
                .set(WorldTemplate::getBackground,
                        worldTemplate.getBackground())
                .set(WorldTemplate::getVisible, visible));
        if (!visible && updated == 0) {
            throw new UserRequestException("世界模板已公开或状态已变更，请刷新后重试；公开后不能再改为私有");
        }
    }
}

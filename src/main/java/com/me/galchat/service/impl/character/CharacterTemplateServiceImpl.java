package com.me.galchat.service.impl.character;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CharacterTemplateMapper;
import com.me.galchat.service.ICharacterTemplateService;
import com.me.galchat.service.IWorldTemplateService;
import com.me.galchat.utils.ImageSecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
@RequiredArgsConstructor
public class CharacterTemplateServiceImpl extends ServiceImpl<CharacterTemplateMapper, CharacterTemplate> implements ICharacterTemplateService {

    private final IWorldTemplateService worldTemplateService;

    @Override
    @Cacheable(cacheNames = "characterTemplate", key = "#id", condition = "#id != null", unless = "#result == null")
    public CharacterTemplate getCharacterTemplateById(Long id) {
        CharacterTemplate template = lambdaQuery()
                .select(CharacterTemplate::getId,
                        CharacterTemplate::getWorldId,
                        CharacterTemplate::getName,
                        CharacterTemplate::getImage,
                        CharacterTemplate::getBackground,
                        CharacterTemplate::getPersonality,
                        CharacterTemplate::getCocPlayStyle,
                        CharacterTemplate::getFavorability,
                        CharacterTemplate::getInitFavor)
                .eq(CharacterTemplate::getId, id)
                .one();
        if (template == null) {
            throw new UserRequestException("角色模板不存在");
        }
        return template;
    }

    @Override
    public CharacterTemplate getCharacterTemplateByWorldId(Long worldId, Long id) {
        CharacterTemplate template = lambdaQuery()
                .select(CharacterTemplate::getId,
                        CharacterTemplate::getWorldId,
                        CharacterTemplate::getName,
                        CharacterTemplate::getImage,
                        CharacterTemplate::getBackground,
                        CharacterTemplate::getPersonality,
                        CharacterTemplate::getCocPlayStyle,
                        CharacterTemplate::getFavorability,
                        CharacterTemplate::getInitFavor)
                .eq(CharacterTemplate::getId, id)
                .eq(CharacterTemplate::getWorldId, worldId)
                .one();
        if (template == null) {
            throw new UserRequestException("角色模板不存在");
        }
        return template;
    }

    @Override
    public List<CharacterTemplate> listCharacterBaseInfoByWorldId(Long userId, Long worldId) {
        worldTemplateService.getWorldTemplateById(userId, worldId);
        return lambdaQuery()
                .select(CharacterTemplate::getId, CharacterTemplate::getName, CharacterTemplate::getImage)
                .eq(CharacterTemplate::getWorldId, worldId)
                .list();
    }

    @Override
    @Transactional
    public void createCharacterTemplate(Long userId, Long worldId, CharacterTemplate characterTemplate) {
        if (characterTemplate == null) {
            throw new UserRequestException("请求参数不能为空");
        }
        WorldTemplate worldTemplate = worldTemplateService.getById(worldId);
        if (worldTemplate == null) {
            throw new UserRequestException("世界模板不存在");
        }
        if (!Objects.equals(worldTemplate.getAuthorId(), userId)) {
            throw new UserAuthException("无权新增该世界角色");
        }
        String image = ImageSecurityUtils.normalizeOssImageUrl(characterTemplate.getImage());

        CharacterTemplate newCharacterTemplate = new CharacterTemplate()
                .setWorldId(worldId)
                .setName(characterTemplate.getName())
                .setImage(image)
                .setBackground(characterTemplate.getBackground())
                .setPersonality(characterTemplate.getPersonality())
                .setCocPlayStyle(characterTemplate.getCocPlayStyle())
                .setFavorability(characterTemplate.getFavorability())
                .setInitFavor(characterTemplate.getInitFavor());
        save(newCharacterTemplate);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = "characterTemplate", key = "#id")
    public void updateCharacterTemplate(Long userId, Long worldId, Long id, CharacterTemplate characterTemplate) {
        if (characterTemplate == null) {
            throw new UserRequestException("请求参数不能为空");
        }
        WorldTemplate worldTemplate = worldTemplateService.getById(worldId);
        if (worldTemplate == null) {
            throw new UserRequestException("世界模板不存在");
        }
        if (!Objects.equals(worldTemplate.getAuthorId(), userId)) {
            throw new UserAuthException("无权修改该世界角色");
        }
        CharacterTemplate oldCharacterTemplate = getCharacterTemplateByWorldId(worldId, id);
        String image = characterTemplate.getImage() == null ? null : ImageSecurityUtils.normalizeOssImageUrl(characterTemplate.getImage());

        baseMapper.update(null, new LambdaUpdateWrapper<CharacterTemplate>()
                .eq(CharacterTemplate::getId, oldCharacterTemplate.getId())
                .set(CharacterTemplate::getName, characterTemplate.getName())
                .set(CharacterTemplate::getImage, image)
                .set(CharacterTemplate::getBackground,
                        characterTemplate.getBackground())
                .set(CharacterTemplate::getPersonality,
                        characterTemplate.getPersonality())
                .set(CharacterTemplate::getCocPlayStyle,
                        characterTemplate.getCocPlayStyle())
                .set(CharacterTemplate::getFavorability,
                        characterTemplate.getFavorability())
                .set(CharacterTemplate::getInitFavor,
                        characterTemplate.getInitFavor()));
    }
}

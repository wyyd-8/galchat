package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CharacterTemplateMapper;
import com.me.galchat.service.ICharacterTemplateService;
import com.me.galchat.service.IWorldTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
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
                        CharacterTemplate::getName,
                        CharacterTemplate::getImage,
                        CharacterTemplate::getBackground,
                        CharacterTemplate::getPersonality,
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

        CharacterTemplate newCharacterTemplate = new CharacterTemplate()
                .setName(characterTemplate.getName())
                .setImage(characterTemplate.getImage())
                .setBackground(characterTemplate.getBackground())
                .setPersonality(characterTemplate.getPersonality())
                .setFavorability(characterTemplate.getFavorability())
                .setInitFavor(characterTemplate.getInitFavor());
        save(newCharacterTemplate);

        worldTemplateService.updateById(new WorldTemplate()
                .setId(worldId)
                .setCharacterIds(appendCharacterId(worldTemplate.getCharacterIds(), newCharacterTemplate.getId())));
    }

    private Long[] appendCharacterId(Long[] characterIds, Long characterId) {
        if (characterIds == null || characterIds.length == 0) {
            return new Long[]{characterId};
        }
        Long[] newCharacterIds = Arrays.copyOf(characterIds, characterIds.length + 1);
        newCharacterIds[characterIds.length] = characterId;
        return newCharacterIds;
    }
}

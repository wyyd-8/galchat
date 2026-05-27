package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.UserCharacterFavorLog;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserCharacterFavorLogMapper;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.service.ICharacterTemplateService;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.service.IUserWorldPrefixService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
public class UserCharacterInfoServiceImpl extends ServiceImpl<UserCharacterInfoMapper, UserCharacterInfo> implements IUserCharacterInfoService {

    private final ICharacterTemplateService characterTemplateService;
    private final IUserWorldPrefixService userWorldPrefixService;
    private final StringRedisTemplate redisTemplate;
    private final UserCharacterFavorLogMapper userCharacterFavorLogMapper;

    @Override
    public void addCharacter(Long userWorldId, Long characterId) {
        userWorldPrefixService.checkUserWorldAuth(userWorldId, false);
        UserCharacterInfo oldCharacter = getByUserWorldIdAndCharacterId(userWorldId, characterId);
        if (oldCharacter != null) {
            throw new UserRequestException("角色已存在");
        }

        CharacterTemplate template = characterTemplateService.getCharacterTemplateById(characterId);
        UserCharacterInfo userCharacterInfo = new UserCharacterInfo()
                .setUserWorldId(userWorldId)
                .setCharacterId(template.getId())
                .setCharacterName(template.getName())
                .setCharacterImage(template.getImage())
                .setFavorValue(template.getInitFavor());
        save(userCharacterInfo);
    }

    @Override
    public void deleteCharacter(Long userWorldId, Long characterId) {
        userWorldPrefixService.checkUserWorldAuth(userWorldId, false);
        boolean removed = lambdaUpdate()
                .eq(UserCharacterInfo::getUserWorldId, userWorldId)
                .eq(UserCharacterInfo::getCharacterId, characterId)
                .remove();
        if (!removed) {
            throw new UserRequestException("角色不存在");
        }
        redisTemplate.opsForHash().delete(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY, userWorldId + ":" + characterId);
    }

    @Override
    public List<UserCharacterInfo> listByUserWorldId(Long userWorldId) {
        userWorldPrefixService.checkUserWorldAuth(userWorldId, false);
        return lambdaQuery()
                .eq(UserCharacterInfo::getUserWorldId, userWorldId)
                .list();
    }

    @Override
    public void updateFavorValue(Long userWorldId, Long characterId, Integer favorChange, Long bindingChat) {
        if (favorChange == null) {
            favorChange = 0;
        }

        Integer oldFavorValue = getFavorValueByUserWorldIdAndCharacterId(userWorldId, characterId).getFavorValue();
        Integer favorValue = baseMapper.updateFavorValue(userWorldId, characterId, favorChange);
        if (favorValue == null) {
            throw new UserRequestException("角色不存在");
        }
        redisTemplate.opsForHash().put(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY,
                buildFavorCacheKey(userWorldId, characterId), String.valueOf(favorValue));
        insertFavorLog(userWorldId, characterId, favorValue - oldFavorValue, bindingChat);
        return;
    }

    @Override
    public String buildCharacterPrompt(Long userWorldId, Long characterId) {
        if (characterId == null) {
            throw new UserRequestException("角色id不能为空");
        }

        UserCharacterInfo userCharacterInfo = getFavorValueByUserWorldIdAndCharacterId(userWorldId, characterId);
        if (userCharacterInfo == null) {
            throw new UserRequestException("角色不存在");
        }

        CharacterTemplate template = characterTemplateService.getCharacterTemplateById(characterId);
        String name = template.getName();
        String favorPrompt = getFavorPrompt(template.getFavorability(), userCharacterInfo.getFavorValue());

        StringBuilder prompt = new StringBuilder();
        appendPromptLine(prompt, "name", name);
        appendPromptLine(prompt, "background", template.getBackground());
        appendPromptLine(prompt, "personality", template.getPersonality());
        appendPromptLine(prompt, "favor", favorPrompt);
        return prompt.toString();
    }

    private UserCharacterInfo getByUserWorldIdAndCharacterId(Long userWorldId, Long characterId) {
        return lambdaQuery()
                .eq(UserCharacterInfo::getUserWorldId, userWorldId)
                .eq(UserCharacterInfo::getCharacterId, characterId)
                .one();
    }

    private UserCharacterInfo getFavorValueByUserWorldIdAndCharacterId(Long userWorldId, Long characterId) {
        String cacheKey = buildFavorCacheKey(userWorldId, characterId);
        Object cachedFavorValue = redisTemplate.opsForHash().get(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY, cacheKey);
        if (cachedFavorValue != null) {
            return new UserCharacterInfo().setFavorValue(Integer.valueOf(String.valueOf(cachedFavorValue)));
        }

        UserCharacterInfo userCharacterInfo = lambdaQuery()
                .select(UserCharacterInfo::getFavorValue)
                .eq(UserCharacterInfo::getUserWorldId, userWorldId)
                .eq(UserCharacterInfo::getCharacterId, characterId)
                .one();
        if (userCharacterInfo != null && userCharacterInfo.getFavorValue() != null) {
            redisTemplate.opsForHash().put(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY, cacheKey, String.valueOf(userCharacterInfo.getFavorValue()));
        }
        return userCharacterInfo;
    }

    private void insertFavorLog(Long userWorldId, Long characterId, Integer favorUpdate, Long bindingChat) {
        userCharacterFavorLogMapper.insert(new UserCharacterFavorLog()
                .setUserWorldId(userWorldId)
                .setCharacterId(characterId)
                .setFavorUpdate(favorUpdate)
                .setBindingChat(bindingChat));
    }

    private String buildFavorCacheKey(Long userWorldId, Long characterId) {
        return userWorldId + ":" + characterId;
    }

    private String getFavorPrompt(Map<String, String> favorability, Integer favorValue) {
        if (favorability == null || favorability.isEmpty()) {
            return null;
        }

        int currentFavorValue = favorValue == null ? 0 : favorValue;
        Optional<Map.Entry<String, String>> match = favorability.entrySet().stream()
                .filter(entry -> StringUtils.hasText(entry.getValue()))
                .filter(entry -> parseFavorThreshold(entry.getKey()) != null)
                .filter(entry -> parseFavorThreshold(entry.getKey()) <= currentFavorValue)
                .max(Comparator.comparingInt(entry -> parseFavorThreshold(entry.getKey())));
        return match.map(Map.Entry::getValue).orElse(null);
    }

    private Integer parseFavorThreshold(String threshold) {
        try {
            return Integer.valueOf(threshold);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void appendPromptLine(StringBuilder prompt, String key, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (!prompt.isEmpty()) {
            prompt.append('\n');
        }
        prompt.append(key).append(": ").append(value);
    }
}

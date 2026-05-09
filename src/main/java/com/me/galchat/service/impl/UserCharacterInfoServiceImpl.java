package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.service.ICharacterTemplateService;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.utils.CurrentHolder;
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

    private static final String WORLD_USER_AUTH_KEY = "world:user:auth";

    private final ICharacterTemplateService characterTemplateService;
    private final StringRedisTemplate redisTemplate;

    @Override
    public UserCharacterInfo addCharacter(Long userWorldId, Long characterId) {
        checkUserWorldAuth(userWorldId);
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
        return userCharacterInfo;
    }

    @Override
    public void deleteCharacter(Long userWorldId, Long characterId) {
        checkUserWorldAuth(userWorldId);
        UserCharacterInfo userCharacterInfo = getByUserWorldIdAndCharacterId(userWorldId, characterId);
        if (userCharacterInfo == null) {
            throw new UserRequestException("角色不存在");
        }
        lambdaUpdate()
                .eq(UserCharacterInfo::getUserWorldId, userWorldId)
                .eq(UserCharacterInfo::getCharacterId, characterId)
                .remove();
    }

    @Override
    public List<UserCharacterInfo> listByUserWorldId(Long userWorldId) {
        checkUserWorldAuth(userWorldId);
        return lambdaQuery()
                .eq(UserCharacterInfo::getUserWorldId, userWorldId)
                .list();
    }

    @Override
    public Integer updateFavorValue(Long userWorldId, Long characterId, Integer favorChange) {
        checkUserWorldAuth(userWorldId);
        if (characterId == null) {
            throw new UserRequestException("角色id不能为空");
        }
        if (favorChange == null) {
            favorChange = 0;
        }

        Integer favorValue = baseMapper.updateFavorValue(userWorldId, characterId, favorChange);
        if (favorValue == null) {
            throw new UserRequestException("角色不存在");
        }
        return favorValue;
    }

    @Override
    public String buildCharacterPrompt(Long userWorldId, Long characterId) {
        checkUserWorldAuth(userWorldId);
        if (characterId == null) {
            throw new UserRequestException("角色id不能为空");
        }

        UserCharacterInfo userCharacterInfo = getByUserWorldIdAndCharacterId(userWorldId, characterId);
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

    private void checkUserWorldAuth(Long userWorldId) {
        if (userWorldId == null) {
            throw new UserRequestException("用户世界id不能为空");
        }

        Integer currentUserId = CurrentHolder.getCurrentId();
        if (currentUserId == null) {
            throw new UserAuthException("用户未登录");
        }

        Object authUserId = redisTemplate.opsForHash().get(WORLD_USER_AUTH_KEY, String.valueOf(userWorldId));
        if (!String.valueOf(currentUserId).equals(authUserId)) {
            throw new UserAuthException("无权访问该用户世界");
        }
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

package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.UserCharacterFavorLog;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserChatThinkingHistory;
import com.me.galchat.domain.po.UserChatToolCall;
import com.me.galchat.domain.po.UserEventLog;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupChatMemberMapper;
import com.me.galchat.mapper.UserCharacterFavorLogMapper;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.mapper.UserChatThinkingHistoryMapper;
import com.me.galchat.mapper.UserChatToolCallMapper;
import com.me.galchat.mapper.UserEventLogMapper;
import com.me.galchat.mapper.VectorStoreCleanupMapper;
import com.me.galchat.service.ICharacterTemplateService;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.service.IUserWorldPrefixService;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final UserChatHistoryMapper userChatHistoryMapper;
    private final UserChatThinkingHistoryMapper userChatThinkingHistoryMapper;
    private final UserChatToolCallMapper userChatToolCallMapper;
    private final UserEventLogMapper userEventLogMapper;
    private final GroupChatMemberMapper groupChatMemberMapper;
    private final SingleChatLockService singleChatLockService;
    private final VectorStoreCleanupMapper vectorStoreCleanupMapper;

    @Override
    public void addCharacter(Long userWorldId, Long characterId) {
        UserWorldPrefix userWorld = userWorldPrefixService.checkUserWorldAuth(userWorldId, true);
        UserCharacterInfo oldCharacter = getByUserWorldIdAndCharacterId(userWorldId, characterId);
        if (oldCharacter != null) {
            throw new UserRequestException("角色已存在");
        }

        CharacterTemplate template = characterTemplateService.getCharacterTemplateById(characterId);
        if (!Objects.equals(template.getWorldId(), userWorld.getWorldId())) {
            throw new UserRequestException("角色模板不属于该世界");
        }
        UserCharacterInfo userCharacterInfo = new UserCharacterInfo()
                .setUserWorldId(userWorldId)
                .setCharacterId(template.getId())
                .setCharacterName(template.getName())
                .setCharacterImage(template.getImage())
                .setFavorValue(template.getInitFavor())
                .setUserInfoPrompt("");
        save(userCharacterInfo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCharacter(Long userWorldId, Long characterId) {
        userWorldPrefixService.checkUserWorldAuth(userWorldId, false);
        Long activeConversationCount = groupChatMemberMapper.countActiveConversations(userWorldId, characterId);
        if (activeConversationCount != null && activeConversationCount > 0) {
            throw new UserRequestException("角色仍在进行中的群聊中，请先关闭群聊");
        }
        RLock characterLock = singleChatLockService.tryLock(userWorldId, characterId);
        if (characterLock == null) {
            throw new UserRequestException("角色正在单聊中，请稍后再删除");
        }

        try {
            doDeleteCharacter(userWorldId, characterId);
        } finally {
            singleChatLockService.unlock(characterLock);
        }
    }

    private void doDeleteCharacter(Long userWorldId, Long characterId) {
        if (getByUserWorldIdAndCharacterId(userWorldId, characterId) == null) {
            throw new UserRequestException("角色不存在");
        }

        List<Long> userMessageIds = listUserMessageIds(userWorldId, characterId);
        deleteChatAuxiliaryData(userMessageIds);
        deleteChatHistories(userWorldId, characterId);
        deleteFavorLogs(userWorldId, characterId);
        deleteUserEventLogs(userWorldId, characterId);
        deleteVectorData(userWorldId, characterId);
        deleteCharacterInfo(userWorldId, characterId);
        evictCharacterCaches(userWorldId, characterId, userMessageIds);
    }

    private List<Long> listUserMessageIds(Long userWorldId, Long characterId) {
        return userChatHistoryMapper.selectList(new LambdaQueryWrapper<UserChatHistory>()
                .select(UserChatHistory::getId)
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .and(wrapper -> wrapper.isNull(UserChatHistory::getType)
                        .or()
                        .eq(UserChatHistory::getType, MessageType.USER.getValue())))
                .stream()
                .map(UserChatHistory::getId)
                .toList();
    }

    private void deleteChatAuxiliaryData(List<Long> userMessageIds) {
        if (userMessageIds.isEmpty()) {
            return;
        }

        userChatThinkingHistoryMapper.delete(new LambdaUpdateWrapper<UserChatThinkingHistory>()
                .in(UserChatThinkingHistory::getUserMessageId, userMessageIds));
        userChatToolCallMapper.delete(new LambdaUpdateWrapper<UserChatToolCall>()
                .in(UserChatToolCall::getUserMessageId, userMessageIds));
    }

    private void deleteStepNoKeys(List<Long> userMessageIds) {
        if (userMessageIds.isEmpty()) {
            return;
        }

        redisTemplate.delete(userMessageIds.stream()
                .map(userMessageId -> RedisConstant.CHAT_MEMORY_STEP_KEY_PREFIX + userMessageId)
                .toList());
    }

    private void deleteChatHistories(Long userWorldId, Long characterId) {
        userChatHistoryMapper.delete(new LambdaUpdateWrapper<UserChatHistory>()
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId));
    }

    private void deleteFavorLogs(Long userWorldId, Long characterId) {
        userCharacterFavorLogMapper.delete(new LambdaUpdateWrapper<UserCharacterFavorLog>()
                .eq(UserCharacterFavorLog::getUserWorldId, userWorldId)
                .eq(UserCharacterFavorLog::getCharacterId, characterId));
    }

    private void deleteUserEventLogs(Long userWorldId, Long characterId) {
        userEventLogMapper.delete(new LambdaUpdateWrapper<UserEventLog>()
                .eq(UserEventLog::getUserWorldId, userWorldId)
                .eq(UserEventLog::getCharacterId, characterId));
    }

    private void deleteVectorData(Long userWorldId, Long characterId) {
        vectorStoreCleanupMapper.deleteChatHistoryByConversation(userWorldId, characterId);
    }

    private void deleteCharacterInfo(Long userWorldId, Long characterId) {
        boolean removed = lambdaUpdate()
                .eq(UserCharacterInfo::getUserWorldId, userWorldId)
                .eq(UserCharacterInfo::getCharacterId, characterId)
                .remove();
        if (!removed) {
            throw new UserRequestException("角色不存在");
        }
    }

    private void evictCharacterCaches(Long userWorldId, Long characterId, List<Long> userMessageIds) {
        redisTemplate.opsForHash().delete(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY,
                buildFavorCacheKey(userWorldId, characterId));
        redisTemplate.delete(characterCacheKeys(userWorldId, characterId));
        deleteStepNoKeys(userMessageIds);
    }

    private List<String> characterCacheKeys(Long userWorldId, Long characterId) {
        String conversationKey = RedisConstant.CHAT_KEY_PREFIX + userWorldId + ":" + characterId;
        return List.of(
                buildPromptInfoCacheKey(userWorldId, characterId),
                conversationKey + RedisConstant.TYPING_SUFFIX,
                conversationKey + RedisConstant.INPUT_SUFFIX,
                conversationKey + RedisConstant.LAST_ASSISTANT_SUFFIX,
                RedisConstant.TOPIC_BOUNDARY_KEY_PREFIX + userWorldId + ":" + characterId
        );
    }

    @Override
    public List<UserCharacterInfo> listByUserWorldId(Long userWorldId) {
        userWorldPrefixService.checkUserWorldAuth(userWorldId, false);
        return lambdaQuery()
                .eq(UserCharacterInfo::getUserWorldId, userWorldId)
                .list();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateFavorValue(Long userWorldId, Long characterId, Integer favorChange,
                                 String bindingType, Long bindingChat) {
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
        evictPromptInfoCache(userWorldId, characterId);
        insertFavorLog(userWorldId, characterId, favorValue - oldFavorValue, bindingType, bindingChat);
        return;
    }

    @Override
    public void setFavorValue(Long userWorldId, Long characterId, Integer favorValue) {
        boolean updated = lambdaUpdate()
                .eq(UserCharacterInfo::getUserWorldId, userWorldId)
                .eq(UserCharacterInfo::getCharacterId, characterId)
                .set(UserCharacterInfo::getFavorValue, favorValue)
                .update();
        if (!updated) {
            throw new UserRequestException("角色不存在");
        }
        redisTemplate.opsForHash().put(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY,
                buildFavorCacheKey(userWorldId, characterId), String.valueOf(favorValue));
        evictPromptInfoCache(userWorldId, characterId);
    }

    @Override
    public void updateUserInfoPrompt(Long userWorldId, Long characterId, String userInfoPrompt) {
        userWorldPrefixService.checkUserWorldAuth(userWorldId, false);
        boolean updated = lambdaUpdate()
                .eq(UserCharacterInfo::getUserWorldId, userWorldId)
                .eq(UserCharacterInfo::getCharacterId, characterId)
                .set(UserCharacterInfo::getUserInfoPrompt,
                        StringUtils.hasText(userInfoPrompt) ? userInfoPrompt.trim() : "")
                .update();
        if (!updated) {
            throw new UserRequestException("角色不存在");
        }
        evictPromptInfoCache(userWorldId, characterId);
    }

    @Override
    public String appendUserInfoPrompt(Long userWorldId, Long characterId, String userInfoPrompt) {
        if (!StringUtils.hasText(userInfoPrompt)) {
            return null;
        }
        String prompt = baseMapper.appendUserInfoPrompt(userWorldId, characterId, userInfoPrompt.trim());
        if (prompt == null) {
            throw new UserRequestException("角色不存在");
        }
        evictPromptInfoCache(userWorldId, characterId);
        return prompt;
    }

    @Override
    public String buildCharacterPrompt(Long userWorldId, Long characterId) {
        if (characterId == null) {
            throw new UserRequestException("角色id不能为空");
        }

        UserCharacterInfo userCharacterInfo = getPromptInfoByUserWorldIdAndCharacterId(userWorldId, characterId);
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
        appendPromptLine(prompt, "user_info_prompt", userCharacterInfo.getUserInfoPrompt());
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

    private UserCharacterInfo getPromptInfoByUserWorldIdAndCharacterId(Long userWorldId, Long characterId) {
        UserCharacterInfo cachedPromptInfo = getCachedPromptInfo(userWorldId, characterId);
        if (cachedPromptInfo != null) {
            return cachedPromptInfo;
        }

        UserCharacterInfo userCharacterInfo = lambdaQuery()
                .select(UserCharacterInfo::getFavorValue, UserCharacterInfo::getUserInfoPrompt)
                .eq(UserCharacterInfo::getUserWorldId, userWorldId)
                .eq(UserCharacterInfo::getCharacterId, characterId)
                .one();
        if (userCharacterInfo != null && userCharacterInfo.getFavorValue() != null) {
            cachePromptInfo(userWorldId, characterId, userCharacterInfo);
        }
        return userCharacterInfo;
    }

    private void insertFavorLog(Long userWorldId, Long characterId, Integer favorUpdate,
                                String bindingType, Long bindingChat) {
        userCharacterFavorLogMapper.insert(new UserCharacterFavorLog()
                .setUserWorldId(userWorldId)
                .setCharacterId(characterId)
                .setFavorUpdate(favorUpdate)
                .setBindingType(bindingType)
                .setBindingChat(bindingChat));
    }

    private String buildFavorCacheKey(Long userWorldId, Long characterId) {
        return userWorldId + ":" + characterId;
    }

    private String buildPromptInfoCacheKey(Long userWorldId, Long characterId) {
        return RedisConstant.USER_CHARACTER_PROMPT_INFO_KEY_PREFIX + userWorldId + ":" + characterId;
    }

    private UserCharacterInfo getCachedPromptInfo(Long userWorldId, Long characterId) {
        List<Object> values = redisTemplate.opsForHash().multiGet(buildPromptInfoCacheKey(userWorldId, characterId),
                List.of(RedisConstant.FAVOR_VALUE_HASH_FIELD, RedisConstant.USER_INFO_PROMPT_HASH_FIELD));
        if (values == null || values.size() != 2 || values.get(0) == null || values.get(1) == null) {
            return null;
        }

        return new UserCharacterInfo()
                .setFavorValue(Integer.valueOf(String.valueOf(values.get(0))))
                .setUserInfoPrompt(String.valueOf(values.get(1)));
    }

    private void cachePromptInfo(Long userWorldId, Long characterId, UserCharacterInfo userCharacterInfo) {
        redisTemplate.opsForHash().putAll(buildPromptInfoCacheKey(userWorldId, characterId), Map.of(
                RedisConstant.FAVOR_VALUE_HASH_FIELD, String.valueOf(userCharacterInfo.getFavorValue()),
                RedisConstant.USER_INFO_PROMPT_HASH_FIELD, userCharacterInfo.getUserInfoPrompt()));
    }

    private void evictPromptInfoCache(Long userWorldId, Long characterId) {
        redisTemplate.delete(buildPromptInfoCacheKey(userWorldId, characterId));
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
        return match.map(Map.Entry::getValue).orElse("");
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

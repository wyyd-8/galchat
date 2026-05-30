package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.mapper.UserWorldPrefixMapper;
import com.me.galchat.mapper.VectorStoreCleanupMapper;
import com.me.galchat.mapper.WorldEventLogMapper;
import com.me.galchat.mapper.WorldStoryEventMapper;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.IWorldTemplateService;
import com.me.galchat.utils.CurrentHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
@Service
@RequiredArgsConstructor
public class UserWorldPrefixServiceImpl extends ServiceImpl<UserWorldPrefixMapper, UserWorldPrefix> implements IUserWorldPrefixService {

    private static final long REDIS_SCAN_COUNT = 1_000L;

    private final IWorldTemplateService worldTemplateService;
    private final StringRedisTemplate redisTemplate;
    private final UserCharacterInfoMapper userCharacterInfoMapper;
    private final WorldEventLogMapper worldEventLogMapper;
    private final WorldStoryEventMapper worldStoryEventMapper;
    private final VectorStoreCleanupMapper vectorStoreCleanupMapper;

    @Override
    public List<UserWorldPrefix> listBaseInfoByUserId(Long userId) {
        return lambdaQuery()
                .select(UserWorldPrefix::getId, UserWorldPrefix::getName, UserWorldPrefix::getImage)
                .eq(UserWorldPrefix::getUserId, userId)
                .list();
    }

    @Override
    public void createUserWorld(Long userId, UserWorldPrefix userWorldPrefix) {
        WorldTemplate template = worldTemplateService.getWorldTemplateById(userId, userWorldPrefix.getWorldId());
        UserWorldPrefix newUserWorld = new UserWorldPrefix()
                .setUserId(userId)
                .setWorldId(template.getId())
                .setName(StringUtils.hasText(userWorldPrefix.getName()) ? userWorldPrefix.getName() : template.getName())
                .setImage(template.getImage())
                .setAcitvePushStatus(userWorldPrefix.getAcitvePushStatus())
                .setFavorSystemStatus(userWorldPrefix.getFavorSystemStatus())
                .setEotDetectionStatus(userWorldPrefix.getEotDetectionStatus())
                .setThinkStatus(userWorldPrefix.getThinkStatus())
                .setAddSpecialPrompt(userWorldPrefix.getAddSpecialPrompt())
                .setMyWorld(template.getAuthorId().equals(userId));
        save(newUserWorld);
        redisTemplate.opsForHash().put(RedisConstant.WORLD_USER_AUTH_KEY,
                String.valueOf(newUserWorld.getId()),
                String.valueOf(userId));
    }

    @Override
    public UserWorldPrefix getUserWorld(Long userId, Long id) {
        return getExistingUserWorld(userId, id);
    }

    @Override
    public void updateUserWorld(Long userId, Long id, UserWorldPrefix userWorldPrefix) {
        UserWorldPrefix oldUserWorld = getExistingUserWorld(userId, id);
        UserWorldPrefix updateUserWorld = new UserWorldPrefix()
                .setId(oldUserWorld.getId())
                .setName(userWorldPrefix.getName())
                .setAcitvePushStatus(userWorldPrefix.getAcitvePushStatus())
                .setFavorSystemStatus(userWorldPrefix.getFavorSystemStatus())
                .setEotDetectionStatus(userWorldPrefix.getEotDetectionStatus());
        updateById(updateUserWorld);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUserWorld(Long userId, Long id) {
        UserWorldPrefix userWorld = baseMapper.selectByIdAndUserId(id, userId);
        if (userWorld == null) {
            throw new UserRequestException("用户世界不存在");
        }

        Long userWorldId = userWorld.getId();
        checkNoCharacters(userWorldId);
        worldStoryEventMapper.deleteByUserWorldIdWithCharacters(userWorldId);
        worldEventLogMapper.deleteByUserWorldId(userWorldId);
        vectorStoreCleanupMapper.deleteWorldEventByUserWorldId(userWorldId);
        int deleted = baseMapper.deleteByIdAndUserId(userWorldId, userId);
        if (deleted == 0) {
            throw new UserRequestException("当前世界存在角色，不能删除");
        }
        evictUserWorldRedisData(userWorldId);
    }

    @Override
    public UserWorldPrefix checkUserWorldAuth(Long userWorldId, boolean needUserWorldPrefix) {
        Integer currentUserId = CurrentHolder.getCurrentId();
        if (currentUserId == null) {
            throw new UserAuthException("用户未登录");
        }
        return checkUserWorldAuth(Long.valueOf(currentUserId), userWorldId, needUserWorldPrefix);
    }

    @Override
    public UserWorldPrefix checkUserWorldAuth(Long userId, Long userWorldId, boolean needUserWorldPrefix) {
        if (userWorldId == null) {
            throw new UserRequestException("用户世界id不能为空");
        }
        if (userId == null) {
            throw new UserAuthException("用户未登录");
        }

        Object authUserId = redisTemplate.opsForHash().get(RedisConstant.WORLD_USER_AUTH_KEY, String.valueOf(userWorldId));
        if (String.valueOf(userId).equals(authUserId)) {
            return needUserWorldPrefix ? getExistingUserWorld(userId, userWorldId) : null;
        }

        UserWorldPrefix userWorld = lambdaQuery()
                .eq(UserWorldPrefix::getId, userWorldId)
                .eq(UserWorldPrefix::getUserId, userId)
                .one();
        if (userWorld == null) {
            throw new UserAuthException("无权访问该用户世界");
        }

        redisTemplate.opsForHash().put(RedisConstant.WORLD_USER_AUTH_KEY, String.valueOf(userWorldId), String.valueOf(userId));
        return userWorld;
    }

    private UserWorldPrefix getExistingUserWorld(Long userId, Long id) {
        UserWorldPrefix userWorld = lambdaQuery()
                .eq(UserWorldPrefix::getId, id)
                .eq(UserWorldPrefix::getUserId, userId)
                .one();
        if (userWorld == null) {
            throw new UserRequestException("用户世界不存在");
        }
        return userWorld;
    }

    private void checkNoCharacters(Long userWorldId) {
        if (userCharacterInfoMapper.countByUserWorldId(userWorldId) > 0) {
            throw new UserRequestException("当前世界存在角色，不能删除");
        }
    }

    private void evictUserWorldRedisData(Long userWorldId) {
        String worldFieldPrefix = userWorldId + ":";
        redisTemplate.opsForHash().delete(RedisConstant.WORLD_USER_AUTH_KEY, String.valueOf(userWorldId));
        deleteHashFieldsByPattern(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY, worldFieldPrefix + "*");
        deleteKeysByPattern(RedisConstant.CHAT_KEY_PREFIX + worldFieldPrefix + "*");
        deleteKeysByPattern(RedisConstant.USER_CHARACTER_PROMPT_INFO_KEY_PREFIX + worldFieldPrefix + "*");
        deleteKeysByPattern(RedisConstant.TOPIC_BOUNDARY_KEY_PREFIX + worldFieldPrefix + "*");
        deleteKeysByPattern(RedisConstant.STORY_ACTIVE_KEY_PREFIX + worldFieldPrefix + "*");
    }

    private void deleteHashFieldsByPattern(String hashKey, String pattern) {
        List<Object> fields = new ArrayList<>();
        try (Cursor<Map.Entry<Object, Object>> cursor = redisTemplate.opsForHash()
                .scan(hashKey, ScanOptions.scanOptions().match(pattern).count(REDIS_SCAN_COUNT).build())) {
            cursor.forEachRemaining(entry -> fields.add(entry.getKey()));
        }
        if (!fields.isEmpty()) {
            redisTemplate.opsForHash().delete(hashKey, fields.toArray());
        }
    }

    private void deleteKeysByPattern(String pattern) {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(REDIS_SCAN_COUNT).build())) {
            cursor.forEachRemaining(keys::add);
        }
        deleteRedisKeys(keys);
    }

    private void deleteRedisKeys(Collection<String> keys) {
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Override
    @Cacheable(cacheNames = "worldPrompt", key = "#worldId", condition = "#worldId != null", unless = "#result == null")
    public String buildWorldPrompt(Long worldId) {
        return baseMapper.getBackground(worldId);
    }
}

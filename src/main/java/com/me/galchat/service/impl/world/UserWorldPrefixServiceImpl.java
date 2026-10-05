package com.me.galchat.service.impl.world;

import com.me.galchat.utils.RedisCacheExpiry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserWorldSave;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.mapper.UserWorldPrefixMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.UserWorldSaveMapper;
import com.me.galchat.service.ITrpgRedisStateService;
import com.me.galchat.service.impl.group.GroupConversationDeletionStore;
import com.me.galchat.service.impl.group.GroupConversationLockService;
import com.me.galchat.service.impl.group.GroupGenerationStreamRegistry;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.IWorldTemplateService;
import com.me.galchat.utils.CurrentHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserWorldPrefixServiceImpl extends ServiceImpl<UserWorldPrefixMapper, UserWorldPrefix> implements IUserWorldPrefixService {

    private final IWorldTemplateService worldTemplateService;
    private final StringRedisTemplate redisTemplate;
    private final UserCharacterInfoMapper userCharacterInfoMapper;
    private final GroupConversationMapper conversationMapper;
    private final UserWorldSaveMapper worldSaveMapper;
    private final GroupConversationLockService lockService;
    private final GroupConversationDeletionStore deletionStore;
    private final ITrpgRedisStateService redisStateService;
    private final GroupGenerationStreamRegistry generationRegistry;

    @Override
    public List<UserWorldPrefix> listBaseInfoByUserId(Long userId) {
        List<UserWorldPrefix> worlds = lambdaQuery()
                .select(UserWorldPrefix::getId, UserWorldPrefix::getWorldId,
                        UserWorldPrefix::getName, UserWorldPrefix::getImage)
                .eq(UserWorldPrefix::getUserId, userId)
                .list();
        fillCurrentTemplateImages(worlds);
        return worlds;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createUserWorld(Long userId, UserWorldPrefix userWorldPrefix) {
        WorldTemplate template = worldTemplateService.getWorldTemplateForCreate(userId, userWorldPrefix.getWorldId());
        UserWorldPrefix newUserWorld = new UserWorldPrefix()
                .setUserId(userId)
                .setWorldId(template.getId())
                .setName(StringUtils.hasText(userWorldPrefix.getName()) ? userWorldPrefix.getName() : template.getName())
                .setImage(template.getImage())
                .setAcitvePushStatus(userWorldPrefix.getAcitvePushStatus())
                .setDailyCompanionMode(Boolean.TRUE.equals(userWorldPrefix.getDailyCompanionMode()))
                .setFavorSystemStatus(userWorldPrefix.getFavorSystemStatus())
                .setAddSpecialPrompt(userWorldPrefix.getAddSpecialPrompt())
                .setMyWorld(template.getAuthorId().equals(userId));
        save(newUserWorld);
        redisTemplate.opsForHash().put(RedisConstant.WORLD_USER_AUTH_KEY,
                String.valueOf(newUserWorld.getId()),
                String.valueOf(userId));
        RedisCacheExpiry.ensure(redisTemplate, RedisConstant.WORLD_USER_AUTH_KEY, RedisConstant.WORLD_USER_AUTH_TTL);
    }

    @Override
    public UserWorldPrefix getUserWorld(Long userId, Long id) {
        UserWorldPrefix world = getExistingUserWorld(userId, id);
        fillCurrentTemplateImages(List.of(world));
        return world;
    }

    private void fillCurrentTemplateImages(List<UserWorldPrefix> worlds) {
        List<Long> templateIds = worlds.stream().map(UserWorldPrefix::getWorldId)
                .filter(Objects::nonNull).distinct().toList();
        if (templateIds.isEmpty()) {
            return;
        }
        Map<Long, WorldTemplate> templates = worldTemplateService.list(new LambdaQueryWrapper<WorldTemplate>()
                        .select(WorldTemplate::getId, WorldTemplate::getImage)
                        .in(WorldTemplate::getId, templateIds)).stream()
                .collect(Collectors.toMap(WorldTemplate::getId, Function.identity()));
        // Images follow the live template, including an explicitly cleared image.
        worlds.forEach(world -> {
            WorldTemplate template = templates.get(world.getWorldId());
            if (template != null) {
                world.setImage(template.getImage());
            }
        });
    }

    @Override
    public void updateUserWorld(Long userId, Long id, UserWorldPrefix userWorldPrefix) {
        UserWorldPrefix oldUserWorld = getExistingUserWorld(userId, id);
        UserWorldPrefix updateUserWorld = new UserWorldPrefix()
                .setId(oldUserWorld.getId())
                .setName(userWorldPrefix.getName())
                .setAcitvePushStatus(userWorldPrefix.getAcitvePushStatus())
                .setFavorSystemStatus(userWorldPrefix.getFavorSystemStatus());
        updateById(updateUserWorld);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUserWorld(Long userId, Long id) {
        GroupConversationLockService.OwnedLock worldLock = lockService.tryWorldLock(id);
        if (worldLock == null) {
            throw new UserRequestException("当前世界正在变更，请稍后再删除");
        }
        List<GroupConversationLockService.OwnedLock> conversationLocks = new ArrayList<>();
        Runnable unlock = () -> {
            for (int i = conversationLocks.size() - 1; i >= 0; i--) {
                lockService.unlock(conversationLocks.get(i));
            }
            lockService.unlock(worldLock);
        };
        boolean transactional = TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
        if (transactional) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) { unlock.run(); }
            });
        }
        try {
            UserWorldPrefix userWorld = baseMapper.selectByIdAndUserId(id, userId);
            if (userWorld == null) throw new UserRequestException("用户世界不存在");
            checkNoCharacters(id);
            List<GroupConversation> conversations = conversationMapper.selectList(
                    new LambdaQueryWrapper<GroupConversation>()
                            .eq(GroupConversation::getUserWorldId, id)
                            .orderByAsc(GroupConversation::getId));
            // Acquire every conversation before deleting anything. Generations hold
            // these locks independently of the world mutation lock.
            for (GroupConversation conversation : conversations) {
                var lock = lockService.tryLock(conversation.getId());
                if (lock == null) throw new UserRequestException("世界内有群聊或跑团正在生成回复，请稍后再删除");
                conversationLocks.add(lock);
            }
            for (GroupConversation conversation : conversations) deletionStore.delete(conversation);
            worldSaveMapper.delete(new LambdaQueryWrapper<UserWorldSave>().eq(UserWorldSave::getUserWorldId, id));
            if (baseMapper.deleteByIdAndUserId(id, userId) == 0) {
                throw new UserRequestException("当前世界存在角色，不能删除");
            }
            Runnable clearState = () -> clearDeletedWorldState(id, conversations);
            if (transactional) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() { clearState.run(); }
                });
            } else clearState.run();
        } finally {
            if (!transactional) unlock.run();
        }
    }

    private void clearDeletedWorldState(Long worldId, List<GroupConversation> conversations) {
        for (GroupConversation conversation : conversations) {
            try {
                redisStateService.clear(conversation.getId());
            } catch (RuntimeException error) {
                log.warn("删除世界后清理跑团状态失败, conversationId:{}", conversation.getId(), error);
            }
            generationRegistry.evict(conversation.getId());
        }
        try {
            evictUserWorldRedisData(worldId);
        } catch (RuntimeException error) {
            log.warn("删除世界后清理世界缓存失败, worldId:{}", worldId, error);
        }
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
        RedisCacheExpiry.ensure(redisTemplate, RedisConstant.WORLD_USER_AUTH_KEY, RedisConstant.WORLD_USER_AUTH_TTL);
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
        deleteKeysByPattern(RedisConstant.USER_CHARACTER_PROMPT_INFO_KEY_PREFIX + worldFieldPrefix + "*");
        deleteKeysByPattern(RedisConstant.TOPIC_BOUNDARY_KEY_PREFIX + worldFieldPrefix + "*");
    }

    private void deleteHashFieldsByPattern(String hashKey, String pattern) {
        List<Object> fields = new ArrayList<>();
        try (Cursor<Map.Entry<Object, Object>> cursor = redisTemplate.opsForHash()
                .scan(hashKey, ScanOptions.scanOptions().match(pattern).count(RedisConstant.REDIS_SCAN_COUNT).build())) {
            cursor.forEachRemaining(entry -> fields.add(entry.getKey()));
        }
        if (!fields.isEmpty()) {
            redisTemplate.opsForHash().delete(hashKey, fields.toArray());
        }
    }

    private void deleteKeysByPattern(String pattern) {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(RedisConstant.REDIS_SCAN_COUNT).build())) {
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

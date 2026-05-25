package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserWorldPrefixMapper;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.IWorldTemplateService;
import com.me.galchat.utils.CurrentHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

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

    private static final String WORLD_USER_AUTH_KEY = "world:user:auth";

    private final IWorldTemplateService worldTemplateService;
    private final StringRedisTemplate redisTemplate;

    @Override
    public List<UserWorldPrefix> listBaseInfoByUserId(Long userId) {
        return lambdaQuery()
                .select(UserWorldPrefix::getId, UserWorldPrefix::getName, UserWorldPrefix::getImage)
                .eq(UserWorldPrefix::getUserId, userId)
                .list();
    }

    @Override
    public UserWorldPrefix createUserWorld(Long userId, UserWorldPrefix userWorldPrefix) {
        WorldTemplate template = worldTemplateService.getWorldTemplateById(userWorldPrefix.getWorldId());
        UserWorldPrefix newUserWorld = new UserWorldPrefix()
                .setUserId(userId)
                .setWorldId(template.getId())
                .setName(StringUtils.hasText(userWorldPrefix.getName()) ? userWorldPrefix.getName() : template.getName())
                .setImage(template.getImage())
                .setAcitvePushStatus(userWorldPrefix.getAcitvePushStatus())
                .setPushTime(userWorldPrefix.getPushTime())
                .setConnectOtherCharacterStatus(userWorldPrefix.getConnectOtherCharacterStatus())
                .setFavorSystemStatus(userWorldPrefix.getFavorSystemStatus())
                .setEotDetectionStatus(userWorldPrefix.getEotDetectionStatus());
        save(newUserWorld);
        redisTemplate.opsForHash().put(WORLD_USER_AUTH_KEY,
                String.valueOf(newUserWorld.getId()),
                String.valueOf(userId));
        return newUserWorld;
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
                .setPushTime(userWorldPrefix.getPushTime())
                .setConnectOtherCharacterStatus(userWorldPrefix.getConnectOtherCharacterStatus())
                .setFavorSystemStatus(userWorldPrefix.getFavorSystemStatus())
                .setEotDetectionStatus(userWorldPrefix.getEotDetectionStatus())
                .setThinkStatus(oldUserWorld.getThinkStatus());
        updateById(updateUserWorld);
    }

    @Override
    public void deleteUserWorld(Long userId, Long id) {
        UserWorldPrefix userWorld = getExistingUserWorld(userId, id);
        removeById(userWorld.getId());
        redisTemplate.opsForHash().delete(WORLD_USER_AUTH_KEY, String.valueOf(userWorld.getId()));
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

        Object authUserId = redisTemplate.opsForHash().get(WORLD_USER_AUTH_KEY, String.valueOf(userWorldId));
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

        redisTemplate.opsForHash().put(WORLD_USER_AUTH_KEY, String.valueOf(userWorldId), String.valueOf(userId));
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

    @Override
    @Cacheable(cacheNames = "worldPrompt", key = "#worldId", condition = "#worldId != null", unless = "#result == null")
    public String buildWorldPrompt(Long worldId) {
        return baseMapper.getBackground(worldId);
    }
}

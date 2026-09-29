package com.me.galchat.service.impl.group;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.FavorBindingType;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.po.UserCharacterFavorLog;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserCharacterFavorLogMapper;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GroupChatFavorRollbackService {
    private final UserCharacterFavorLogMapper favorLogMapper;
    private final UserCharacterInfoMapper characterInfoMapper;
    private final StringRedisTemplate redisTemplate;

    @Transactional(rollbackFor = Exception.class)
    public void rollback(Long userWorldId, List<Long> stepIds) {
        if (stepIds.isEmpty()) return;
        var query = new LambdaQueryWrapper<UserCharacterFavorLog>()
                .eq(UserCharacterFavorLog::getUserWorldId, userWorldId)
                .eq(UserCharacterFavorLog::getBindingType, FavorBindingType.GROUP_REPLY_STEP)
                .in(UserCharacterFavorLog::getBindingChat, stepIds);
        List<UserCharacterFavorLog> logs = favorLogMapper.selectList(query);
        Map<Long, Integer> changes = logs.stream()
                .filter(log -> log.getCharacterId() != null && log.getFavorUpdate() != null)
                .collect(Collectors.groupingBy(UserCharacterFavorLog::getCharacterId,
                        Collectors.summingInt(UserCharacterFavorLog::getFavorUpdate)));
        changes.forEach((characterId, change) -> {
            if (change == 0) return;
            if (characterInfoMapper.updateFavorValue(userWorldId, characterId, -change) == null) {
                throw new UserRequestException("角色不存在，无法回滚好感变化");
            }
            Runnable invalidate = () -> {
                try {
                    redisTemplate.opsForHash().delete(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY,
                            userWorldId + ":" + characterId);
                } finally {
                    redisTemplate.delete(RedisConstant.USER_CHARACTER_PROMPT_INFO_KEY_PREFIX
                            + userWorldId + ":" + characterId);
                }
            };
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) { invalidate.run(); }
                });
            } else {
                invalidate.run();
            }
        });
        favorLogMapper.delete(query);
    }
}

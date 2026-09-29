package com.me.galchat.service.impl.group;

import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.po.UserCharacterFavorLog;
import com.me.galchat.mapper.UserCharacterFavorLogMapper;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroupChatFavorRollbackServiceTest {
    @Test
    void reversesActualLoggedChangesAndInvalidatesCachesAfterTransactionCompletion() {
        var logs = mock(UserCharacterFavorLogMapper.class);
        var characters = mock(UserCharacterInfoMapper.class);
        var redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(logs.selectList(any())).thenReturn(List.of(
                new UserCharacterFavorLog().setCharacterId(9L).setFavorUpdate(2),
                new UserCharacterFavorLog().setCharacterId(9L).setFavorUpdate(-1)));
        when(characters.updateFavorValue(5L, 9L, -1)).thenReturn(80);
        var service = new GroupChatFavorRollbackService(logs, characters, redis);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.rollback(5L, List.of(103L));
            verify(characters).updateFavorValue(5L, 9L, -1);
            verify(logs).delete(any());
            verifyNoInteractions(redis);
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            assertThat(callbacks).hasSize(1);
            // The cache is also invalidated if a later operation rolls the transaction back.
            callbacks.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(hash).delete(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY, "5:9");
            verify(redis).delete(RedisConstant.USER_CHARACTER_PROMPT_INFO_KEY_PREFIX + "5:9");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}

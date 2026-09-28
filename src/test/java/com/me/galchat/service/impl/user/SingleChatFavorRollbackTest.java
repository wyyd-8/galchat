package com.me.galchat.service.impl.user;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.FavorBindingType;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.po.*;
import com.me.galchat.mapper.*;
import com.me.galchat.memory.TopicBoundaryService;
import com.me.galchat.service.*;
import com.me.galchat.service.impl.chat.SingleChatGenerationRegistry;
import com.me.galchat.service.impl.chat.SingleChatLockService;
import com.me.galchat.support.MybatisPlusTestSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SingleChatFavorRollbackTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void transactionCompletionKeepsTheNextFavorDeltaAccurate(boolean rollback) {
        MybatisPlusTestSupport.initialize(UserChatHistory.class, UserCharacterInfo.class,
                UserCharacterFavorLog.class, UserChatThinkingHistory.class, UserChatToolCall.class, UserEventLog.class);
        var dbFavor = new AtomicInteger(15);
        var cache = new HashMap<Object, Object>();
        cache.put("3:7", "15");
        var lifecycle = new ArrayList<String>();
        var redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(hash.get(eq(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY), any()))
                .thenAnswer(i -> cache.get(i.getArgument(1)));
        doAnswer(i -> { cache.remove("3:7"); lifecycle.add("evict"); return 1L; })
                .when(hash).delete(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY, "3:7");
        doAnswer(i -> { cache.put(i.getArgument(1), i.getArgument(2)); return null; })
                .when(hash).put(eq(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY), any(), any());
        var favors = mock(UserCharacterFavorLogMapper.class);
        when(favors.selectList(any())).thenReturn(List.of(new UserCharacterFavorLog().setFavorUpdate(5)));
        var characters = mock(UserCharacterInfoMapper.class);
        when(characters.updateFavorValue(eq(3L), eq(7L), anyInt()))
                .thenAnswer(i -> dbFavor.addAndGet(i.getArgument(2)));
        var histories = mock(UserChatHistoryMapper.class);
        when(histories.selectList(any())).thenReturn(List.of(new UserChatHistory().setId(20L).setType("user")));
        when(histories.update(isNull(), any())).thenReturn(1);
        var topics = mock(TopicBoundaryService.class);
        if (rollback) {
            doThrow(new IllegalStateException("vector cleanup failed")).when(topics)
                    .rollbackAfterWithdraw(anyLong(), anyLong(), anySet(), nullable(Long.class));
        }
        var locks = mock(SingleChatLockService.class);
        var lock = mock(RLock.class);
        when(locks.tryLock(3L, 7L)).thenReturn(lock);
        doAnswer(i -> { lifecycle.add("unlock"); return null; }).when(locks).unlock(lock);
        var events = mock(UserEventLogMapper.class);
        var service = new UserChatHistoryServiceImpl(mock(IUserWorldPrefixService.class),
                mock(UserChatThinkingHistoryMapper.class), mock(UserChatToolCallMapper.class), favors, characters,
                topics, locks, mock(SingleChatGenerationRegistry.class), redis, events);
        ReflectionTestUtils.setField(service, "baseMapper", histories);
        ReflectionTestUtils.setField(service, "entityClass", UserChatHistory.class);
        var tx = new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object t, TransactionDefinition d) { }
            protected void doCommit(DefaultTransactionStatus s) { lifecycle.add("commit"); }
            protected void doRollback(DefaultTransactionStatus s) { dbFavor.set(15); lifecycle.add("rollback"); }
        };
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(tx);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(service);
        factory.addAdvice(interceptor);
        var transactional = (IUserChatHistoryService) factory.getProxy();

        if (rollback) {
            assertThatThrownBy(() -> transactional.withdrawLatestUserMessage(3L, 7L))
                    .hasMessage("vector cleanup failed");
        } else {
            transactional.withdrawLatestUserMessage(3L, 7L);
        }

        assertThat(dbFavor.get()).isEqualTo(rollback ? 15 : 10);
        assertThat(cache).doesNotContainKey("3:7");
        assertThat(lifecycle).containsExactly(rollback ? "rollback" : "commit", "evict", "unlock");
        verify(redis).delete(RedisConstant.USER_CHARACTER_PROMPT_INFO_KEY_PREFIX + "3:7");
        // Withdrawal must target this message's events, leaving unrelated and legacy events alone.
        var deletion = ArgumentCaptor.forClass(Wrapper.class);
        verify(events).delete(deletion.capture());
        var query = (LambdaUpdateWrapper<?>) deletion.getValue();
        assertThat(query.getSqlSegment()).contains("user_world_id =", "character_id =", "source_user_message_id =");
        assertThat(query.getParamNameValuePairs().values()).containsExactlyInAnyOrder(3L, 7L, 20L);

        when(characters.selectOne(any())).thenAnswer(i -> new UserCharacterInfo().setFavorValue(dbFavor.get()));
        var characterService = new UserCharacterInfoServiceImpl(mock(ICharacterTemplateService.class),
                mock(IUserWorldPrefixService.class), redis, favors, histories, mock(UserChatThinkingHistoryMapper.class),
                mock(UserChatToolCallMapper.class), events, mock(GroupChatMemberMapper.class), locks,
                mock(SingleChatGenerationRegistry.class), mock(VectorStoreCleanupMapper.class));
        ReflectionTestUtils.setField(characterService, "baseMapper", characters);
        ReflectionTestUtils.setField(characterService, "entityClass", UserCharacterInfo.class);

        characterService.updateFavorValue(3L, 7L, 2, FavorBindingType.SINGLE_MESSAGE, 30L);

        var favorLog = ArgumentCaptor.forClass(UserCharacterFavorLog.class);
        verify(favors).insert(favorLog.capture());
        assertThat(favorLog.getValue().getFavorUpdate()).isEqualTo(2);
    }
}

package com.me.galchat.service.impl.user;

import com.me.galchat.constant.FavorBindingType;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.po.UserCharacterFavorLog;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.mapper.*;
import com.me.galchat.service.*;
import com.me.galchat.service.impl.chat.SingleChatGenerationRegistry;
import com.me.galchat.service.impl.chat.SingleChatLockService;
import com.me.galchat.support.MybatisPlusTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserCharacterFavorUpdateTest {
    private final AtomicInteger database = new AtomicInteger(10);
    private final Map<Object, Object> cache = new HashMap<>();
    private final List<UserCharacterFavorLog> logs = new ArrayList<>();
    private final List<String> lifecycle = new ArrayList<>();
    private final UserCharacterInfoMapper characters = mock(UserCharacterInfoMapper.class);
    private final UserCharacterFavorLogMapper favors = mock(UserCharacterFavorLogMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final HashOperations<String, Object, Object> hash = mock(HashOperations.class);
    private IUserCharacterInfoService service;

    @BeforeEach
    void setUp() {
        MybatisPlusTestSupport.initialize(UserCharacterInfo.class, UserCharacterFavorLog.class);
        cache.put("3:7", "10");
        when(redis.opsForHash()).thenReturn(hash);
        when(hash.get(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY, "3:7"))
                .thenAnswer(i -> cache.get("3:7"));
        doAnswer(i -> {
            cache.put(i.getArgument(1), i.getArgument(2));
            lifecycle.add("cache");
            return null;
        }).when(hash).put(eq(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY), any(), any());
        when(characters.selectOne(any())).thenAnswer(i -> new UserCharacterInfo().setFavorValue(database.get()));
        when(characters.compareAndSetFavorValue(eq(3L), eq(7L), anyInt(), anyInt()))
                .thenAnswer(i -> database.compareAndSet(i.getArgument(2), i.getArgument(3)) ? 1 : 0);
        when(favors.insert(any(UserCharacterFavorLog.class))).thenAnswer(i -> {
            logs.add(i.getArgument(0));
            lifecycle.add("log");
            return 1;
        });
        var target = new UserCharacterInfoServiceImpl(mock(ICharacterTemplateService.class),
                mock(IUserWorldPrefixService.class), redis, favors, mock(UserChatHistoryMapper.class),
                mock(UserChatThinkingHistoryMapper.class), mock(UserChatToolCallMapper.class),
                mock(UserEventLogMapper.class), mock(GroupChatMemberMapper.class),
                mock(SingleChatLockService.class), mock(SingleChatGenerationRegistry.class),
                mock(VectorStoreCleanupMapper.class));
        ReflectionTestUtils.setField(target, "baseMapper", characters);
        ReflectionTestUtils.setField(target, "entityClass", UserCharacterInfo.class);
        var transactions = new AbstractPlatformTransactionManager() {
            private int before;
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object transaction, TransactionDefinition definition) { before = database.get(); }
            protected void doCommit(DefaultTransactionStatus status) { lifecycle.add("commit"); }
            protected void doRollback(DefaultTransactionStatus status) {
                database.set(before);
                logs.clear();
                lifecycle.add("rollback");
            }
        };
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(interceptor);
        service = (IUserCharacterInfoService) proxy.getProxy();
    }

    @Test
    void staleRedisValueDoesNotIncludeAnotherConversationsFavorInThisLog() {
        database.set(15); // A group reply committed +5 while Redis still contains 10.

        service.updateFavorValue(3L, 7L, 2, FavorBindingType.SINGLE_MESSAGE, 30L);

        assertThat(database.get()).isEqualTo(17);
        assertThat(logs).singleElement().satisfies(log -> {
            assertThat(log.getFavorUpdate()).isEqualTo(2);
            assertThat(log.getBindingType()).isEqualTo(FavorBindingType.SINGLE_MESSAGE);
            assertThat(log.getBindingChat()).isEqualTo(30L);
        });
        assertThat(cache.get("3:7")).isEqualTo("17");
    }

    @Test
    void publishesFavorToRedisOnlyAfterLogAndTransactionCommit() {
        service.updateFavorValue(3L, 7L, 2, FavorBindingType.GROUP_REPLY_STEP, 40L);

        assertThat(lifecycle).containsExactly("log", "commit", "cache");
        assertThat(logs).singleElement().extracting(UserCharacterFavorLog::getFavorUpdate).isEqualTo(2);
    }

    @Test
    void failedLogRollsBackFavorWithoutPublishingItToRedis() {
        when(favors.insert(any(UserCharacterFavorLog.class))).thenThrow(new IllegalStateException("log failed"));

        assertThatThrownBy(() -> service.updateFavorValue(3L, 7L, 2, FavorBindingType.SINGLE_MESSAGE, 30L))
                .hasMessage("log failed");

        assertThat(database.get()).isEqualTo(10);
        assertThat(cache.get("3:7")).isEqualTo("10");
        assertThat(logs).isEmpty();
        assertThat(lifecycle).containsExactly("rollback");
    }

    @Test
    void retriesAgainstDatabaseAgainWhenTheFirstFallbackAlsoLosesARace() {
        database.set(15);
        var attempts = new AtomicInteger();
        when(characters.compareAndSetFavorValue(eq(3L), eq(7L), anyInt(), anyInt())).thenAnswer(i -> {
            if (attempts.incrementAndGet() == 2) database.set(20);
            return database.compareAndSet(i.getArgument(2), i.getArgument(3)) ? 1 : 0;
        });

        service.updateFavorValue(3L, 7L, 2, FavorBindingType.SINGLE_MESSAGE, 30L);

        assertThat(database.get()).isEqualTo(22);
        assertThat(logs).singleElement().extracting(UserCharacterFavorLog::getFavorUpdate).isEqualTo(2);
        assertThat(cache.get("3:7")).isEqualTo("22");
    }

    @Test
    void persistentConflictsFailWithoutLoggingOrPublishingAnUnappliedChange() {
        when(characters.compareAndSetFavorValue(eq(3L), eq(7L), anyInt(), anyInt())).thenReturn(0);

        assertThatThrownBy(() -> service.updateFavorValue(3L, 7L, 2, FavorBindingType.SINGLE_MESSAGE, 30L))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("重试");

        verify(characters, atMost(5)).compareAndSetFavorValue(anyLong(), anyLong(), anyInt(), anyInt());
        assertThat(database.get()).isEqualTo(10);
        assertThat(logs).isEmpty();
        assertThat(lifecycle).containsExactly("rollback");
    }

    @Test
    void deletedCharacterIsReportedInsteadOfRetriedAsContention() {
        when(characters.compareAndSetFavorValue(eq(3L), eq(7L), anyInt(), anyInt())).thenReturn(0);
        when(characters.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.updateFavorValue(3L, 7L, 2, FavorBindingType.SINGLE_MESSAGE, 30L))
                .hasMessage("角色不存在");

        assertThat(logs).isEmpty();
        assertThat(lifecycle).containsExactly("rollback");
    }

    @ParameterizedTest
    @CsvSource({"98, 5, 100, 2", "2, -5, 0, -2", "100, 5, 100, 0", "0, -5, 0, 0",
            "10, 2147483647, 100, 90", "10, -2147483648, 0, -10"})
    void recordsActualClampedDelta(int before, int change, int after, int delta) {
        database.set(before);
        cache.put("3:7", String.valueOf(before));

        service.updateFavorValue(3L, 7L, change, FavorBindingType.SINGLE_MESSAGE, 30L);

        assertThat(database.get()).isEqualTo(after);
        assertThat(logs).singleElement().extracting(UserCharacterFavorLog::getFavorUpdate).isEqualTo(delta);
    }

    @Test
    void cacheMissDoesNotPublishTheDatabaseReadBeforeCommit() {
        cache.clear();

        service.updateFavorValue(3L, 7L, null, FavorBindingType.SINGLE_MESSAGE, 30L);

        assertThat(logs).singleElement().extracting(UserCharacterFavorLog::getFavorUpdate).isEqualTo(0);
        assertThat(lifecycle).containsExactly("log", "commit", "cache");
    }

    @Test
    void postCommitCacheFailureDoesNotReportAnAlreadyAppliedToolCallAsFailed() {
        doThrow(new IllegalStateException("Redis unavailable")).when(hash)
                .put(eq(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY), any(), any());

        assertThatCode(() -> service.updateFavorValue(3L, 7L, 2, FavorBindingType.SINGLE_MESSAGE, 30L))
                .doesNotThrowAnyException();

        assertThat(database.get()).isEqualTo(12);
        assertThat(logs).singleElement().extracting(UserCharacterFavorLog::getFavorUpdate).isEqualTo(2);
        assertThat(lifecycle).containsExactly("log", "commit");
        verify(redis).delete(RedisConstant.USER_CHARACTER_PROMPT_INFO_KEY_PREFIX + "3:7");
    }
}

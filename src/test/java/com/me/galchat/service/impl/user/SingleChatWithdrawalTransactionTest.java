package com.me.galchat.service.impl.user;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.memory.TopicBoundaryService;
import com.me.galchat.memory.UserChatMemory;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.vector.ChatHistoryVectorService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.ai.chat.client.ChatClient;
import com.me.galchat.service.IUserChatHistoryService;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.impl.chat.SingleChatGenerationRegistry;
import com.me.galchat.service.impl.chat.SingleChatLockService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RLock;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SingleChatWithdrawalTransactionTest {
    private final List<String> lifecycle = new ArrayList<>();
    private final TopicBoundaryService topics = mock(TopicBoundaryService.class);
    private final UserChatHistoryMapper histories = mock(UserChatHistoryMapper.class);
    private final SingleChatLockService locks = mock(SingleChatLockService.class);
    private final SingleChatGenerationRegistry generations = mock(SingleChatGenerationRegistry.class);
    private final UserCharacterInfoMapper characterInfos = mock(UserCharacterInfoMapper.class);
    private boolean failCommit;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishesRedisBoundaryOnlyAfterCommitBeforeUnlock(boolean deleteBoundary) {
        var fixture = boundaryFixture(deleteBoundary);
        transactional(service(fixture.service())).withdrawLatestUserMessage(3L, 7L, 20L);

        assertThat(lifecycle).containsExactly("begin", "commit", "redis", "unlock");
        if (deleteBoundary) {
            assertThat(fixture.value().get()).isNull();
        } else {
            assertThat(fixture.service().getBoundary(new ConversationInfo(3L, 7L, null)).startIds())
                    .containsExactly(1L, 2L, 3L, 4L, 5L);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commitFailureLeavesRedisBoundaryUntouched(boolean deleteBoundary) {
        var fixture = boundaryFixture(deleteBoundary);
        String original = fixture.value().get();
        failCommit = true;

        assertThatThrownBy(() -> transactional(service(fixture.service()))
                .withdrawLatestUserMessage(3L, 7L, 20L)).hasMessage("commit failed");

        assertThat(fixture.value().get()).isEqualTo(original);
        assertThat(lifecycle).containsExactly("begin", "commit", "rollback", "unlock");
    }

    @Test
    void laterDatabaseFailureDoesNotPublishPreparedBoundary() {
        var fixture = boundaryFixture(false);
        String original = fixture.value().get();
        when(characterInfos.update(any())).thenThrow(new IllegalStateException("refresh failed"));

        assertThatThrownBy(() -> transactional(service(fixture.service()))
                .withdrawLatestUserMessage(3L, 7L, 20L)).hasMessage("refresh failed");

        assertThat(fixture.value().get()).isEqualTo(original);
        assertThat(lifecycle).containsExactly("begin", "rollback", "unlock");
    }

    private BoundaryFixture boundaryFixture(boolean deleteBoundary) {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        String key = RedisConstant.TOPIC_BOUNDARY_KEY_PREFIX + "3:7";
        var value = new AtomicReference<>(deleteBoundary
                ? "{\"startIds\":[20],\"lastCheckedMessageId\":20}"
                : "{\"startIds\":[1,2,3,4,5,20],\"lastCheckedMessageId\":20}");
        when(values.get(key)).thenAnswer(invocation -> value.get());
        doAnswer(invocation -> {
            lifecycle.add("redis");
            value.set(invocation.getArgument(1));
            return null;
        }).when(values).set(eq(key), anyString());
        when(redis.delete(key)).thenAnswer(invocation -> {
            lifecycle.add("redis"); value.set(null); return true;
        });
        return new BoundaryFixture(new TopicBoundaryService(redis, mock(ChatClient.class),
                mock(UserChatMemory.class), mock(ChatHistoryVectorService.class)), value);
    }

    private record BoundaryFixture(TopicBoundaryService service, AtomicReference<String> value) {}

    @Test
    void holdsConversationLockUntilWithdrawalCommits() {
        transactional(service()).withdrawLatestUserMessage(3L, 7L, 20L);

        assertThat(lifecycle).containsExactly("begin", "commit", "unlock");
    }

    @Test
    void holdsConversationLockUntilFailedWithdrawalRollsBack() {
        var failure = new IllegalStateException("boundary rollback failed");
        doThrow(failure).when(topics).rollbackAfterWithdraw(eq(3L), eq(7L), anySet(), isNull());

        assertThatThrownBy(() -> transactional(service()).withdrawLatestUserMessage(3L, 7L, 20L))
                .isSameAs(failure);
        assertThat(lifecycle).containsExactly("begin", "rollback", "unlock");
    }

    @Test
    void releasesConversationLockWhenCalledWithoutATransaction() {
        service().withdrawLatestUserMessage(3L, 7L, 20L);

        assertThat(lifecycle).containsExactly("unlock");
    }

    @ParameterizedTest
    @ValueSource(longs = {19, 21, 999})
    void checksTheExpectedAnchorUnderLockBeforeAnyMutation(long expectedMessageId) {
        var service = transactional(service());
        assertThatThrownBy(() -> service.withdrawLatestUserMessage(3L, 7L, expectedMessageId))
                .isInstanceOf(UserRequestException.class).hasMessageContaining("聊天记录已变化");
        var order = inOrder(locks, histories);
        order.verify(locks).tryLock(3L, 7L);
        order.verify(histories).selectList(any());
        verify(histories, never()).update(isNull(), any());
        verify(histories, never()).delete(any());
        verifyNoInteractions(topics, generations);
        assertThat(lifecycle).containsExactly("begin", "rollback", "unlock");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void rejectsMissingOrInvalidTargetsBeforeAcquiringTheLock(Long expectedMessageId) {
        var service = service();
        assertThatThrownBy(() -> service.withdrawLatestUserMessage(3L, 7L, expectedMessageId))
                .isInstanceOf(UserRequestException.class);
        verifyNoInteractions(locks, histories, topics, generations);
    }

    @Test
    void retryingTheSameWithdrawalNeverDeletesThePreviousRound() {
        var service = transactional(service());
        service.withdrawLatestUserMessage(3L, 7L, 20L);
        clearInvocations(histories, topics, generations);
        when(histories.selectList(any())).thenReturn(List.of(
                new UserChatHistory().setId(20L).setType("withdrawn"),
                new UserChatHistory().setId(10L).setType("assistant")));

        assertThatThrownBy(() -> service.withdrawLatestUserMessage(3L, 7L, 20L))
                .isInstanceOf(UserRequestException.class).hasMessageContaining("聊天记录已变化");
        verify(histories, never()).update(isNull(), any());
        verify(histories, never()).delete(any());
        verifyNoInteractions(topics, generations);
    }

    private UserChatHistoryServiceImpl service() {
        return service(topics);
    }

    private UserChatHistoryServiceImpl service(TopicBoundaryService topicService) {
        for (var entity : List.of(UserChatHistory.class, UserCharacterInfo.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
        when(histories.selectList(any())).thenReturn(List.of(
                new UserChatHistory().setId(20L).setType("assistant")));
        when(histories.update(isNull(), any())).thenReturn(1);
        var lock = mock(RLock.class);
        when(locks.tryLock(3L, 7L)).thenReturn(lock);
        doAnswer(invocation -> { lifecycle.add("unlock"); return null; }).when(locks).unlock(lock);
        var service = new UserChatHistoryServiceImpl(mock(IUserWorldPrefixService.class), null, null, null,
                characterInfos, topicService, locks, generations, null, null);
        ReflectionTestUtils.setField(service, "baseMapper", histories);
        ReflectionTestUtils.setField(service, "entityClass", UserChatHistory.class);
        return service;
    }

    private IUserChatHistoryService transactional(UserChatHistoryServiceImpl service) {
        var transactions = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
                lifecycle.add("begin");
            }
            @Override protected void doCommit(DefaultTransactionStatus status) {
                lifecycle.add("commit");
                if (failCommit) throw new TransactionSystemException("commit failed");
            }
            @Override protected void doRollback(DefaultTransactionStatus status) { lifecycle.add("rollback"); }
        };
        transactions.setRollbackOnCommitFailure(true);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(service);
        proxy.addAdvice(interceptor);
        return (IUserChatHistoryService) proxy.getProxy();
    }
}

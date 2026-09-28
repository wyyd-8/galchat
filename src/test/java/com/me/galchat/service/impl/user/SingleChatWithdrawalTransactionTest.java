package com.me.galchat.service.impl.user;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.memory.TopicBoundaryService;
import com.me.galchat.service.IUserChatHistoryService;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.impl.chat.SingleChatGenerationRegistry;
import com.me.galchat.service.impl.chat.SingleChatLockService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SingleChatWithdrawalTransactionTest {
    private final List<String> lifecycle = new ArrayList<>();
    private final TopicBoundaryService topics = mock(TopicBoundaryService.class);

    @Test
    void holdsConversationLockUntilWithdrawalCommits() {
        transactional(service()).withdrawLatestUserMessage(3L, 7L);

        assertThat(lifecycle).containsExactly("begin", "commit", "unlock");
    }

    @Test
    void holdsConversationLockUntilFailedWithdrawalRollsBack() {
        var failure = new IllegalStateException("boundary rollback failed");
        doThrow(failure).when(topics).rollbackAfterWithdraw(eq(3L), eq(7L), anySet(), isNull());

        assertThatThrownBy(() -> transactional(service()).withdrawLatestUserMessage(3L, 7L))
                .isSameAs(failure);
        assertThat(lifecycle).containsExactly("begin", "rollback", "unlock");
    }

    @Test
    void releasesConversationLockWhenCalledWithoutATransaction() {
        service().withdrawLatestUserMessage(3L, 7L);

        assertThat(lifecycle).containsExactly("unlock");
    }

    private UserChatHistoryServiceImpl service() {
        for (var entity : List.of(UserChatHistory.class, UserCharacterInfo.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
        var histories = mock(UserChatHistoryMapper.class);
        when(histories.selectList(any())).thenReturn(List.of(
                new UserChatHistory().setId(20L).setType("assistant")));
        when(histories.update(isNull(), any())).thenReturn(1);
        var locks = mock(SingleChatLockService.class);
        var lock = mock(RLock.class);
        when(locks.tryLock(3L, 7L)).thenReturn(lock);
        doAnswer(invocation -> { lifecycle.add("unlock"); return null; }).when(locks).unlock(lock);
        var service = new UserChatHistoryServiceImpl(mock(IUserWorldPrefixService.class), null, null, null,
                mock(UserCharacterInfoMapper.class), topics, locks, mock(SingleChatGenerationRegistry.class), null, null);
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
            @Override protected void doCommit(DefaultTransactionStatus status) { lifecycle.add("commit"); }
            @Override protected void doRollback(DefaultTransactionStatus status) { lifecycle.add("rollback"); }
        };
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(service);
        proxy.addAdvice(interceptor);
        return (IUserChatHistoryService) proxy.getProxy();
    }
}

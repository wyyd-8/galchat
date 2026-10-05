package com.me.galchat.service.impl.user;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.mapper.*;
import com.me.galchat.service.ICharacterTemplateService;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.me.galchat.service.IUserCharacterInfoService;
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

class SingleChatDeletionTransactionTest {
    private final List<String> lifecycle = new ArrayList<>();
    private final VectorStoreCleanupMapper vectors = mock(VectorStoreCleanupMapper.class);

    @Test
    void holdsConversationLockUntilDeletionCommits() {
        transactional(service()).deleteCharacter(3L, 7L);

        assertThat(lifecycle).containsExactly("begin", "commit", "unlock");
    }

    @Test
    void holdsConversationLockUntilFailedDeletionRollsBack() {
        var failure = new IllegalStateException("boundary rollback failed");
        doThrow(failure).when(vectors).deleteChatHistoryByConversation(3L, 7L);

        assertThatThrownBy(() -> transactional(service()).deleteCharacter(3L, 7L))
                .isSameAs(failure);
        assertThat(lifecycle).containsExactly("begin", "rollback", "unlock");
    }

    @Test
    void releasesConversationLockWhenCalledWithoutATransaction() {
        service().deleteCharacter(3L, 7L);

        assertThat(lifecycle).containsExactly("unlock");
    }

    private UserCharacterInfoServiceImpl service() {
        for (var entity : List.of(UserChatHistory.class, UserCharacterInfo.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
        var histories = mock(UserChatHistoryMapper.class);
        var characters = mock(UserCharacterInfoMapper.class);
        when(characters.selectOne(any(), anyBoolean())).thenReturn(new UserCharacterInfo());
        when(characters.selectOne(any())).thenReturn(new UserCharacterInfo());
        when(characters.delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        var locks = mock(SingleChatLockService.class);
        var lock = mock(RLock.class);
        when(locks.tryLock(3L, 7L)).thenReturn(lock);
        doAnswer(invocation -> { lifecycle.add("unlock"); return null; }).when(locks).unlock(lock);
        var redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        var service = new UserCharacterInfoServiceImpl(mock(ICharacterTemplateService.class),
                mock(IUserWorldPrefixService.class), redis, mock(UserCharacterFavorLogMapper.class), histories,
                mock(UserChatThinkingHistoryMapper.class), mock(UserChatToolCallMapper.class),
                mock(UserEventLogMapper.class), mock(GroupChatMemberMapper.class), locks,
                mock(SingleChatGenerationRegistry.class), vectors, mock(com.me.galchat.service.impl.group.GroupConversationLockService.class));
        ReflectionTestUtils.setField(service, "baseMapper", characters);
        ReflectionTestUtils.setField(service, "entityClass", UserCharacterInfo.class);
        return service;
    }

    private IUserCharacterInfoService transactional(UserCharacterInfoServiceImpl service) {
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
        return (IUserCharacterInfoService) proxy.getProxy();
    }
}

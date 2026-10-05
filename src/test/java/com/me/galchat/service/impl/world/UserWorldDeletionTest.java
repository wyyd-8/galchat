package com.me.galchat.service.impl.world;

import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.po.UserWorldSave;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.*;
import com.me.galchat.service.*;
import com.me.galchat.service.impl.group.*;
import com.me.galchat.support.MybatisPlusTestSupport;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.redisson.api.RLock;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserWorldDeletionTest {
    @Mock IWorldTemplateService templates;
    @Mock UserCharacterInfoMapper characters;
    @Mock UserWorldPrefixMapper worlds;
    @Mock GroupConversationMapper conversations;
    @Mock UserWorldSaveMapper saves;
    @Mock GroupConversationLockService locks;
    @Mock GroupConversationDeletionStore deletionStore;
    @Mock ITrpgRedisStateService redisState;
    @Mock GroupGenerationStreamRegistry generations;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) StringRedisTemplate redis;
    @InjectMocks UserWorldPrefixServiceImpl target;
    AutoCloseable mocks;
    final List<String> lifecycle = new ArrayList<>();
    final GroupConversation chat = new GroupConversation().setId(7L).setUserWorldId(3L).setMode("chat");
    final GroupConversation trpg = new GroupConversation().setId(8L).setUserWorldId(3L).setMode("trpg").setModuleId(20L);
    GroupConversationLockService.OwnedLock worldLock, chatLock, trpgLock;

    @BeforeEach
    void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        MybatisPlusTestSupport.initialize(GroupConversation.class, UserWorldSave.class);
        ReflectionTestUtils.setField(target, "baseMapper", worlds);
        when(worlds.selectByIdAndUserId(3L, 1L)).thenReturn(new UserWorldPrefix().setId(3L).setUserId(1L));
        when(worlds.deleteByIdAndUserId(3L, 1L)).thenReturn(1);
        when(conversations.selectList(any())).thenReturn(List.of(chat, trpg));
        worldLock = ownedLock(); chatLock = ownedLock(); trpgLock = ownedLock();
        when(locks.tryWorldLock(3L)).thenReturn(worldLock);
        when(locks.tryLock(7L)).thenReturn(chatLock);
        when(locks.tryLock(8L)).thenReturn(trpgLock);
        doAnswer(call -> { lifecycle.add("unlock"); return null; }).when(locks).unlock(any());
        doAnswer(call -> { lifecycle.add("clear:" + call.getArgument(0)); return null; }).when(redisState).clear(anyLong());
    }

    @AfterEach void close() throws Exception { mocks.close(); }

    @Test
    void deletesEveryRemainingConversationAndTheSaveBeforeDeletingWorld() {
        transactional().deleteUserWorld(1L, 3L);
        var order = inOrder(locks, conversations, deletionStore, saves, worlds);
        order.verify(locks).tryWorldLock(3L);
        order.verify(conversations).selectList(argThat(query -> {
            var q = (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) query;
            return q.getSqlSegment().contains("user_world_id") && q.getParamNameValuePairs().containsValue(3L);
        }));
        order.verify(locks).tryLock(7L);
        order.verify(locks).tryLock(8L);
        order.verify(deletionStore).delete(chat);
        order.verify(deletionStore).delete(trpg);
        order.verify(saves).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        order.verify(worlds).deleteByIdAndUserId(3L, 1L);
        assertThat(lifecycle).containsExactly("begin", "commit", "clear:7", "clear:8", "unlock", "unlock", "unlock");
        verify(generations).evict(7L);
        verify(generations).evict(8L);
    }

    @Test
    void busyConversationPreventsAnyDeletion() {
        when(locks.tryLock(8L)).thenReturn(null);
        assertThatThrownBy(() -> transactional().deleteUserWorld(1L, 3L)).isInstanceOf(UserRequestException.class);
        verifyNoInteractions(deletionStore, saves, redisState, generations);
        verify(worlds, never()).deleteByIdAndUserId(anyLong(), anyLong());
        assertThat(lifecycle).containsExactly("begin", "rollback", "unlock", "unlock");
    }

    @Test
    void failureRollsBackWithoutClearingTransientStateAndHoldsLocksUntilRollback() {
        var failure = new IllegalStateException("database failure");
        doThrow(failure).when(deletionStore).delete(trpg);
        assertThatThrownBy(() -> transactional().deleteUserWorld(1L, 3L)).isSameAs(failure);
        verify(worlds, never()).deleteByIdAndUserId(anyLong(), anyLong());
        verifyNoInteractions(redisState, generations);
        assertThat(lifecycle).containsExactly("begin", "rollback", "unlock", "unlock", "unlock");
    }

    @Test
    void aRemainingCharacterStillPreventsWorldAndConversationDeletion() {
        when(characters.countByUserWorldId(3L)).thenReturn(1);
        assertThatThrownBy(() -> transactional().deleteUserWorld(1L, 3L)).isInstanceOf(UserRequestException.class);
        verifyNoInteractions(deletionStore, saves, redisState, generations);
        verify(worlds, never()).deleteByIdAndUserId(anyLong(), anyLong());
    }

    @Test
    void aBusyWorldPreventsAnyDeletion() {
        when(locks.tryWorldLock(3L)).thenReturn(null);
        assertThatThrownBy(() -> transactional().deleteUserWorld(1L, 3L)).isInstanceOf(UserRequestException.class);
        verifyNoInteractions(deletionStore, saves, redisState, generations);
        verify(worlds, never()).deleteByIdAndUserId(anyLong(), anyLong());
    }

    @Test
    void anotherUsersWorldCannotBeDeleted() {
        when(worlds.selectByIdAndUserId(3L, 1L)).thenReturn(null);
        assertThatThrownBy(() -> transactional().deleteUserWorld(1L, 3L)).isInstanceOf(UserRequestException.class);
        verifyNoInteractions(deletionStore, saves, redisState, generations);
    }

    private GroupConversationLockService.OwnedLock ownedLock() {
        return new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L);
    }

    private IUserWorldPrefixService transactional() {
        var transactions = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) { lifecycle.add("begin"); }
            @Override protected void doCommit(DefaultTransactionStatus status) { lifecycle.add("commit"); }
            @Override protected void doRollback(DefaultTransactionStatus status) { lifecycle.add("rollback"); }
        };
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(interceptor);
        return (IUserWorldPrefixService) proxy.getProxy();
    }
}

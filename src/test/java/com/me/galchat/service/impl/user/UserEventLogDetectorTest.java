package com.me.galchat.service.impl.user;

import com.me.galchat.constant.ChatConstant;
import com.me.galchat.domain.po.*;
import com.me.galchat.mapper.*;
import com.me.galchat.service.IUserEventLogService;
import com.me.galchat.service.impl.chat.SingleChatLockService;
import com.me.galchat.support.MybatisPlusTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.redisson.api.RLock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserEventLogDetectorTest {
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) ChatClient client;
    @Mock UserChatHistoryMapper histories;
    @Mock UserCharacterInfoMapper characters;
    @Mock IUserEventLogService events;
    @Mock SingleChatLockService locks;
    final List<String> lifecycle = new ArrayList<>();
    @Spy TransactionTemplate transactions = new TransactionTemplate(new AbstractPlatformTransactionManager() {
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object t, TransactionDefinition d) { lifecycle.add("begin"); }
        protected void doCommit(DefaultTransactionStatus s) { lifecycle.add("commit"); }
        protected void doRollback(DefaultTransactionStatus s) { lifecycle.add("rollback"); }
    });
    @Mock UserEventLogRetryScheduler retryScheduler;
    @InjectMocks UserEventLogDetector detector;
    AutoCloseable mocks;

    @BeforeEach void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        MybatisPlusTestSupport.initialize(UserCharacterInfo.class, UserEventLog.class);
        doAnswer(i -> { lifecycle.add("unlock"); return null; }).when(locks).unlock(any(RLock.class));
        when(client.prompt().user(anyString()).call().content()).thenReturn(
                "{\"eventDescription\":\"明天考试\",\"time\":\"2026-09-29T09:00:00\"}");
        when(histories.selectById(20L)).thenReturn(source());
        when(locks.tryLock(3L, 7L)).thenReturn(mock(RLock.class));
        when(characters.selectCount(any())).thenReturn(1L);
    }
    @AfterEach void cleanup() throws Exception { mocks.close(); }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void doesNotSaveWhenSourceWasWithdrawnOrDeletedWhileModelWasRunning(boolean withdrawn) {
        when(client.prompt().user(anyString()).call().content()).thenAnswer(i -> {
            // The independent model finishes after withdrawal/deletion has committed.
            when(histories.selectById(20L)).thenReturn(withdrawn
                    ? source().setType(ChatConstant.WITHDRAWN_TYPE) : null);
            return "{\"eventDescription\":\"明天考试\",\"time\":\"2026-09-29T09:00:00\"}";
        });
        detector.detectAndSave(3L, 7L, 20L, List.of(new UserChatHistory()
                .setId(20L).setUserWorldId(3L).setCharacterId(7L).setType("user").setContent("明天考试")));
        verify(events, never()).addUserEventLog(any());
        verify(histories).selectById(20L);
        assertThat(lifecycle).containsExactly("begin", "commit", "unlock");
    }
    @Test void savesWithSourceAndCommitsBeforeUnlocking() {
        detect();
        verify(events).addUserEventLog(argThat(event -> event.getSourceUserMessageId().equals(20L)
                && event.getUserWorldId().equals(3L) && event.getCharacterId().equals(7L)));
        assertThat(lifecycle).containsExactly("begin", "commit", "unlock");
    }

    @Test void lockContentionRetriesPersistenceWithoutCallingModelAgain() {
        when(locks.tryLock(3L, 7L)).thenReturn(null, mock(RLock.class));
        detect();
        verifyNoInteractions(events);
        var retry = ArgumentCaptor.forClass(Runnable.class);
        verify(retryScheduler).schedule(retry.capture());
        retry.getValue().run();
        verify(client.prompt().user(anyString()).call(), times(1)).content();
        verify(events).addUserEventLog(any());
    }

    @Test void delayedRetryAlsoRechecksWithdrawnSource() {
        when(locks.tryLock(3L, 7L)).thenReturn(null, mock(RLock.class));
        detect();
        var retry = ArgumentCaptor.forClass(Runnable.class);
        verify(retryScheduler).schedule(retry.capture());
        when(histories.selectById(20L)).thenReturn(source().setType(ChatConstant.WITHDRAWN_TYPE));
        retry.getValue().run();
        verify(events, never()).addUserEventLog(any());
    }

    @Test void skipsDeletedCharacterEvenIfHistoryStillExists() {
        when(characters.selectCount(any())).thenReturn(0L);
        detect();
        verify(events, never()).addUserEventLog(any());
    }

    @Test void repeatedDetectionDoesNotDuplicateAnExistingSource() {
        when(events.count(any())).thenReturn(1L);
        detect();
        verify(events, never()).addUserEventLog(any());
    }

    @Test void persistenceFailureRollsBackBeforeReleasingLock() {
        when(events.addUserEventLog(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(this::detect).hasMessage("database unavailable");
        assertThat(lifecycle).containsExactly("begin", "rollback", "unlock");
    }

    private UserChatHistory source() {
        return new UserChatHistory().setId(20L).setUserWorldId(3L).setCharacterId(7L)
                .setType("user").setContent("明天考试");
    }
    private void detect() { detector.detectAndSave(3L, 7L, 20L, List.of(source())); }

}

package com.me.galchat.consumer;

import com.me.galchat.constant.UserEventLogConstant;
import com.me.galchat.domain.dto.UserEventLogDelayTaskDTO;
import com.me.galchat.domain.po.*;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.memory.TopicBoundaryService;
import com.me.galchat.service.*;
import com.me.galchat.service.impl.chat.SingleChatLockService;
import com.me.galchat.service.impl.chat.SingleChatRuntimeService;
import com.me.galchat.support.MybatisPlusTestSupport;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.redisson.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserEventLogConcurrencyTest {
    @Mock RedissonClient redisson;
    @Mock IUserEventLogService events;
    @Mock IUserWorldPrefixService worlds;
    @Mock IUserCharacterInfoService characters;
    @Mock UserChatHistoryMapper histories;
    @Mock TopicBoundaryService topics;
    @Mock SingleChatLockService locks;
    @Mock SingleChatRuntimeService runtime;
    @Mock RLock lock;
    @Mock RDelayedQueue<UserEventLogDelayTaskDTO> queue;
    @Mock ChatClient client;
    @Mock(answer = Answers.RETURNS_SELF) ChatClient.ChatClientRequestSpec request;
    @Mock ChatClient.CallResponseSpec response;
    @InjectMocks UserEventLogConsumer consumer;
    private AutoCloseable mocks;

    @BeforeEach
    void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        MybatisPlusTestSupport.initialize(UserCharacterInfo.class, UserEventLog.class);
        ReflectionTestUtils.setField(consumer, "userEventCareClient", client);
        ReflectionTestUtils.setField(consumer, "userEventLogDelayedQueue", queue);
        when(worlds.getById(10L)).thenReturn(new UserWorldPrefix().setId(10L).setAcitvePushStatus(true));
        when(characters.getOne(any())).thenReturn(new UserCharacterInfo());
        when(events.listByIds(List.of(30L))).thenReturn(List.of(new UserEventLog()
                .setId(30L).setUserWorldId(10L).setCharacterId(20L).setEventDescription("明天考试")));
        when(locks.tryLock(10L, 20L)).thenReturn(lock);
        when(runtime.careClient(10L, 20L, client)).thenReturn(client);
        when(client.prompt()).thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.content()).thenReturn("考试顺利吗？");
    }

    @AfterEach void cleanup() throws Exception { mocks.close(); }

    @Test
    void busyConversationUsesTheSameDelayedRetryAsRecentChat() {
        when(locks.tryLock(10L, 20L)).thenReturn(null);
        var task = task();
        handle(task);
        assertThat(task.getRetryCount()).isEqualTo(1);
        verify(queue).offer(task, UserEventLogConstant.RETRY_DELAY.toMillis(), TimeUnit.MILLISECONDS);
        verifyNoInteractions(client, histories, topics);
        verify(locks, never()).unlock(any(RLock.class));
    }

    @Test
    void busyConversationStopsAtTheExistingRetryLimit() {
        when(locks.tryLock(10L, 20L)).thenReturn(null);
        handle(task().setRetryCount(UserEventLogConstant.MAX_RETRY_COUNT));
        verifyNoInteractions(queue, client, histories, topics);
    }

    @Test
    void keepsLockFromCharacterReadUntilHistoryAndTopicWritesFinish() {
        handle(task());
        var order = inOrder(locks, characters, client, histories, topics);
        order.verify(locks).tryLock(10L, 20L);
        order.verify(characters).getOne(any());
        order.verify(client).prompt();
        order.verify(histories).insert(any(UserChatHistory.class));
        order.verify(topics).startAssistantMessageTopic(any());
        order.verify(characters).update(any(UserCharacterInfo.class), any());
        order.verify(locks).unlock(lock);
    }

    @Test
    void recentChatRetriesAndReleasesTheAcquiredLock() {
        when(characters.getOne(any())).thenReturn(new UserCharacterInfo().setLastChatTime(LocalDateTime.now()));
        var task = task();
        handle(task);
        assertThat(task.getRetryCount()).isEqualTo(1);
        verify(queue).offer(task, UserEventLogConstant.RETRY_DELAY.toMillis(), TimeUnit.MILLISECONDS);
        verify(locks).unlock(lock);
        verifyNoInteractions(client, histories, topics);
    }

    @Test
    void generationFailureReleasesTheLock() {
        var failure = new IllegalStateException("model unavailable");
        when(response.content()).thenThrow(failure);
        assertThatThrownBy(() -> handle(task())).isSameAs(failure);
        verify(locks).unlock(lock);
        verifyNoInteractions(histories, topics);
    }

    @Test
    void emptyReplyReleasesTheLockWithoutWritingHistory() {
        when(response.content()).thenReturn("");
        handle(task());
        verify(locks).unlock(lock);
        verifyNoInteractions(histories, topics);
    }

    @Test
    void queuedCareIsSkippedWhenWithdrawalRemovedItsEvents() {
        when(events.listByIds(List.of(30L))).thenReturn(List.of());
        handle(task());
        verifyNoInteractions(client, runtime, histories, topics);
        verify(locks).unlock(lock);
    }

    private UserEventLogDelayTaskDTO task() {
        return new UserEventLogDelayTaskDTO().setUserWorldId(10L).setCharacterId(20L)
                .setTaskType(UserEventLogConstant.TASK_TYPE_UPCOMING).setUserEventLogIds(List.of(30L));
    }

    private void handle(UserEventLogDelayTaskDTO task) {
        ReflectionTestUtils.invokeMethod(consumer, "handleUserEventLogTask", task);
    }
}

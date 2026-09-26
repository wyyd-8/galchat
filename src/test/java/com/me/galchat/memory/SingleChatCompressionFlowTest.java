package com.me.galchat.memory;

import com.me.galchat.domain.dto.ChatMessageDTO;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.vo.ChatFluxVO;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.impl.chat.ChatServiceImpl;
import com.me.galchat.service.impl.chat.SingleChatLockService;
import com.me.galchat.service.impl.chat.SingleChatRuntimeService;
import com.me.galchat.service.impl.trpg.TrpgRunMemoryService;
import com.me.galchat.vector.MutiSearchService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SingleChatCompressionFlowTest {
    @Test void firstReplyArrivesBeforeArchivalWhileLockRemainsHeld() throws Exception {
        var memory = mock(UserChatMemory.class);
        var boundary = mock(TopicBoundaryService.class);
        var user = new UserChatHistory().setId(40L).setType("user").setContent("新问题");
        when(memory.save(any(), any())).thenReturn(user);
        when(memory.listHistories(any())).thenReturn(List.of(user));
        when(memory.toPromptMessages(any())).thenReturn(List.of(new UserMessage("新问题")));
        when(boundary.getBoundary(any())).thenReturn(new TopicBoundary(List.of(10L, 20L, 30L), 32L));
        var published = new AtomicBoolean();
        when(boundary.prepareUpdate(any(), eq(user))).thenReturn(() -> published.set(true));
        var model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(
                List.of(new Generation(new AssistantMessage("即时回复"))))));
        var client = ChatClient.builder(model).defaultAdvisors(TopicAwareMessageChatMemoryAdvisor
                .builder(memory, boundary, mock(MutiSearchService.class)).build()).build();
        var runtime = mock(SingleChatRuntimeService.class);
        when(runtime.chatClient(any(), any(), any())).thenReturn(client);
        var locks = mock(SingleChatLockService.class);
        var lock = new SingleChatLockService.OwnedLock(mock(RLock.class), 1);
        when(locks.tryLockWithOwner(1L, 2L)).thenReturn(lock);
        var service = new ChatServiceImpl(client, client, runtime, null, mock(UserChatHistoryMapper.class),
                mock(IUserWorldPrefixService.class), mock(IUserCharacterInfoService.class), null, null,
                locks, mock(TrpgRunMemoryService.class));
        var queue = new LinkedBlockingQueue<Runnable>();
        ReflectionTestUtils.setField(service, "topicCompressionTaskExecutor", (TaskExecutor) queue::add);
        ReflectionTestUtils.setField(service, "userEventLogTaskExecutor", (TaskExecutor) ignored -> {});
        var request = new ChatMessageDTO();
        request.setWorldId(3L); request.setUserWorldId(1L); request.setCharacterId(2L); request.setMessage("新问题");
        var chunks = new CopyOnWriteArrayList<ChatFluxVO>();
        var firstReply = new CountDownLatch(1);
        var complete = new CompletableFuture<Void>();
        service.chat(request).subscribe(chunk -> { chunks.add(chunk); firstReply.countDown(); },
                complete::completeExceptionally, () -> complete.complete(null));
        assertThat(firstReply.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(chunks).extracting(ChatFluxVO::getContent).contains("即时回复");
        assertThat(complete).isNotDone();
        assertThat(published).isFalse();
        verify(locks, never()).unlock(lock);
        queue.take().run();
        complete.get(5, TimeUnit.SECONDS);
        assertThat(published).isTrue();
        verify(locks).unlock(lock);
    }
}

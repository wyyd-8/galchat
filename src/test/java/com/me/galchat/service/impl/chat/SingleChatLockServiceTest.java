package com.me.galchat.service.impl.chat;

import org.junit.jupiter.api.Test;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SingleChatLockServiceTest {
    @Test
    void backgroundGenerationDoesNotLetAReusedRequestThreadReenterItsLock() throws Exception {
        var client = mock(RedissonClient.class);
        var lock = mock(RLock.class);
        when(client.getLock(anyString())).thenReturn(lock);
        var owner = new AtomicLong();
        when(lock.tryLock(anyLong(), any())).thenAnswer(call -> {
            long thread = Thread.currentThread().threadId();
            return owner.compareAndSet(0, thread) || owner.get() == thread;
        });
        when(lock.tryLockAsync(anyLong(), anyLong(), any(), anyLong())).thenAnswer(call -> {
            long task = call.getArgument(3);
            @SuppressWarnings("unchecked") RFuture<Boolean> result = mock(RFuture.class);
            when(result.get()).thenReturn(owner.compareAndSet(0, task) || owner.get() == task);
            return result;
        });
        var service = new SingleChatLockService(client);
        var generation = service.tryLockWithOwner(2L, 3L);
        assertThat(generation).isNotNull();
        // The servlet thread is free again while the detached model reply still owns the lock.
        assertThat(service.tryLock(2L, 3L)).isNull();
        assertThat(service.tryLockWithOwner(2L, 3L)).isNull();
    }
    @Test
    void interruptedAcquisitionReleasesALockThatArrivesAfterTheCallerLeaves() {
        var client = mock(RedissonClient.class);
        var lock = mock(RLock.class);
        when(client.getLock(anyString())).thenReturn(lock);
        var granted = new java.util.concurrent.CompletableFuture<Boolean>();
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        var owner = new AtomicLong();
        when(lock.tryLockAsync(anyLong(), anyLong(), any(), anyLong())).thenAnswer(call -> {
            owner.set(call.getArgument(3));
            return new org.redisson.misc.CompletableFutureWrapper<>(granted);
        });
        when(lock.unlockAsync(anyLong())).thenAnswer(call -> {
            released.set(owner.get() == (long) call.getArgument(0));
            return new org.redisson.misc.CompletableFutureWrapper<Void>((Void) null);
        });
        var service = new SingleChatLockService(client);
        try {
            Thread.currentThread().interrupt();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.tryLockWithOwner(2L, 3L))
                    .isInstanceOf(com.me.galchat.exception.UserRequestException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        granted.complete(true);
        assertThat(released).isTrue();
    }

}

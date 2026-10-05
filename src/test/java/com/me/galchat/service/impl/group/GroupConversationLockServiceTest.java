package com.me.galchat.service.impl.group;

import com.me.galchat.exception.UserRequestException;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.misc.CompletableFutureWrapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroupConversationLockServiceTest {
    @Test
    void reusedRequestThreadCannotReenterBackgroundGenerationAndAnotherThreadCanReleaseIt() throws Exception {
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
            return new CompletableFutureWrapper<>(owner.compareAndSet(0, task) || owner.get() == task);
        });
        when(lock.unlockAsync(anyLong())).thenAnswer(call -> {
            long task = call.getArgument(0);
            if (!owner.compareAndSet(task, 0)) throw new IllegalMonitorStateException();
            return new CompletableFutureWrapper<Void>((Void) null);
        });
        var service = new GroupConversationLockService(client);
        var generation = service.tryLockWithOwner(7L);
        assertThat(generation).isNotNull();
        assertThat(service.tryLock(7L)).isNull();
        assertThat(service.tryLockWithOwner(7L)).isNull();
        verify(lock, atLeastOnce()).tryLockAsync(eq(0L), eq(-1L), eq(TimeUnit.MILLISECONDS), anyLong());

        CompletableFuture.runAsync(() -> service.unlock(generation)).get(5, TimeUnit.SECONDS);
        assertThat(service.tryLockWithOwner(7L)).isNotNull();
    }

    @Test
    void interruptedAcquisitionReleasesALockGrantedLater() {
        var client = mock(RedissonClient.class);
        var lock = mock(RLock.class);
        when(client.getLock(anyString())).thenReturn(lock);
        var granted = new CompletableFuture<Boolean>();
        var owner = new AtomicLong();
        when(lock.tryLockAsync(anyLong(), anyLong(), any(), anyLong())).thenAnswer(call -> {
            owner.set(call.getArgument(3));
            return new CompletableFutureWrapper<>(granted);
        });
        when(lock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<Void>((Void) null));
        var service = new GroupConversationLockService(client);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> service.tryLockWithOwner(7L)).isInstanceOf(UserRequestException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        granted.complete(true);
        verify(lock).unlockAsync(owner.get());
    }

    @Test
    void synchronousLockPreventsGenerationFromEnteringOnTheSameThread() throws Exception {
        var client = mock(RedissonClient.class);
        var lock = mock(RLock.class);
        when(client.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any())).thenReturn(true);
        when(lock.tryLockAsync(anyLong(), anyLong(), any(), anyLong())).thenAnswer(call ->
                new CompletableFutureWrapper<>((long) call.getArgument(3) == Thread.currentThread().threadId()));
        var service = new GroupConversationLockService(client);
        assertThat(service.tryLock(7L)).isNotNull();
        assertThat(service.tryLockWithOwner(7L)).isNull();
    }
}

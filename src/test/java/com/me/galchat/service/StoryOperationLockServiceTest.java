package com.me.galchat.service;

import com.me.galchat.constant.RedisConstant;
import com.me.galchat.service.impl.StoryOperationLockService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoryOperationLockServiceTest {

    @Test
    void tryLockUserCharacterWithOwnerKeepsOwnerThreadId() throws InterruptedException {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        StoryOperationLockService lockService = new StoryOperationLockService(redissonClient);

        when(redissonClient.getLock(RedisConstant.USER_CHARACTER_LOCK_PREFIX + "1:2")).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.MILLISECONDS)).thenReturn(true);

        StoryOperationLockService.OwnedLock ownedLock = lockService.tryLockUserCharacterWithOwner(1L, 2L);

        assertThat(ownedLock.lock()).isSameAs(lock);
        assertThat(ownedLock.ownerThreadId()).isEqualTo(Thread.currentThread().threadId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unlockOwnedLockReleasesByOwnerThreadId() {
        RLock lock = mock(RLock.class);
        RFuture<Void> future = mock(RFuture.class);
        long ownerThreadId = 123L;
        StoryOperationLockService lockService = new StoryOperationLockService(mock(RedissonClient.class));

        when(lock.unlockAsync(ownerThreadId)).thenReturn(future);
        when(future.toCompletableFuture()).thenReturn(CompletableFuture.completedFuture(null));

        lockService.unlock(new StoryOperationLockService.OwnedLock(lock, ownerThreadId));

        verify(lock).unlockAsync(ownerThreadId);
        verify(lock, never()).unlock();
    }
}

package com.me.galchat.service.impl;

import com.me.galchat.constant.RedisConstant;
import com.me.galchat.constant.StoryConstant;
import com.me.galchat.exception.UserRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class StoryOperationLockService {

    private final RedissonClient redissonClient;

    public record OwnedLock(RLock lock, long ownerThreadId) {
    }

    public List<RLock> lockStoryCharacters(Long userWorldId, List<Long> characterIds) {
        List<RLock> locks = new ArrayList<>();
        try {
            for (Long characterId : distinctSortedCharacterIds(characterIds)) {
                locks.add(lock(userCharacterLock(userWorldId, characterId), StoryConstant.STORY_LOCK_WAIT));
            }
            return locks;
        } catch (RuntimeException e) {
            unlockAll(locks);
            throw e;
        }
    }

    public RLock tryLockUserCharacter(Long userWorldId, Long characterId) {
        return tryLock(userCharacterLock(userWorldId, characterId), StoryConstant.CHAT_LOCK_WAIT);
    }

    public OwnedLock tryLockUserCharacterWithOwner(Long userWorldId, Long characterId) {
        return tryLockWithOwner(userCharacterLock(userWorldId, characterId), StoryConstant.CHAT_LOCK_WAIT);
    }

    public void unlockAll(List<RLock> locks) {
        if (locks == null || locks.isEmpty()) {
            return;
        }
        for (int i = locks.size() - 1; i >= 0; i--) {
            unlock(locks.get(i));
        }
    }

    public void unlock(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    public void unlock(OwnedLock ownedLock) {
        if (ownedLock == null || ownedLock.lock() == null) {
            return;
        }
        try {
            ownedLock.lock().unlockAsync(ownedLock.ownerThreadId()).toCompletableFuture().join();
        } catch (CompletionException e) {
            handleUnlockFailure(ownedLock.lock(), ownedLock.ownerThreadId(), e.getCause() == null ? e : e.getCause());
        } catch (IllegalMonitorStateException e) {
            handleUnlockFailure(ownedLock.lock(), ownedLock.ownerThreadId(), e);
        }
    }

    private RLock lock(RLock lock, Duration waitTime) {
        RLock locked = tryLock(lock, waitTime);
        if (locked == null) {
            throw new UserRequestException("故事切换或结束中，请稍后再试");
        }
        return locked;
    }

    private RLock tryLock(RLock lock, Duration waitTime) {
        try {
            boolean locked = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);
            return locked ? lock : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UserRequestException("故事操作被中断，请稍后再试");
        }
    }

    private OwnedLock tryLockWithOwner(RLock lock, Duration waitTime) {
        long ownerThreadId = Thread.currentThread().threadId();
        try {
            boolean locked = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);
            return locked ? new OwnedLock(lock, ownerThreadId) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UserRequestException("故事操作被中断，请稍后再试");
        }
    }

    private void handleUnlockFailure(RLock lock, long ownerThreadId, Throwable throwable) {
        if (throwable instanceof IllegalMonitorStateException) {
            log.debug("锁已不再由原线程持有, lock:{}, ownerThreadId:{}", lock.getName(), ownerThreadId);
            return;
        }
        log.warn("释放故事操作锁失败, lock:{}, ownerThreadId:{}", lock.getName(), ownerThreadId, throwable);
    }

    private List<Long> distinctSortedCharacterIds(List<Long> characterIds) {
        if (characterIds == null) {
            return List.of();
        }
        return characterIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    private RLock userCharacterLock(Long userWorldId, Long characterId) {
        return redissonClient.getLock(RedisConstant.USER_CHARACTER_LOCK_PREFIX + userWorldId + ":" + characterId);
    }
}

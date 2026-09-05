package com.me.galchat.service.impl.chat;

import com.me.galchat.constant.RedisConstant;
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

/** 只保护同一用户、同一角色的单聊消息和相关状态，不参与群聊串行化。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SingleChatLockService {

    private static final Duration BATCH_WAIT = Duration.ofSeconds(5);
    private static final Duration CHAT_WAIT = Duration.ofSeconds(3);

    private final RedissonClient redissonClient;

    public record OwnedLock(RLock lock, long ownerThreadId) {
    }

    public List<RLock> lockConversations(Long userWorldId, List<Long> characterIds) {
        List<RLock> locks = new ArrayList<>();
        try {
            for (Long characterId : distinctSortedCharacterIds(characterIds)) {
                locks.add(requireLock(singleChatLock(userWorldId, characterId), BATCH_WAIT));
            }
            return locks;
        } catch (RuntimeException e) {
            unlockAll(locks);
            throw e;
        }
    }

    public RLock tryLock(Long userWorldId, Long characterId) {
        return acquire(singleChatLock(userWorldId, characterId), CHAT_WAIT);
    }

    public OwnedLock tryLockWithOwner(Long userWorldId, Long characterId) {
        RLock lock = singleChatLock(userWorldId, characterId);
        long ownerThreadId = Thread.currentThread().threadId();
        try {
            return lock.tryLock(CHAT_WAIT.toMillis(), TimeUnit.MILLISECONDS)
                    ? new OwnedLock(lock, ownerThreadId) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UserRequestException("单聊操作被中断，请稍后再试");
        }
    }

    public void unlockAll(List<RLock> locks) {
        if (locks == null) {
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
            handleUnlockFailure(ownedLock.lock(), ownedLock.ownerThreadId(),
                    e.getCause() == null ? e : e.getCause());
        } catch (IllegalMonitorStateException e) {
            handleUnlockFailure(ownedLock.lock(), ownedLock.ownerThreadId(), e);
        }
    }

    private RLock requireLock(RLock lock, Duration waitTime) {
        RLock result = acquire(lock, waitTime);
        if (result == null) {
            throw new UserRequestException("单聊正在处理中，请稍后再试");
        }
        return result;
    }

    private RLock acquire(RLock lock, Duration waitTime) {
        try {
            return lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS) ? lock : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UserRequestException("单聊操作被中断，请稍后再试");
        }
    }

    private void handleUnlockFailure(RLock lock, long ownerThreadId, Throwable throwable) {
        if (throwable instanceof IllegalMonitorStateException) {
            log.debug("锁已不再由原线程持有, lock:{}, ownerThreadId:{}", lock.getName(), ownerThreadId);
            return;
        }
        log.warn("释放单聊锁失败, lock:{}, ownerThreadId:{}", lock.getName(), ownerThreadId, throwable);
    }

    private List<Long> distinctSortedCharacterIds(List<Long> characterIds) {
        if (characterIds == null) {
            return List.of();
        }
        return characterIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    private RLock singleChatLock(Long userWorldId, Long characterId) {
        return redissonClient.getLock(RedisConstant.SINGLE_CHAT_LOCK_PREFIX + userWorldId + ":" + characterId);
    }
}

package com.me.galchat.service.impl.group;

import com.me.galchat.constant.RedisConstant;
import com.me.galchat.exception.UserRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class GroupConversationLockService {

    private final RedissonClient redissonClient;

    public record OwnedLock(RLock lock, long ownerThreadId) {
    }

    public OwnedLock tryLock(Long conversationId) {
        if (conversationId == null) {
            throw new UserRequestException("群聊会话id不能为空");
        }
        return tryLock(RedisConstant.GROUP_CONVERSATION_LOCK_PREFIX + conversationId);
    }

    public OwnedLock tryWorldLock(Long userWorldId) {
        if (userWorldId == null) {
            throw new UserRequestException("用户世界id不能为空");
        }
        return tryLock(RedisConstant.GROUP_WORLD_MUTATION_LOCK_PREFIX + userWorldId);
    }

    private OwnedLock tryLock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        long ownerThreadId = Thread.currentThread().threadId();
        try {
            return lock.tryLock(0, TimeUnit.MILLISECONDS) ? new OwnedLock(lock, ownerThreadId) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UserRequestException("群聊操作被中断，请稍后再试");
        }
    }

    public void unlock(OwnedLock ownedLock) {
        if (ownedLock == null || ownedLock.lock() == null) {
            return;
        }
        try {
            ownedLock.lock().unlockAsync(ownedLock.ownerThreadId()).toCompletableFuture().join();
        } catch (IllegalMonitorStateException e) {
            log.debug("群聊锁已释放或不再属于原线程, lock:{}, ownerThreadId:{}",
                    ownedLock.lock().getName(), ownedLock.ownerThreadId());
        } catch (CompletionException e) {
            log.warn("释放群聊锁失败, lock:{}, ownerThreadId:{}",
                    ownedLock.lock().getName(), ownedLock.ownerThreadId(), e.getCause() == null ? e : e.getCause());
        }
    }
}

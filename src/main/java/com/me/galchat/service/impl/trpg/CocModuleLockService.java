package com.me.galchat.service.impl.trpg;

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
public class CocModuleLockService {

    private final RedissonClient redissonClient;

    public record OwnedLock(RLock lock, long ownerThreadId) {
    }

    public OwnedLock tryReadLock(Long moduleId) {
        requireModuleId(moduleId);
        return tryLock(redissonClient.getReadWriteLock(
                RedisConstant.COC_MODULE_LOCK_PREFIX + moduleId).readLock());
    }

    public OwnedLock tryWriteLock(Long moduleId) {
        requireModuleId(moduleId);
        return tryLock(redissonClient.getReadWriteLock(
                RedisConstant.COC_MODULE_LOCK_PREFIX + moduleId).writeLock());
    }

    private OwnedLock tryLock(RLock lock) {
        long ownerThreadId = Thread.currentThread().threadId();
        try {
            return lock.tryLock(0, TimeUnit.MILLISECONDS)
                    ? new OwnedLock(lock, ownerThreadId) : null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UserRequestException("模组操作被中断，请稍后再试");
        }
    }

    public void unlock(OwnedLock ownedLock) {
        if (ownedLock == null || ownedLock.lock() == null) {
            return;
        }
        try {
            ownedLock.lock().unlockAsync(ownedLock.ownerThreadId())
                    .toCompletableFuture().join();
        } catch (IllegalMonitorStateException exception) {
            log.debug("模组锁已释放或不再属于原线程, lock:{}, ownerThreadId:{}",
                    ownedLock.lock().getName(), ownedLock.ownerThreadId());
        } catch (CompletionException exception) {
            log.warn("释放模组锁失败, lock:{}, ownerThreadId:{}",
                    ownedLock.lock().getName(), ownedLock.ownerThreadId(),
                    exception.getCause() == null ? exception : exception.getCause());
        }
    }

    private void requireModuleId(Long moduleId) {
        if (moduleId == null) {
            throw new UserRequestException("模组id不能为空");
        }
    }
}

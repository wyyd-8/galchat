package com.me.galchat.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Redis deletion cannot be rolled back with the database transaction. */
@Slf4j
public final class RedisAfterCommitCleanup {
    private RedisAfterCommitCleanup() { }

    public static void run(String description, Runnable cleanup) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    cleanup.run();
                } catch (RuntimeException error) {
                    // The database has committed; do not turn successful finalization into a retry.
                    log.warn("数据库提交后清理 Redis 状态失败: {}", description, error);
                }
            }
        });
    }
}

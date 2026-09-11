package com.me.galchat.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class TrpgTurnVectorIndexQueue {

    private static final Logger logger = LoggerFactory.getLogger(
            TrpgTurnVectorIndexQueue.class);

    private final TaskExecutor executor;
    private final TrpgTurnVectorService vectorService;

    public TrpgTurnVectorIndexQueue(
            @Qualifier("trpgTurnVectorTaskExecutor") TaskExecutor executor,
            @Lazy TrpgTurnVectorService vectorService) {
        this.executor = executor;
        this.vectorService = vectorService;
    }

    public void submitTurnAfterCommit(Long turnId) {
        afterCommit(() -> submit(() -> vectorService.indexTurn(turnId),
                "跑团轮次向量索引失败, turnId:" + turnId));
    }

    private void afterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        task.run();
                    }
                });
    }

    private void submit(Runnable task, String failureMessage) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException exception) {
                    logger.warn(failureMessage, exception);
                }
            });
        } catch (RuntimeException exception) {
            logger.warn(failureMessage, exception);
        }
    }
}

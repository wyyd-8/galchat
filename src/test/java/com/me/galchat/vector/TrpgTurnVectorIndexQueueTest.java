package com.me.galchat.vector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class TrpgTurnVectorIndexQueueTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void submitsTurnIndexOnlyAfterTheTurnTransactionCommits() {
        TrpgTurnVectorService vectorService = mock(TrpgTurnVectorService.class);
        AtomicLong indexed = new AtomicLong();
        doAnswer(invocation -> {
            indexed.set(invocation.getArgument(0));
            return null;
        }).when(vectorService).indexTurn(81L);
        TrpgTurnVectorIndexQueue queue = new TrpgTurnVectorIndexQueue(
                Runnable::run, vectorService);
        TransactionSynchronizationManager.initSynchronization();

        queue.submitTurnAfterCommit(81L);

        assertThat(indexed.get()).isZero();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        assertThat(indexed.get()).isEqualTo(81L);
    }
}

package com.me.galchat.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ImportedWeaponAuditQueueTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void submitsAuditOnlyAfterTheImportTransactionCommits() {
        AtomicReference<Long> auditedId = new AtomicReference<>();
        ImportedWeaponAuditService auditService =
                mock(ImportedWeaponAuditService.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            auditedId.set(invocation.getArgument(0));
            return null;
        }).when(auditService).audit(71L);
        TaskExecutor directExecutor = Runnable::run;
        ImportedWeaponAuditQueue queue = new ImportedWeaponAuditQueue(
                directExecutor, auditService);
        TransactionSynchronizationManager.initSynchronization();

        queue.submitAfterCommit(71L);

        assertThat(auditedId.get()).isNull();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        assertThat(auditedId.get()).isEqualTo(71L);
    }

    @Test
    void auditFailureNeverBreaksTheCompletedImport() {
        ImportedWeaponAuditService auditService =
                mock(ImportedWeaponAuditService.class);
        doThrow(new IllegalStateException("model unavailable"))
                .when(auditService).audit(71L);
        ImportedWeaponAuditQueue queue = new ImportedWeaponAuditQueue(
                Runnable::run, auditService);

        assertThatCode(() -> queue.submitAfterCommit(71L))
                .doesNotThrowAnyException();
    }
}

package com.me.galchat.service.impl.character;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ImportedWeaponAuditQueue {

    private static final Logger logger = LoggerFactory.getLogger(
            ImportedWeaponAuditQueue.class);

    private final TaskExecutor taskExecutor;
    private final ImportedWeaponAuditService auditService;

    public ImportedWeaponAuditQueue(
            @Qualifier("weaponAuditTaskExecutor") TaskExecutor taskExecutor,
            @Lazy ImportedWeaponAuditService auditService) {
        this.taskExecutor = taskExecutor;
        this.auditService = auditService;
    }

    public void submitAfterCommit(Long characterId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit(characterId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        submit(characterId);
                    }
                });
    }

    private void submit(Long characterId) {
        try {
            taskExecutor.execute(() -> safeAudit(characterId));
        } catch (RuntimeException exception) {
            logger.warn("主动导入武器审核任务投递失败, characterId:{}",
                    characterId, exception);
        }
    }

    private void safeAudit(Long characterId) {
        try {
            auditService.audit(characterId);
        } catch (RuntimeException exception) {
            logger.warn("主动导入武器审核失败, characterId:{}",
                    characterId, exception);
        }
    }
}

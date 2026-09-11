package com.me.galchat.service.impl.group;

import com.me.galchat.domain.po.*;
import com.me.galchat.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroupConversationLifecycleServiceTest {
    final GroupConversationService conversations = mock(GroupConversationService.class);
    final GroupConversationLockService locks = mock(GroupConversationLockService.class);
    final GroupConversationMapper mapper = mock(GroupConversationMapper.class);
    final GroupReplyPlanService plans = mock(GroupReplyPlanService.class);
    final GroupContextSummaryMapper summaries = mock(GroupContextSummaryMapper.class);
    final GroupTurnRecoveryService recovery = mock(GroupTurnRecoveryService.class);
    final TransactionTemplate transactions = mock(TransactionTemplate.class);
    final GroupConversationLifecycleService service = new GroupConversationLifecycleService(
            conversations, locks, mapper, plans, summaries, recovery, transactions);
    final GroupConversation conversation = new GroupConversation().setId(7L).setMode("trpg").setStatus("active");

    @BeforeEach
    void setup() {
        when(conversations.requireActive(7L)).thenReturn(conversation);
        when(locks.tryLock(7L)).thenReturn(new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        when(transactions.execute(any())).thenAnswer(call -> call.<TransactionCallback<?>>getArgument(0)
                .doInTransaction(new SimpleTransactionStatus()));
    }

    @Test
    void manualEndSkipsGenerationAndPreservesSceneSummariesEvenWithPendingCompletion() {
        when(conversations.hasCompletion(7L)).thenReturn(true);
        service.close(7L);
        assertThat(conversation.getStatus()).isEqualTo("closed");
        assertThat(conversation.getClosedAt()).isNotNull();
        verify(conversations).discardCompletion(7L);
        verify(recovery).cancelFailedTurns(7L);
        verifyNoInteractions(summaries);
    }

    @Test
    void ordinaryChatStillClosesWithoutGeneration() {
        conversation.setMode("chat");
        service.close(7L);
        assertThat(conversation.getStatus()).isEqualTo("closed");
        verifyNoInteractions(summaries);
    }

    @Test
    void runningTurnPreventsManualEnd() {
        doThrow(new IllegalStateException("still running")).when(recovery).assertCanCloseConversation(7L);
        assertThatThrownBy(() -> service.close(7L)).hasMessage("still running");
        verifyNoInteractions(transactions, plans, mapper);
        verify(conversations, never()).discardCompletion(7L);
    }

    @Test
    void generatedCompletionPreservesSourceSummaries() {
        var materials = new com.me.galchat.domain.dto.TrpgCompletionModels.Materials("灯塔", null, 42, 3,
                List.of(new com.me.galchat.domain.dto.TrpgCompletionModels.Source(1, 42, "原摘要")), List.of(), List.of(), List.of());
        service.closeWithCompletionUnderLock(conversation, materials, "最终概要");
        assertThat(conversation.getSummary()).isEqualTo("最终概要");
        verify(summaries).insert(any(GroupContextSummary.class));
        verify(summaries, never()).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }
}

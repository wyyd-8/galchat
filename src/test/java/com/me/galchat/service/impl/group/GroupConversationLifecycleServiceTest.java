package com.me.galchat.service.impl.group;

import com.me.galchat.service.impl.trpg.TrpgSummaryIntervalSelector;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.mapper.GroupContextSummaryMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.exception.UserRequestException;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupConversationLifecycleServiceTest {

    @Test
    void trpgEndRewritesSummaryFromSceneSummariesAndClosesConversation() {
        GroupConversationService conversationService = mock(GroupConversationService.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupReplyPlanService replyPlanService = mock(GroupReplyPlanService.class);
        GroupContextSummaryMapper summaryMapper = mock(GroupContextSummaryMapper.class);
        GroupTurnRecoveryService recoveryService = mock(GroupTurnRecoveryService.class);
        DeepSeekChatModel summaryModel = mock(DeepSeekChatModel.class);
        when(summaryModel.getOptions()).thenReturn(DeepSeekChatOptions.builder().build());
        ChatClient summaryClient = ChatClient.builder(summaryModel).build();
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        GroupConversationLifecycleService service = new GroupConversationLifecycleService(conversationService,
                lockService, conversationMapper, replyPlanService, summaryMapper,
                recoveryService, summaryClient,
                transactionTemplate, new TrpgSummaryIntervalSelector());

        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setUserWorldId(1L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setTitle("地下医院")
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        when(conversationService.requireAuthorized(7L)).thenReturn(conversation);
        when(conversationService.requireActive(7L)).thenReturn(conversation);
        when(lockService.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        when(summaryMapper.selectList(any())).thenReturn(List.of(
                new GroupContextSummary()
                        .setId(31L)
                        .setConversationId(7L)
                        .setSceneId(21L)
                        .setScenePlanId(11L)
                        .setStartSequence(1L)
                        .setEndSequence(2L)
                        .setSummary("众人在医院病房中找到了钥匙。")
                        .setVersion(1)));
        when(summaryModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("众人在医院中发现了钥匙。")))));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        var summaryCaptor = org.mockito.ArgumentCaptor.forClass(GroupContextSummary.class);
        var promptCaptor = org.mockito.ArgumentCaptor.forClass(Prompt.class);

        GroupConversation result = service.close(7L);

        assertThat(result.getStatus()).isEqualTo(GroupChatConstant.STATUS_CLOSED);
        assertThat(result.getClosedAt()).isNotNull();
        assertThat(result.getSummary()).isEqualTo("众人在医院中发现了钥匙。");
        verify(summaryModel).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getInstructions().getLast().getText())
                .contains("众人在医院病房中找到了钥匙。")
                .doesNotContain("检查病房", "发现钥匙");
        verify(summaryMapper).insert(summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().getStartSequence()).isEqualTo(1L);
        assertThat(summaryCaptor.getValue().getEndSequence()).isEqualTo(2L);
        verify(replyPlanService).clearConversationPlans(conversation);
    }

    @Test
    void chatCloseDoesNotGenerateSummary() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        GroupReplyPlanService replyPlanService =
                mock(GroupReplyPlanService.class);
        GroupContextSummaryMapper summaryMapper =
                mock(GroupContextSummaryMapper.class);
        GroupTurnRecoveryService recoveryService =
                mock(GroupTurnRecoveryService.class);
        ChatClient summaryClient = mock(ChatClient.class);
        TransactionTemplate transactionTemplate =
                mock(TransactionTemplate.class);
        GroupConversation conversation = new GroupConversation()
                .setId(8L)
                .setMode(GroupChatConstant.MODE_CHAT)
                .setTitle("普通闲聊")
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        when(conversationService.requireActive(8L)).thenReturn(conversation);
        when(lockService.tryLock(8L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        GroupConversationLifecycleService service =
                new GroupConversationLifecycleService(
                        conversationService, lockService,
                        conversationMapper, replyPlanService,
                        summaryMapper,
                        recoveryService, summaryClient,
                        transactionTemplate,
                        new TrpgSummaryIntervalSelector());

        GroupConversation result = service.close(8L);

        assertThat(result.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_CLOSED);
        assertThat(result.getSummary()).isNull();
        verify(summaryClient, never()).prompt(any(Prompt.class));
        verify(summaryMapper, never()).selectList(any());
        verify(summaryMapper, never()).insert(
                any(GroupContextSummary.class));
    }

    @Test
    void closeRejectsNonTerminalTurnBeforeGeneratingSummary() {
        GroupConversationService conversationService = mock(GroupConversationService.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        GroupTurnRecoveryService recoveryService = mock(GroupTurnRecoveryService.class);
        ChatClient summaryClient = mock(ChatClient.class);
        GroupConversationLifecycleService service = new GroupConversationLifecycleService(
                conversationService,
                lockService,
                mock(GroupConversationMapper.class),
                mock(GroupReplyPlanService.class),
                mock(GroupContextSummaryMapper.class),
                recoveryService,
                summaryClient,
                mock(TransactionTemplate.class),
                new TrpgSummaryIntervalSelector());
        GroupConversation conversation =
                new GroupConversation().setId(7L).setStatus(GroupChatConstant.STATUS_ACTIVE);
        when(conversationService.requireAuthorized(7L)).thenReturn(conversation);
        when(conversationService.requireActive(7L)).thenReturn(conversation);
        when(lockService.tryLock(7L)).thenReturn(
                new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        doThrow(new UserRequestException("存在未完成的群聊轮次"))
                .when(recoveryService).assertConversationHasNoNonTerminalTurns(7L);

        assertThatThrownBy(() -> service.close(7L))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("未完成");
        verify(summaryClient, never()).prompt(any(Prompt.class));
    }

    @Test
    void turnCompletionCloseIgnoresOnlyTheTurnBeingCompleted() {
        GroupTurnRecoveryService recoveryService =
                mock(GroupTurnRecoveryService.class);
        ChatClient summaryClient = mock(ChatClient.class);
        GroupConversationLifecycleService service =
                new GroupConversationLifecycleService(
                        mock(GroupConversationService.class),
                        mock(GroupConversationLockService.class),
                        mock(GroupConversationMapper.class),
                        mock(GroupReplyPlanService.class),
                        mock(GroupContextSummaryMapper.class),
                        recoveryService,
                        summaryClient,
                        mock(TransactionTemplate.class),
                        new TrpgSummaryIntervalSelector());
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        doThrow(new UserRequestException("存在其他未完成轮次"))
                .when(recoveryService)
                .assertConversationHasNoNonTerminalTurnsExcept(7L, 9L);

        assertThatThrownBy(() -> service.closeAfterTurnUnderLock(
                conversation, 9L))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("其他未完成");
        verify(recoveryService, never())
                .assertConversationHasNoNonTerminalTurns(7L);
        verify(summaryClient, never()).prompt(any(Prompt.class));
    }

}

package com.me.galchat.service.impl;

import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.service.IDiceRollInternalService;
import com.me.galchat.utils.DiceUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiceRollServiceImplTest {

    @Test
    void summaryQueryAuthorizesThroughItsConversation() {
        IDiceRollInternalService internal = mock(IDiceRollInternalService.class);
        GroupConversationService conversationService = mock(GroupConversationService.class);
        DiceRollServiceImpl service = new DiceRollServiceImpl(internal, conversationService);
        when(internal.requireSummary(101L)).thenReturn(new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED));
        when(conversationService.requireAuthorized(7L)).thenReturn(activeConversation(7L));

        var summary = service.getSummary(101L);

        assertThat(summary.getConversationId()).isEqualTo(7L);
        verify(conversationService).requireAuthorized(7L);
    }

    @Test
    void resultQueryAuthorizesSummaryConversationAndReturnsOnlyDetails() {
        IDiceRollInternalService internal = mock(IDiceRollInternalService.class);
        GroupConversationService conversationService = mock(GroupConversationService.class);
        DiceRollServiceImpl service = new DiceRollServiceImpl(internal, conversationService);
        DiceRollSummary summary = new DiceRollSummary().setId(101L).setConversationId(7L);
        when(internal.requireSummary(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L)).thenReturn(List.of(
                new DiceRollResult().setId(201L).setSummaryId(101L).setRoundNo(1)));

        var results = service.listResults(101L);

        assertThat(results).singleElement()
                .satisfies(result -> assertThat(result.getId()).isEqualTo(201L));
        verify(conversationService).requireAuthorized(7L);
    }

    @Test
    void playerRollResolvesPlaceholderAndReturnsProgressEnvelope() {
        IDiceRollInternalService internal = mock(IDiceRollInternalService.class);
        GroupConversationService conversationService = mock(GroupConversationService.class);
        DiceRollServiceImpl service = new DiceRollServiceImpl(internal, conversationService);
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        DiceRollResult pending = new DiceRollResult()
                .setId(201L)
                .setSummaryId(101L)
                .setCharacterId(null)
                .setRoundNo(1)
                .setResultData(DiceUtils.prepare("1D6"));
        when(internal.requireResult(201L)).thenReturn(pending);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(conversationService.requireActive(7L)).thenReturn(activeConversation(7L));
        when(internal.listResultEntities(101L)).thenReturn(List.of(pending));

        var progress = service.roll(201L);

        assertThat(progress.rolledResult().getResultData().getFormula()).isEqualTo("1D6");
        assertThat(progress.rolledResult().getResultData().getResult()).isBetween(1, 6);
        assertThat(progress.summary().getStatus()).isEqualTo(DiceRollConstant.STATUS_COMPLETED);
        assertThat(progress.createdResults()).isEmpty();
        verify(internal).saveResult(pending);
        verify(internal).saveSummary(summary);
    }

    private GroupConversation activeConversation(Long id) {
        return new GroupConversation().setId(id).setStatus("active");
    }
}

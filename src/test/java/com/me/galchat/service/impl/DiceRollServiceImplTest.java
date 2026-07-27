package com.me.galchat.service.impl;

import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.DiceRollDetailVO;
import com.me.galchat.domain.vo.DiceRollProgressVO;
import com.me.galchat.domain.vo.DiceRollSummaryVO;
import com.me.galchat.service.ICocDiceOrchestrationService;
import com.me.galchat.service.IDiceRollInternalService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiceRollServiceImplTest {

    @Test
    void queryUsesSummaryConversationAuthorization() {
        IDiceRollInternalService internal = mock(IDiceRollInternalService.class);
        GroupConversationService conversationService = mock(GroupConversationService.class);
        ICocDiceOrchestrationService orchestration = mock(ICocDiceOrchestrationService.class);
        DiceRollServiceImpl service = new DiceRollServiceImpl(
                internal, conversationService, orchestration);
        when(internal.requireSummary(101L)).thenReturn(new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus("COMPLETED"));
        when(conversationService.requireAuthorized(7L)).thenReturn(activeConversation(7L));

        var summary = service.getSummary(101L);

        assertThat(summary.getConversationId()).isEqualTo(7L);
        verify(conversationService).requireAuthorized(7L);
    }

    @Test
    void resultQueryAuthorizesSummaryConversationAndReturnsOnlyDetails() {
        IDiceRollInternalService internal = mock(IDiceRollInternalService.class);
        GroupConversationService conversationService = mock(GroupConversationService.class);
        ICocDiceOrchestrationService orchestration = mock(ICocDiceOrchestrationService.class);
        DiceRollServiceImpl service = new DiceRollServiceImpl(
                internal, conversationService, orchestration);
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
    void playerRollDelegatesToSemanticOrchestration() {
        IDiceRollInternalService internal = mock(IDiceRollInternalService.class);
        GroupConversationService conversationService = mock(GroupConversationService.class);
        ICocDiceOrchestrationService orchestration = mock(ICocDiceOrchestrationService.class);
        DiceRollServiceImpl service = new DiceRollServiceImpl(
                internal, conversationService, orchestration);
        DiceRollProgressVO expected = new DiceRollProgressVO(
                new DiceRollSummaryVO().setId(101L),
                new DiceRollDetailVO().setId(201L),
                List.of());
        when(orchestration.rollPlayerResult(201L)).thenReturn(expected);

        var progress = service.roll(201L);

        assertThat(progress).isSameAs(expected);
        verify(orchestration).rollPlayerResult(201L);
    }

    private GroupConversation activeConversation(Long id) {
        return new GroupConversation().setId(id).setStatus("active");
    }
}

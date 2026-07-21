package com.me.galchat.service.impl;

import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.DiceRollResultCreateDTO;
import com.me.galchat.domain.dto.DiceRollSummaryCreateDTO;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.mapper.DiceRollResultMapper;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.utils.DiceUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiceRollServiceImplTest {

    @Test
    void summaryQueryAuthorizesThroughItsConversation() {
        DiceRollSummaryMapper summaryMapper = mock(DiceRollSummaryMapper.class);
        DiceRollResultMapper resultMapper = mock(DiceRollResultMapper.class);
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupConversationService conversationService = mock(GroupConversationService.class);
        DiceRollServiceImpl service = service(summaryMapper, resultMapper, conversationMapper, conversationService);
        when(summaryMapper.selectById(101L)).thenReturn(new DiceRollSummary()
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
    void createUsesPreparedPlayerResultAndAutomaticallyRollsCharacters() {
        DiceRollSummaryMapper summaryMapper = mock(DiceRollSummaryMapper.class);
        DiceRollResultMapper resultMapper = mock(DiceRollResultMapper.class);
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        DiceRollServiceImpl service = service(summaryMapper, resultMapper, conversationMapper,
                mock(GroupConversationService.class));
        when(conversationMapper.selectById(7L)).thenReturn(activeConversation(7L));
        when(summaryMapper.insert(any(DiceRollSummary.class))).thenAnswer(invocation -> {
            invocation.<DiceRollSummary>getArgument(0).setId(101L);
            return 1;
        });
        AtomicLong resultId = new AtomicLong(200L);
        when(resultMapper.insert(any(DiceRollResult.class))).thenAnswer(invocation -> {
            invocation.<DiceRollResult>getArgument(0).setId(resultId.incrementAndGet());
            return 1;
        });

        DiceRollSummaryCreateDTO summary = new DiceRollSummaryCreateDTO();
        summary.setReason("调查员们搜索房间");
        var created = service.createDiceRoll(7L, summary, List.of(
                result(null, 1, "1D100", "玩家侦查"),
                result(12L, 2, "1D100", "同伴侦查")
        ));

        assertThat(created.getId()).isEqualTo(101L);
        assertThat(created.getStatus()).isEqualTo(DiceRollConstant.STATUS_PENDING);
        ArgumentCaptor<DiceRollResult> captor = ArgumentCaptor.forClass(DiceRollResult.class);
        verify(resultMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<DiceRollResult> inserted = captor.getAllValues();
        assertThat(inserted.get(0).getCharacterId()).isNull();
        assertThat(inserted.get(0).getResultData().getResult()).isNull();
        assertThat(inserted.get(0).getResultData().getModules()).hasSize(1);
        assertThat(inserted.get(0).getResultData().getModules().getFirst().getDice()).hasSize(2);
        assertThat(inserted.get(1).getCharacterId()).isEqualTo(12L);
        assertThat(inserted.get(1).getResultData().getResult()).isNotNull();
    }

    @Test
    void playerRollResolvesPlaceholderAndCompletesCurrentRound() {
        DiceRollSummaryMapper summaryMapper = mock(DiceRollSummaryMapper.class);
        DiceRollResultMapper resultMapper = mock(DiceRollResultMapper.class);
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupConversationService conversationService = mock(GroupConversationService.class);
        DiceRollServiceImpl service = service(summaryMapper, resultMapper, conversationMapper, conversationService);
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
        when(resultMapper.selectById(201L)).thenReturn(pending);
        when(summaryMapper.selectByIdForUpdate(101L)).thenReturn(summary);
        when(conversationService.requireActive(7L)).thenReturn(activeConversation(7L));
        when(resultMapper.selectCount(any())).thenReturn(0L);

        var rolled = service.roll(201L);

        assertThat(rolled.getResultData().getFormula()).isEqualTo("1D6");
        assertThat(rolled.getResultData().getResult()).isBetween(1, 6);
        assertThat(summary.getStatus()).isEqualTo(DiceRollConstant.STATUS_COMPLETED);
        verify(resultMapper).updateById(pending);
        verify(summaryMapper).updateById(summary);
    }

    @Test
    void appendRoundKeepsSummaryAndCreatesNewPlayerPlaceholder() {
        DiceRollSummaryMapper summaryMapper = mock(DiceRollSummaryMapper.class);
        DiceRollResultMapper resultMapper = mock(DiceRollResultMapper.class);
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        DiceRollServiceImpl service = service(summaryMapper, resultMapper, conversationMapper,
                mock(GroupConversationService.class));
        when(conversationMapper.selectById(7L)).thenReturn(activeConversation(7L));
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        when(summaryMapper.selectByIdForUpdate(101L)).thenReturn(summary);

        var appended = service.appendDiceRollRound(7L, 101L, "理智检定失败",
                List.of(result(null, 1, "1D6", "结算理智损失")));

        assertThat(summary.getRoundCount()).isEqualTo(2);
        assertThat(summary.getStatus()).isEqualTo(DiceRollConstant.STATUS_PENDING);
        assertThat(summary.getTotalResult()).isEqualTo("理智检定失败");
        assertThat(appended).singleElement().satisfies(detail -> {
            assertThat(detail.getSummaryId()).isEqualTo(101L);
            assertThat(detail.getRoundNo()).isEqualTo(2);
            assertThat(detail.getResultData().getFormula()).isEqualTo("1D6");
            assertThat(detail.getResultData().getResult()).isNull();
        });
        verify(summaryMapper).updateById(summary);
    }

    private DiceRollServiceImpl service(DiceRollSummaryMapper summaryMapper,
                                        DiceRollResultMapper resultMapper,
                                        GroupConversationMapper conversationMapper,
                                        GroupConversationService conversationService) {
        return new DiceRollServiceImpl(summaryMapper, resultMapper, conversationMapper, conversationService);
    }

    private GroupConversation activeConversation(Long id) {
        return new GroupConversation().setId(id).setStatus(GroupChatConstant.STATUS_ACTIVE);
    }

    private DiceRollResultCreateDTO result(Long characterId, int displayOrder, String formula, String reason) {
        DiceRollResultCreateDTO result = new DiceRollResultCreateDTO();
        result.setCharacterId(characterId);
        result.setDisplayOrder(displayOrder);
        result.setFormula(formula);
        result.setReason(reason);
        return result;
    }
}

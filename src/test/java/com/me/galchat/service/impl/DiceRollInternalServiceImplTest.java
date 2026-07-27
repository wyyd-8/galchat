package com.me.galchat.service.impl;

import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.domain.dto.DiceRollResultCreateDTO;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.vo.DiceResolutionDataVO;
import com.me.galchat.mapper.DiceRollResultMapper;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiceRollInternalServiceImplTest {

    private DiceRollSummaryMapper summaryMapper;
    private DiceRollResultMapper resultMapper;
    private DiceRollInternalServiceImpl service;

    @BeforeEach
    void setUp() {
        summaryMapper = mock(DiceRollSummaryMapper.class);
        resultMapper = mock(DiceRollResultMapper.class);
        service = new DiceRollInternalServiceImpl(summaryMapper, resultMapper);
        when(summaryMapper.insert(any(DiceRollSummary.class))).thenAnswer(invocation -> {
            invocation.<DiceRollSummary>getArgument(0).setId(101L);
            return 1;
        });
        AtomicLong resultIds = new AtomicLong(200L);
        when(resultMapper.insert(any(DiceRollResult.class))).thenAnswer(invocation -> {
            invocation.<DiceRollResult>getArgument(0).setId(resultIds.incrementAndGet());
            return 1;
        });
    }

    @Test
    void constantPlayerFormulaIsResolvedImmediatelyAndDoesNotMakeSummaryPending() {
        var aggregate = service.createDiceRoll(
                7L, "SAN损失", List.of(draft(null, "0", resolution("SAN_LOSS"))));

        assertThat(aggregate.results()).singleElement().satisfies(result -> {
            assertThat(result.getResultData().getResult()).isZero();
            assertThat(result.getResultData().getModules()).isEmpty();
            assertThat(result.getResolvedAt()).isNull();
        });
        assertThat(aggregate.summary().getStatus()).isEqualTo(DiceRollConstant.STATUS_COMPLETED);
    }

    @Test
    void realPlayerDiceKeepsPreparedModulesAndPendingStatus() {
        var aggregate = service.createDiceRoll(
                7L, "侦查", List.of(draft(null, "1D100", resolution("CHECK"))));

        assertThat(aggregate.results().getFirst().getResultData().getModules()).isNotEmpty();
        assertThat(aggregate.results().getFirst().getResultData().getResult()).isNull();
        assertThat(aggregate.results().getFirst().getResolutionData().getType()).isEqualTo("CHECK");
        assertThat(aggregate.summary().getStatus()).isEqualTo(DiceRollConstant.STATUS_PENDING);
    }

    @Test
    void appendingConstantPlayerFormulaAdvancesRoundWithoutChangingTotalResult() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED)
                .setTotalResult("理智检定失败");
        when(summaryMapper.selectByIdForUpdate(101L)).thenReturn(summary);

        var results = service.appendDiceRollRound(
                7L, 101L, List.of(draft(null, "0", resolution("SAN_LOSS"))));

        assertThat(summary.getRoundCount()).isEqualTo(2);
        assertThat(summary.getStatus()).isEqualTo(DiceRollConstant.STATUS_COMPLETED);
        assertThat(summary.getTotalResult()).isEqualTo("理智检定失败");
        assertThat(results).singleElement()
                .satisfies(result -> assertThat(result.getRoundNo()).isEqualTo(2));
    }

    private DiceRollResultCreateDTO draft(
            Long characterId, String formula, DiceResolutionDataVO resolution) {
        DiceRollResultCreateDTO draft = new DiceRollResultCreateDTO();
        draft.setCharacterId(characterId);
        draft.setReason("林恩");
        draft.setFormula(formula);
        draft.setResolutionData(resolution);
        return draft;
    }

    private DiceResolutionDataVO resolution(String type) {
        return DiceResolutionDataVO.pending(type, null, Map.of("characterName", "林恩"));
    }
}

package com.me.galchat.service.impl;

import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.vo.DiceRollDetailVO;
import com.me.galchat.domain.vo.DiceRollProgressVO;
import com.me.galchat.domain.vo.DiceRollSummaryVO;
import com.me.galchat.service.ICocDiceOrchestrationService;
import com.me.galchat.service.IDiceRollInternalService;
import com.me.galchat.service.IDiceRollService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DiceRollServiceImpl implements IDiceRollService {

    private final IDiceRollInternalService internalService;
    private final GroupConversationService conversationService;
    private final ICocDiceOrchestrationService orchestrationService;

    @Override
    public DiceRollSummaryVO getSummary(Long id) {
        DiceRollSummary summary = internalService.requireSummary(id);
        conversationService.requireAuthorized(summary.getConversationId());
        return DiceRollSummaryVO.from(summary);
    }

    @Override
    public List<DiceRollDetailVO> listResults(Long summaryId) {
        DiceRollSummary summary = internalService.requireSummary(summaryId);
        conversationService.requireAuthorized(summary.getConversationId());
        return internalService.listResultEntities(summaryId).stream()
                .map(DiceRollDetailVO::from)
                .toList();
    }

    @Override
    public DiceRollProgressVO roll(Long resultId) {
        return orchestrationService.rollPlayerResult(resultId);
    }
}

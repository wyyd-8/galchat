package com.me.galchat.service.impl;

import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.vo.DiceRollDetailVO;
import com.me.galchat.domain.vo.DiceRollProgressVO;
import com.me.galchat.domain.vo.DiceRollResultVO;
import com.me.galchat.domain.vo.DiceRollSummaryVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.IDiceRollInternalService;
import com.me.galchat.service.IDiceRollService;
import com.me.galchat.utils.DiceUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DiceRollServiceImpl implements IDiceRollService {

    private final IDiceRollInternalService internalService;
    private final GroupConversationService conversationService;

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

    /**
     * Task 6 replaces this temporary physical-roll path with semantic orchestration.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public DiceRollProgressVO roll(Long resultId) {
        DiceRollResult initial = internalService.requireResult(resultId);
        DiceRollSummary summary = internalService.requireSummaryForUpdate(initial.getSummaryId());
        conversationService.requireActive(summary.getConversationId());

        DiceRollResult result = internalService.requireResult(resultId);
        if (!summary.getId().equals(result.getSummaryId())) {
            throw new UserRequestException("掷骰结果不存在");
        }
        if (result.getCharacterId() != null) {
            throw new UserRequestException("该结果不是玩家掷骰位置");
        }
        if (!DiceRollConstant.STATUS_PENDING.equals(summary.getStatus())) {
            throw new UserRequestException("当前轮掷骰已经完成");
        }
        if (!summary.getRoundCount().equals(result.getRoundNo())) {
            throw new UserRequestException("该结果不属于当前掷骰轮次");
        }
        DiceRollResultVO prepared = result.getResultData();
        if (prepared == null || !StringUtils.hasText(prepared.getFormula())) {
            throw new UserRequestException("掷骰公式不存在");
        }
        if (prepared.getResult() != null || prepared.getModules() == null
                || prepared.getModules().isEmpty()) {
            throw new UserRequestException("该位置已经完成掷骰");
        }

        LocalDateTime now = LocalDateTime.now();
        result.setResultData(DiceUtils.roll(prepared.getFormula())).setUpdatedAt(now);
        internalService.saveResult(result);

        boolean pending = internalService.listResultEntities(summary.getId()).stream()
                .filter(candidate -> summary.getRoundCount().equals(candidate.getRoundNo()))
                .anyMatch(this::isPendingUserDice);
        if (!pending) {
            summary.setStatus(DiceRollConstant.STATUS_COMPLETED).setUpdatedAt(now);
            internalService.saveSummary(summary);
        }
        return new DiceRollProgressVO(
                DiceRollSummaryVO.from(summary),
                DiceRollDetailVO.from(result),
                List.of());
    }

    private boolean isPendingUserDice(DiceRollResult result) {
        DiceRollResultVO data = result.getResultData();
        return result.getCharacterId() == null
                && data != null
                && data.getResult() == null
                && data.getModules() != null
                && !data.getModules().isEmpty();
    }
}

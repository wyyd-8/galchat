package com.me.galchat.service;

import com.me.galchat.domain.dto.DiceRollResultCreateDTO;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.vo.DiceRollAggregate;

import java.util.List;

public interface IDiceRollInternalService {
    DiceRollAggregate createDiceRoll(
            Long conversationId, String reason, List<DiceRollResultCreateDTO> results);
    DiceRollSummary requireSummary(Long summaryId);
    DiceRollSummary requireSummaryForUpdate(Long summaryId);
    DiceRollResult requireResult(Long resultId);
    List<DiceRollResult> listResultEntities(Long summaryId);
    List<DiceRollResult> appendDiceRollRound(
            Long conversationId, Long summaryId, List<DiceRollResultCreateDTO> results);
    void saveResult(DiceRollResult result);
    void saveSummary(DiceRollSummary summary);
}

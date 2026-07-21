package com.me.galchat.service;

import com.me.galchat.domain.dto.DiceRollResultCreateDTO;
import com.me.galchat.domain.dto.DiceRollSummaryCreateDTO;
import com.me.galchat.domain.dto.DiceRollSummaryUpdateDTO;
import com.me.galchat.domain.vo.DiceRollDetailVO;
import com.me.galchat.domain.vo.DiceRollSummaryVO;

import java.util.List;

public interface IDiceRollInternalService {
    DiceRollSummaryVO createDiceRoll(Long conversationId,
                                     DiceRollSummaryCreateDTO summary,
                                     List<DiceRollResultCreateDTO> results);

    DiceRollSummaryVO updateDiceRollSummary(Long conversationId,
                                            Long summaryId,
                                            DiceRollSummaryUpdateDTO update);

    List<DiceRollDetailVO> appendDiceRollRound(Long conversationId,
                                               Long summaryId,
                                               String totalResult,
                                               List<DiceRollResultCreateDTO> results);
}

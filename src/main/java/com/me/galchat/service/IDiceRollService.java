package com.me.galchat.service;

import com.me.galchat.domain.vo.DiceRollDetailVO;
import com.me.galchat.domain.vo.DiceRollProgressVO;
import com.me.galchat.domain.vo.DiceRollSummaryVO;

import java.util.List;

public interface IDiceRollService {
    DiceRollSummaryVO getSummary(Long id);
    List<DiceRollDetailVO> listResults(Long summaryId);
    DiceRollProgressVO roll(Long resultId);
}

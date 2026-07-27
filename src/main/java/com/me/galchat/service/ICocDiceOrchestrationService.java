package com.me.galchat.service;

import com.me.galchat.domain.dto.KpDiceRequestDTOs;
import com.me.galchat.domain.vo.DiceRollProgressVO;
import com.me.galchat.domain.vo.KpDiceToolResult;

public interface ICocDiceOrchestrationService {
    KpDiceToolResult requestCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.Check request);

    KpDiceToolResult requestOpposedCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.Opposed request);

    KpDiceToolResult requestPushedCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.Pushed request);

    KpDiceToolResult requestSanCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.SanCheck request);

    KpDiceToolResult rollSanLoss(
            Long conversationId, Long runId, KpDiceRequestDTOs.SanLoss request);

    DiceRollProgressVO rollPlayerResult(Long resultId);
}

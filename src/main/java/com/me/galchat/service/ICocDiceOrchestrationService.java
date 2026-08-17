package com.me.galchat.service;

import com.me.galchat.domain.dto.KpDiceRequestDTOs;
import com.me.galchat.domain.dto.KpFirearmRequestDTOs;
import com.me.galchat.domain.vo.DiceRollProgressVO;
import com.me.galchat.domain.vo.KpDiceToolResult;

public interface ICocDiceOrchestrationService {
    KpDiceToolResult requestCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.Check request);

    KpDiceToolResult requestGroupCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.GroupCheck request);

    KpDiceToolResult requestFirearmAttack(
            Long conversationId, Long runId,
            KpFirearmRequestDTOs.Attack request);

    KpDiceToolResult requestOpposedCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.Opposed request);

    KpDiceToolResult requestPushedCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.Pushed request);

    KpDiceToolResult requestSanCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.SanCheck request);

    KpDiceToolResult rollSanLoss(
            Long conversationId, Long runId, KpDiceRequestDTOs.SanLoss request);

    KpDiceToolResult rollDamage(
            Long conversationId, Long runId, KpDiceRequestDTOs.Damage request);

    KpDiceToolResult rollHealing(
            Long conversationId, Long runId, KpDiceRequestDTOs.Healing request);

    KpDiceToolResult requestUnconsciousRecovery(
            Long conversationId, Long runId, Long cardId);

    DiceRollProgressVO rollPlayerResult(Long resultId);
}

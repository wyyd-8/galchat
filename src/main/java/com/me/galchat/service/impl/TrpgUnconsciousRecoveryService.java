package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.GroupChatEvent;
import com.me.galchat.domain.vo.KpDiceToolResult;
import com.me.galchat.groupchat.dice.DiceRollMessageCodec;
import com.me.galchat.groupchat.tool.GroupToolCallStore;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.service.ICocDiceOrchestrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class TrpgUnconsciousRecoveryService {

    private final CocCharacterMapper characterMapper;
    private final ICocDiceOrchestrationService diceService;
    private final GroupChatReplyStepMapper stepMapper;
    private final GroupChatTurnMapper turnMapper;
    private final GroupChatMessageMapper messageMapper;
    private final DiceRollSummaryMapper summaryMapper;
    private final GroupConversationService conversationService;
    private final DiceRollMessageCodec diceMessageCodec;
    private final GroupToolCallStore toolCallStore;
    private final GroupTurnCheckpointService checkpointService;
    private final TrpgCombatLifecycleService combatLifecycleService;

    @Transactional(rollbackFor = Exception.class)
    public Execution handle(
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep step) {
        if (conversation == null || turn == null || step == null
                || !GroupChatConstant.PLAN_SOURCE_COMBAT.equals(
                turn.getPlanSource())) {
            return Execution.notApplicable();
        }
        if (GroupChatConstant.ACTION_COMBAT_UNCONSCIOUS_RECOVERY.equals(
                step.getActionType())) {
            return completeCreatedRecovery(turn, step);
        }
        if (!GroupChatConstant.ACTION_COMBAT_ATTACK.equals(
                step.getActionType())
                || step.getSubjectCharacterId() == null) {
            return Execution.notApplicable();
        }
        CocCharacter card = characterMapper.selectById(
                step.getSubjectCharacterId());
        if (card == null) {
            return Execution.notApplicable();
        }
        if (Boolean.TRUE.equals(card.getDead())
                || Boolean.TRUE.equals(card.getDying())) {
            combatLifecycleService.forfeitCurrentRoundSlot(
                    conversation.getId(), card.getId());
            return new Execution(Outcome.SKIPPED, List.of());
        }
        if (!Boolean.TRUE.equals(card.getUnconscious())) {
            return Execution.notApplicable();
        }
        KpDiceToolResult roll = diceService.requestUnconsciousRecovery(
                conversation.getId(), card.getRunId(), card.getId());
        String boundaryStatus = DiceRollConstant.STATUS_PENDING.equals(
                roll.summary().getStatus())
                ? GroupChatConstant.STATUS_WAITING_DICE
                : GroupChatConstant.STATUS_PAUSED;
        List<Integer> roundNos = roll.results() == null
                ? List.of() : roll.results().stream()
                .map(detail -> detail.getRoundNo())
                .filter(Objects::nonNull)
                .distinct().sorted().toList();
        if (roundNos.isEmpty()) {
            int count = Objects.requireNonNullElse(
                    roll.summary().getRoundCount(), 1);
            roundNos = IntStream.rangeClosed(1, Math.max(1, count))
                    .boxed().toList();
        }
        LocalDateTime now = LocalDateTime.now();
        GroupChatMessage message = new GroupChatMessage()
                .setConversationId(conversation.getId())
                .setTurnId(turn.getId())
                .setReplyStepId(step.getId())
                .setSpeakerType(step.getSpeakerType())
                .setSpeakerId(step.getSpeakerId())
                .setMessageKind(GroupChatConstant.MESSAGE_DICE_ROLL)
                .setVisibility("public")
                .setContent(diceMessageCodec.encode(
                        roll.summary().getId(), roundNos))
                .setSequenceNo(conversationService.nextSequence(
                        conversation.getId()))
                .setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setCreatedAt(now).setUpdatedAt(now);
        messageMapper.insert(message);
        step.setActionType(
                        GroupChatConstant.ACTION_COMBAT_UNCONSCIOUS_RECOVERY)
                .setGroupName("昏迷恢复CON检定")
                .setOutputMessageId(message.getId())
                .setStatus(boundaryStatus)
                .setUpdatedAt(now);
        stepMapper.updateById(step);
        cancelSlotTail(step, now);
        turn.setStatus(boundaryStatus).setUpdatedAt(now);
        turnMapper.updateById(turn);
        toolCallStore.saveSystemDice(step.getId(), roll);
        checkpointService.recordBoundary(
                turn, step,
                GroupChatConstant.STATUS_WAITING_DICE.equals(boundaryStatus)
                        ? GroupTurnCheckpointService.WAITING_DICE
                        : GroupTurnCheckpointService.PAUSED);
        GroupChatEvent event = GroupChatEvent.builder()
                .eventType(GroupChatConstant.EVENT_DICE_ROLL_CREATED)
                .conversationId(conversation.getId())
                .turnId(turn.getId())
                .replyStepId(step.getId())
                .actionType(step.getActionType())
                .messageId(message.getId())
                .sequence(message.getSequenceNo())
                .messageKind(GroupChatConstant.MESSAGE_DICE_ROLL)
                .diceRoll(roll)
                .build();
        return new Execution(Outcome.PAUSED, List.of(event));
    }

    private Execution completeCreatedRecovery(
            GroupChatTurn turn, GroupChatReplyStep step) {
        GroupChatMessage message = latestDiceMessage(step.getId());
        if (message == null) {
            throw new IllegalStateException("昏迷恢复步骤缺少骰点消息");
        }
        Long summaryId = diceMessageCodec.decode(
                message.getContent()).summaryId();
        DiceRollSummary summary = summaryMapper.selectById(summaryId);
        if (summary == null || DiceRollConstant.STATUS_PENDING.equals(
                summary.getStatus())) {
            return new Execution(Outcome.PAUSED, List.of());
        }
        step.setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setUpdatedAt(LocalDateTime.now());
        stepMapper.updateById(step);
        checkpointService.recordBoundary(
                turn, step, GroupTurnCheckpointService.COMPLETED);
        return new Execution(Outcome.COMPLETED, List.of());
    }

    private GroupChatMessage latestDiceMessage(Long replyStepId) {
        List<GroupChatMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<GroupChatMessage>()
                        .eq(GroupChatMessage::getReplyStepId, replyStepId)
                        .eq(GroupChatMessage::getMessageKind,
                                GroupChatConstant.MESSAGE_DICE_ROLL)
                        .orderByDesc(GroupChatMessage::getId)
                        .last("limit 1"));
        return messages == null || messages.isEmpty()
                ? null : messages.getFirst();
    }

    private void cancelSlotTail(
            GroupChatReplyStep recovery, LocalDateTime now) {
        if (recovery.getItemOrder() == null) {
            return;
        }
        List<GroupChatReplyStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId,
                                recovery.getTurnId())
                        .orderByAsc(GroupChatReplyStep::getItemOrder));
        int first = recovery.getItemOrder();
        if (steps == null) {
            return;
        }
        steps.stream()
                .filter(step -> !Objects.equals(
                        step.getId(), recovery.getId()))
                .filter(step -> step.getItemOrder() != null
                        && step.getItemOrder() > first
                        && step.getItemOrder() < first + 4)
                .filter(step -> GroupChatConstant.STATUS_PENDING.equals(
                        step.getStatus()))
                .forEach(step -> {
                    step.setStatus(GroupChatConstant.STATUS_CANCELLED)
                            .setUpdatedAt(now);
                    stepMapper.updateById(step);
                });
    }

    public enum Outcome {
        NOT_APPLICABLE,
        SKIPPED,
        PAUSED,
        COMPLETED
    }

    public record Execution(Outcome outcome, List<GroupChatEvent> events) {
        private static Execution notApplicable() {
            return new Execution(Outcome.NOT_APPLICABLE, List.of());
        }
    }
}

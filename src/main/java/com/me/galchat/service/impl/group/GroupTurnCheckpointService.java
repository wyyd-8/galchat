package com.me.galchat.service.impl.group;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupTurnCheckpoint;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.GroupCheckpointUnavailableException;
import com.me.galchat.domain.dto.KpCharacterAttributeDTOs;
import com.me.galchat.domain.dto.KpEquipmentDTOs;
import com.me.galchat.domain.dto.KpInvestigatorSuspensionDTOs;
import com.me.galchat.service.impl.trpg.TrpgInvestigatorSuspensionService;
import com.me.galchat.service.impl.trpg.TrpgEquipmentService;
import com.me.galchat.service.impl.trpg.TrpgMaterialRecoveryService;
import com.me.galchat.domain.vo.KpDiceToolResult;
import com.me.galchat.exception.TurnCheckpointUnavailableException;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupTurnCheckpointMapper;
import com.me.galchat.service.ICharacterCardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class GroupTurnCheckpointService {

    public static final String STEP_START = "STEP_START";
    public static final String TOOL_COMMITTED = "TOOL_COMMITTED";
    public static final String WAITING_DICE = "WAITING_DICE";
    public static final String PAUSED = "PAUSED";
    public static final String COMPLETED = "COMPLETED";

    private final GroupTurnCheckpointMapper checkpointMapper;
    private final GroupChatMessageMapper messageMapper;
    private final GroupChatToolCallMapper toolCallMapper;
    private final GroupChatReplyStepMapper stepMapper;
    private final GroupChatTurnMapper turnMapper;
    private final DiceRollSummaryMapper diceRollSummaryMapper;
    private final com.me.galchat.groupchat.dice.DiceRollMessageCodec
            diceMessageCodec;
    private final ICharacterCardService characterCardService;
    private final ObjectMapper objectMapper;
    private final GroupChatFavorRollbackService chatFavorRollbackService;
    private final TrpgEquipmentService equipmentService;
    private final TrpgMaterialRecoveryService materialRecoveryService;
    private final TrpgInvestigatorSuspensionService suspensionService;
    private com.me.galchat.mapper.TrpgCompletionMapper completionMapper;

    @org.springframework.beans.factory.annotation.Autowired
    void setCompletionMapper(com.me.galchat.mapper.TrpgCompletionMapper completionMapper) {
        this.completionMapper = completionMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void initializeStep(
            GroupChatTurn turn, GroupChatReplyStep step) {
        requireBoundaryContext(turn, step);
        GroupTurnCheckpoint current = checkpointMapper.selectById(
                turn.getConversationId());
        if (current != null
                && Objects.equals(current.getTurnId(), turn.getId())
                && Objects.equals(current.getReplyStepId(),
                step.getId())) {
            return;
        }
        upsert(turn, step, STEP_START,
                zero(messageMapper.selectMaxIdByReplyStepId(step.getId())),
                zero(toolCallMapper.selectMaxIdByReplyStepId(step.getId())));
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordBoundary(
            GroupChatTurn turn,
            GroupChatReplyStep step,
            String checkpointType) {
        requireBoundaryContext(turn, step);
        if (!WAITING_DICE.equals(checkpointType)
                && !PAUSED.equals(checkpointType)
                && !COMPLETED.equals(checkpointType)) {
            throw new IllegalArgumentException(
                    "不支持的行动轮检查点类型");
        }
        Long toolCallId = toolCallMapper.selectMaxIdByReplyStepId(
                step.getId());
        Long messageId = messageMapper.selectMaxIdByReplyStepId(
                step.getId());
        upsert(turn, step, checkpointType,
                zero(messageId), zero(toolCallId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void clear(Long conversationId) {
        if (conversationId != null) {
            checkpointMapper.deleteById(conversationId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordToolCommitted(
            Long replyStepId, Long toolCallId) {
        GroupChatReplyStep step = replyStepId == null
                ? null : stepMapper.selectById(replyStepId);
        GroupChatTurn turn = step == null
                ? null : turnMapper.selectById(step.getTurnId());
        if (turn == null || turn.getConversationId() == null
                || step.getOutputMessageId() == null
                || toolCallId == null) {
            throw new IllegalStateException(
                    "无法为已提交工具建立行动轮检查点");
        }
        Long messageId = messageMapper.selectMaxIdByReplyStepId(
                replyStepId);
        upsert(turn, step, TOOL_COMMITTED,
                zero(messageId), toolCallId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void restore(
            GroupChatTurn turn, GroupChatReplyStep step) {
        requireBoundaryContext(turn, step);
        GroupTurnCheckpoint checkpoint = checkpointMapper.selectById(
                turn.getConversationId());
        if (checkpoint == null
                || !Objects.equals(turn.getId(), checkpoint.getTurnId())
                || !Objects.equals(step.getId(),
                checkpoint.getReplyStepId())) {
            throw new TurnCheckpointUnavailableException();
        }
        String checkpointType = checkpoint.getCheckpointType();
        boolean diceBoundary = TOOL_COMMITTED.equals(checkpointType)
                || WAITING_DICE.equals(checkpointType);
        if (!diceBoundary
                && !PAUSED.equals(checkpointType)
                && !STEP_START.equals(checkpointType)) {
            throw new TurnCheckpointUnavailableException();
        }
        GroupChatToolCall diceCall = diceBoundary
                ? latestDiceCall(step.getId(), checkpoint.getToolCallId())
                : null;
        if (diceBoundary && diceCall == null) {
            throw new IllegalStateException(
                    "骰点检查点缺少已提交的工具调用");
        }
        rollbackToolEffects(
                turn.getConversationId(),
                toolCallMapper.selectList(
                        new LambdaQueryWrapper<GroupChatToolCall>()
                                .eq(GroupChatToolCall::getReplyStepId,
                                        step.getId())
                                .gt(GroupChatToolCall::getId,
                                        zero(checkpoint.getToolCallId()))
                                .in(GroupChatToolCall::getToolName,
                                        "adjustBasicAttributes", "purchaseEquipment", "suspendInvestigators")
                                .isNotNull(GroupChatToolCall::getToolResult)
                                .orderByDesc(GroupChatToolCall::getId)));
        materialRecoveryService.restoreAfterCheckpoint(turn.getConversationId(),
                step.getId(), zero(checkpoint.getMessageId()));
        messageMapper.deleteAfterCheckpoint(
                step.getId(), zero(checkpoint.getMessageId()));
        toolCallMapper.deleteAfterCheckpoint(
                step.getId(), zero(checkpoint.getToolCallId()));
        reconcileFinishRequest(turn);
        if (TOOL_COMMITTED.equals(checkpointType)) {
            restoreDiceMessage(step, diceCall);
        }
        boolean waitingDice = diceBoundary
                && isPendingDice(diceCall.getDiceRollSummaryId());
        LocalDateTime now = LocalDateTime.now();
        step.setOutputMessageId(null)
                .setStatus(waitingDice
                        ? GroupChatConstant.STATUS_WAITING_DICE
                        : GroupChatConstant.STATUS_PENDING)
                .setErrorMessage(null)
                .setUpdatedAt(now);
        persistResetStep(step);
        turn.setStatus(waitingDice
                        ? GroupChatConstant.STATUS_WAITING_DICE
                        : GroupChatConstant.STATUS_RUNNING)
                .setUpdatedAt(now);
        turnMapper.updateById(turn);
    }

    /** Restores the unfinished suffix after the last fully completed chat reply. */
    @Transactional(rollbackFor = Exception.class)
    public List<GroupChatReplyStep> restoreChat(GroupConversation conversation, GroupChatTurn turn) {
        GroupTurnCheckpoint checkpoint = checkpointMapper.selectById(conversation.getId());
        List<GroupChatReplyStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turn.getId())
                        .orderByAsc(GroupChatReplyStep::getStepNo));
        if (!GroupChatConstant.MODE_CHAT.equals(conversation.getMode())
                || !Objects.equals(conversation.getId(), turn.getConversationId())
                || checkpoint == null || !Objects.equals(checkpoint.getTurnId(), turn.getId())
                || steps == null || steps.isEmpty()) {
            throw new GroupCheckpointUnavailableException();
        }
        int boundary = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (Objects.equals(steps.get(i).getId(), checkpoint.getReplyStepId())) {
                boundary = i;
                break;
            }
        }
        int next;
        if (STEP_START.equals(checkpoint.getCheckpointType()) && boundary == 0) {
            next = 0;
        } else if (COMPLETED.equals(checkpoint.getCheckpointType()) && boundary >= 0) {
            next = boundary + 1;
        } else {
            throw new GroupCheckpointUnavailableException();
        }
        for (int i = 0; i < steps.size(); i++) {
            GroupChatReplyStep step = steps.get(i);
            if (step.getParentStepId() != null
                    || !Objects.equals(step.getTurnId(), turn.getId())
                    || (i < next) != GroupChatConstant.STATUS_COMPLETED.equals(step.getStatus())) {
                throw new GroupCheckpointUnavailableException();
            }
        }
        List<GroupChatReplyStep> remaining = steps.subList(next, steps.size());
        if (!remaining.isEmpty()) {
            List<Long> stepIds = remaining.stream().map(GroupChatReplyStep::getId).toList();
            chatFavorRollbackService.rollback(conversation.getUserWorldId(), stepIds);
            messageMapper.delete(new LambdaQueryWrapper<GroupChatMessage>()
                    .in(GroupChatMessage::getReplyStepId, stepIds));
            toolCallMapper.delete(new LambdaQueryWrapper<GroupChatToolCall>()
                    .in(GroupChatToolCall::getReplyStepId, stepIds));
            for (GroupChatReplyStep step : remaining) {
                // A new attempt uses the actor's current model selection.
                step.setStatus(GroupChatConstant.STATUS_PENDING).setOutputMessageId(null)
                        .setExecutionMode(null).setModelApiId(null)
                        .setErrorMessage(null).setUpdatedAt(LocalDateTime.now());
                stepMapper.update(null, new LambdaUpdateWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getId, step.getId())
                        .set(GroupChatReplyStep::getStatus, step.getStatus())
                        .set(GroupChatReplyStep::getOutputMessageId, null)
                        .set(GroupChatReplyStep::getExecutionMode, null)
                        .set(GroupChatReplyStep::getModelApiId, null)
                        .set(GroupChatReplyStep::getErrorMessage, null)
                        .set(GroupChatReplyStep::getUpdatedAt, step.getUpdatedAt()));
            }
        }
        turn.setStatus(GroupChatConstant.STATUS_RUNNING).setUpdatedAt(LocalDateTime.now());
        turnMapper.updateById(turn);
        return List.copyOf(remaining);
    }

    private void reconcileFinishRequest(GroupChatTurn turn) {
        if (completionMapper == null) return;
        var completion = completionMapper.selectById(turn.getConversationId());
        if (completion == null || !Objects.equals(completion.getTurnId(), turn.getId())) return;
        var stepIds = stepMapper.selectList(new LambdaQueryWrapper<GroupChatReplyStep>()
                .eq(GroupChatReplyStep::getTurnId, turn.getId())).stream().map(GroupChatReplyStep::getId).toList();
        Long count = stepIds.isEmpty() ? 0L : toolCallMapper.selectCount(new LambdaQueryWrapper<GroupChatToolCall>()
                .in(GroupChatToolCall::getReplyStepId, stepIds).eq(GroupChatToolCall::getToolName, "finishRun")
                .isNotNull(GroupChatToolCall::getToolResult));
        if (count == null || count == 0) completionMapper.deleteById(turn.getConversationId());
    }

    private void persistResetStep(GroupChatReplyStep step) {
        stepMapper.update(null,
                new LambdaUpdateWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getId, step.getId())
                        .set(GroupChatReplyStep::getStatus,
                                step.getStatus())
                        .set(GroupChatReplyStep::getOutputMessageId,
                                null)
                        .set(GroupChatReplyStep::getErrorMessage, null)
                        .set(GroupChatReplyStep::getUpdatedAt,
                                step.getUpdatedAt()));
    }

    private void rollbackToolEffects(
            Long runId, List<GroupChatToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return;
        }
        for (GroupChatToolCall call : calls) {
            String toolResult = call.getToolResult();
            if ("suspendInvestigators".equals(call.getToolName()) && toolResult != null) {
                String trimmed = toolResult.stripLeading();
                if (trimmed.startsWith("已悬置调查员") || trimmed.startsWith("\"已悬置调查员")) {
                    throw new IllegalStateException("悬置记录缺少撤销数据，无法回滚");
                }
                if (trimmed.startsWith("{")) {
                    try {
                        suspensionService.rollbackSuspension(runId,
                                objectMapper.readValue(toolResult, KpInvestigatorSuspensionDTOs.SuspendResult.class));
                    } catch (JacksonException exception) {
                        throw new IllegalStateException("悬置记录无法解析，已中止重试", exception);
                    }
                }
                continue;
            }
            // Successful tool effects are JSON objects; tool failures are text.
            if (toolResult == null
                    || !toolResult.stripLeading().startsWith("{")) {
                continue;
            }
            if ("purchaseEquipment".equals(call.getToolName())) {
                try {
                    equipmentService.rollbackPurchase(runId,
                            objectMapper.readValue(toolResult, KpEquipmentDTOs.PurchaseResult.class));
                } catch (JacksonException exception) {
                    throw new IllegalStateException("购买记录无法解析，已中止重试", exception);
                }
                continue;
            }
            KpCharacterAttributeDTOs.Result result;
            try {
                result = objectMapper.readValue(
                        toolResult,
                        KpCharacterAttributeDTOs.Result.class);
            } catch (JacksonException exception) {
                throw new IllegalStateException(
                        "基础属性调整记录无法解析，已中止重试", exception);
            }
            characterCardService.rollbackBasicAttributeAdjustment(
                    runId, result);
        }
    }

    private GroupChatToolCall latestDiceCall(
            Long replyStepId, Long maxToolCallId) {
        List<GroupChatToolCall> calls = toolCallMapper.selectList(
                new LambdaQueryWrapper<GroupChatToolCall>()
                        .eq(GroupChatToolCall::getReplyStepId,
                                replyStepId)
                        .le(maxToolCallId != null,
                                GroupChatToolCall::getId,
                                maxToolCallId)
                        .isNotNull(
                                GroupChatToolCall::getDiceRollSummaryId)
                        .isNotNull(GroupChatToolCall::getToolResult)
                        .orderByDesc(GroupChatToolCall::getId)
                        .last("limit 1"));
        return calls == null || calls.isEmpty()
                ? null : calls.getFirst();
    }

    private boolean isPendingDice(Long summaryId) {
        DiceRollSummary summary = summaryId == null
                ? null : diceRollSummaryMapper.selectById(summaryId);
        if (summary == null) {
            throw new IllegalStateException("骰点检查点引用的概要不存在");
        }
        return DiceRollConstant.STATUS_PENDING.equals(
                summary.getStatus());
    }

    private void restoreDiceMessage(
            GroupChatReplyStep step,
            GroupChatToolCall diceCall) {
        if (step.getOutputMessageId() == null) {
            throw new IllegalStateException(
                    "骰点检查点缺少输出消息");
        }
        GroupChatMessage message = messageMapper.selectById(
                step.getOutputMessageId());
        if (message == null
                || !Objects.equals(message.getReplyStepId(),
                step.getId())) {
            throw new IllegalStateException(
                    "骰点检查点引用的消息不存在");
        }
        KpDiceToolResult result;
        try {
            result = objectMapper.readValue(
                    diceCall.getToolResult(), KpDiceToolResult.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "骰点检查点工具结果无法解析", exception);
        }
        List<Integer> roundNos = result.results() == null
                ? List.of()
                : result.results().stream()
                        .map(detail -> detail.getRoundNo())
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList();
        message.setMessageKind(GroupChatConstant.MESSAGE_DICE_ROLL)
                .setContent(diceMessageCodec.encode(
                        diceCall.getDiceRollSummaryId(), roundNos))
                .setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setUpdatedAt(LocalDateTime.now());
        messageMapper.updateById(message);
    }

    private long zero(Long value) {
        return value == null ? 0L : value;
    }

    private void upsert(
            GroupChatTurn turn,
            GroupChatReplyStep step,
            String checkpointType,
            Long messageId,
            Long toolCallId) {
        checkpointMapper.upsert(new GroupTurnCheckpoint()
                .setConversationId(turn.getConversationId())
                .setTurnId(turn.getId())
                .setReplyStepId(step.getId())
                .setCheckpointType(checkpointType)
                .setMessageId(messageId)
                .setToolCallId(toolCallId)
                .setUpdatedAt(LocalDateTime.now()));
    }

    private void requireBoundaryContext(
            GroupChatTurn turn, GroupChatReplyStep step) {
        if (turn == null || step == null
                || turn.getId() == null
                || turn.getConversationId() == null
                || step.getId() == null
                || !Objects.equals(step.getTurnId(), turn.getId())) {
            throw new IllegalStateException(
                    "行动轮检查点上下文不完整");
        }
    }
}

package com.me.galchat.service.impl;

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
import com.me.galchat.domain.dto.KpCharacterAttributeDTOs;
import com.me.galchat.domain.vo.KpDiceToolResult;
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

    /**
     * @return {@code true} when the matching checkpoint was restored;
     *         {@code false} when the whole turn was reset as a fallback
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean restore(
            GroupChatTurn turn, GroupChatReplyStep step) {
        requireBoundaryContext(turn, step);
        GroupTurnCheckpoint checkpoint = checkpointMapper.selectById(
                turn.getConversationId());
        if (checkpoint == null
                || !Objects.equals(turn.getId(), checkpoint.getTurnId())
                || !Objects.equals(step.getId(),
                checkpoint.getReplyStepId())) {
            restartWholeTurn(turn);
            return false;
        }
        String checkpointType = checkpoint.getCheckpointType();
        boolean diceBoundary = TOOL_COMMITTED.equals(checkpointType)
                || WAITING_DICE.equals(checkpointType);
        if (!diceBoundary
                && !PAUSED.equals(checkpointType)
                && !STEP_START.equals(checkpointType)) {
            restartWholeTurn(turn);
            return false;
        }
        GroupChatToolCall diceCall = diceBoundary
                ? latestDiceCall(step.getId(), checkpoint.getToolCallId())
                : null;
        if (diceBoundary && diceCall == null) {
            throw new IllegalStateException(
                    "骰点检查点缺少已提交的工具调用");
        }
        rollbackAttributeAdjustments(
                turn.getConversationId(),
                toolCallMapper.selectList(
                        new LambdaQueryWrapper<GroupChatToolCall>()
                                .eq(GroupChatToolCall::getReplyStepId,
                                        step.getId())
                                .gt(GroupChatToolCall::getId,
                                        zero(checkpoint.getToolCallId()))
                                .eq(GroupChatToolCall::getToolName,
                                        "adjustBasicAttributes")
                                .isNotNull(GroupChatToolCall::getToolResult)
                                .orderByDesc(GroupChatToolCall::getId)));
        messageMapper.deleteAfterCheckpoint(
                step.getId(), zero(checkpoint.getMessageId()));
        toolCallMapper.deleteAfterCheckpoint(
                step.getId(), zero(checkpoint.getToolCallId()));
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
        return true;
    }

    private void restartWholeTurn(GroupChatTurn turn) {
        List<GroupChatReplyStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turn.getId())
                        .orderByAsc(GroupChatReplyStep::getStepNo));
        List<Long> stepIds = steps == null ? List.of()
                : steps.stream()
                        .map(GroupChatReplyStep::getId)
                        .filter(Objects::nonNull)
                        .toList();
        if (!stepIds.isEmpty()) {
            rollbackAttributeAdjustments(
                    turn.getConversationId(),
                    toolCallMapper.selectList(
                            new LambdaQueryWrapper<GroupChatToolCall>()
                                    .in(GroupChatToolCall::getReplyStepId,
                                            stepIds)
                                    .eq(GroupChatToolCall::getToolName,
                                            "adjustBasicAttributes")
                                    .isNotNull(
                                            GroupChatToolCall::getToolResult)
                                    .orderByDesc(GroupChatToolCall::getId)));
            toolCallMapper.delete(
                    new LambdaQueryWrapper<GroupChatToolCall>()
                            .in(GroupChatToolCall::getReplyStepId,
                                    stepIds));
        }
        messageMapper.delete(
                new LambdaQueryWrapper<GroupChatMessage>()
                        .eq(GroupChatMessage::getTurnId, turn.getId()));
        LocalDateTime now = LocalDateTime.now();
        if (steps != null) {
            for (GroupChatReplyStep current : steps) {
                current.setStatus(current.getParentStepId() == null
                                ? GroupChatConstant.STATUS_PENDING
                                : GroupChatConstant.STATUS_CANCELLED)
                        .setOutputMessageId(null)
                        .setErrorMessage(null)
                        .setUpdatedAt(now);
                persistResetStep(current);
            }
        }
        turn.setStatus(GroupChatConstant.STATUS_RUNNING)
                .setUpdatedAt(now);
        turnMapper.updateById(turn);
        checkpointMapper.deleteById(turn.getConversationId());
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

    private void rollbackAttributeAdjustments(
            Long runId, List<GroupChatToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return;
        }
        for (GroupChatToolCall call : calls) {
            String toolResult = call.getToolResult();
            // Successful adjustments are JSON objects; tool failures are text.
            if (toolResult == null
                    || !toolResult.stripLeading().startsWith("{")) {
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

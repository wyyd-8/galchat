package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.DiceRollResultCreateDTO;
import com.me.galchat.domain.dto.DiceRollSummaryCreateDTO;
import com.me.galchat.domain.dto.DiceRollSummaryUpdateDTO;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.DiceRollDetailVO;
import com.me.galchat.domain.vo.DiceRollResultVO;
import com.me.galchat.domain.vo.DiceRollSummaryVO;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.DiceRollResultMapper;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.service.IDiceRollInternalService;
import com.me.galchat.service.IDiceRollService;
import com.me.galchat.utils.DiceUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DiceRollServiceImpl implements IDiceRollService, IDiceRollInternalService {

    private final DiceRollSummaryMapper summaryMapper;
    private final DiceRollResultMapper resultMapper;
    private final GroupConversationMapper conversationMapper;
    private final GroupConversationService conversationService;

    @Override
    public DiceRollSummaryVO getSummary(Long id) {
        return DiceRollSummaryVO.from(requireAuthorizedSummary(id, false));
    }

    @Override
    public List<DiceRollDetailVO> listResults(Long summaryId) {
        requireAuthorizedSummary(summaryId, false);
        return listResultEntities(summaryId).stream().map(DiceRollDetailVO::from).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DiceRollDetailVO roll(Long resultId) {
        requireId(resultId, "掷骰结果id不能为空");
        DiceRollResult initial = resultMapper.selectById(resultId);
        if (initial == null) {
            throw new UserRequestException("掷骰结果不存在");
        }

        DiceRollSummary summary = requireSummaryForUpdate(initial.getSummaryId());
        conversationService.requireActive(summary.getConversationId());

        // Re-read after locking the summary so a repeated request cannot use stale result data.
        DiceRollResult result = resultMapper.selectById(resultId);
        if (result == null || !summary.getId().equals(result.getSummaryId())) {
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
        if (prepared.getResult() != null) {
            throw new UserRequestException("该位置已经完成掷骰");
        }

        LocalDateTime now = LocalDateTime.now();
        result.setResultData(DiceUtils.roll(prepared.getFormula())).setUpdatedAt(now);
        resultMapper.updateById(result);

        if (!hasPendingPlayerResult(summary.getId(), summary.getRoundCount())) {
            summary.setStatus(DiceRollConstant.STATUS_COMPLETED).setUpdatedAt(now);
            summaryMapper.updateById(summary);
        }
        return DiceRollDetailVO.from(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DiceRollSummaryVO createDiceRoll(Long conversationId,
                                            DiceRollSummaryCreateDTO create,
                                            List<DiceRollResultCreateDTO> results) {
        requireActiveConversation(conversationId);
        if (create == null || !StringUtils.hasText(create.getReason())) {
            throw new UserRequestException("掷骰原因不能为空");
        }
        validateResults(results);

        LocalDateTime now = LocalDateTime.now();
        DiceRollSummary summary = new DiceRollSummary()
                .setConversationId(conversationId)
                .setReason(create.getReason().trim())
                .setRoundCount(1)
                .setStatus(hasPlayerResult(results)
                        ? DiceRollConstant.STATUS_PENDING
                        : DiceRollConstant.STATUS_COMPLETED)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        summaryMapper.insert(summary);
        createResults(summary.getId(), 1, results, now);
        return DiceRollSummaryVO.from(summary);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DiceRollSummaryVO updateDiceRollSummary(Long conversationId,
                                                   Long summaryId,
                                                   DiceRollSummaryUpdateDTO update) {
        requireActiveConversation(conversationId);
        DiceRollSummary summary = requireScopedSummary(conversationId, summaryId, true);
        if (update == null) {
            throw new UserRequestException("掷骰概要更新内容不能为空");
        }
        if (update.getReason() == null && update.getTotalResult() == null) {
            throw new UserRequestException("掷骰概要更新内容不能为空");
        }
        if (update.getReason() != null) {
            if (!StringUtils.hasText(update.getReason())) {
                throw new UserRequestException("掷骰原因不能为空");
            }
            summary.setReason(update.getReason().trim());
        }
        if (update.getTotalResult() != null) {
            if (!StringUtils.hasText(update.getTotalResult())) {
                throw new UserRequestException("掷骰累计结果不能为空");
            }
            summary.setTotalResult(update.getTotalResult().trim());
        }
        summary.setUpdatedAt(LocalDateTime.now());
        summaryMapper.updateById(summary);
        return DiceRollSummaryVO.from(summary);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<DiceRollDetailVO> appendDiceRollRound(Long conversationId,
                                                      Long summaryId,
                                                      String totalResult,
                                                      List<DiceRollResultCreateDTO> results) {
        requireActiveConversation(conversationId);
        validateResults(results);
        if (!StringUtils.hasText(totalResult)) {
            throw new UserRequestException("上一轮累计结果不能为空");
        }

        DiceRollSummary summary = requireScopedSummary(conversationId, summaryId, true);
        if (!DiceRollConstant.STATUS_COMPLETED.equals(summary.getStatus())) {
            throw new UserRequestException("当前轮玩家尚未完成掷骰");
        }

        int nextRound = summary.getRoundCount() + 1;
        LocalDateTime now = LocalDateTime.now();
        summary.setTotalResult(totalResult.trim())
                .setRoundCount(nextRound)
                .setStatus(hasPlayerResult(results)
                        ? DiceRollConstant.STATUS_PENDING
                        : DiceRollConstant.STATUS_COMPLETED)
                .setUpdatedAt(now);
        summaryMapper.updateById(summary);
        return createResults(summary.getId(), nextRound, results, now).stream()
                .map(DiceRollDetailVO::from)
                .toList();
    }

    private DiceRollSummary requireAuthorizedSummary(Long summaryId, boolean active) {
        DiceRollSummary summary = requireSummary(summaryId);
        if (active) {
            conversationService.requireActive(summary.getConversationId());
        } else {
            conversationService.requireAuthorized(summary.getConversationId());
        }
        return summary;
    }

    private DiceRollSummary requireScopedSummary(Long conversationId, Long summaryId, boolean lock) {
        requireId(summaryId, "掷骰概要id不能为空");
        DiceRollSummary summary = lock
                ? summaryMapper.selectByIdForUpdate(summaryId)
                : summaryMapper.selectById(summaryId);
        if (summary == null) {
            throw new UserRequestException("掷骰概要不存在");
        }
        if (!conversationId.equals(summary.getConversationId())) {
            throw new UserAuthException("掷骰概要不属于当前群聊");
        }
        return summary;
    }

    private DiceRollSummary requireSummary(Long summaryId) {
        requireId(summaryId, "掷骰概要id不能为空");
        DiceRollSummary summary = summaryMapper.selectById(summaryId);
        if (summary == null) {
            throw new UserRequestException("掷骰概要不存在");
        }
        return summary;
    }

    private DiceRollSummary requireSummaryForUpdate(Long summaryId) {
        DiceRollSummary summary = summaryMapper.selectByIdForUpdate(summaryId);
        if (summary == null) {
            throw new UserRequestException("掷骰概要不存在");
        }
        return summary;
    }

    private void requireActiveConversation(Long conversationId) {
        requireId(conversationId, "群聊会话id不能为空");
        GroupConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new UserRequestException("群聊会话不存在");
        }
        if (!GroupChatConstant.STATUS_ACTIVE.equals(conversation.getStatus())) {
            throw new UserRequestException("群聊会话已结束");
        }
    }

    private List<DiceRollResult> createResults(Long summaryId,
                                               int roundNo,
                                               List<DiceRollResultCreateDTO> creates,
                                               LocalDateTime now) {
        List<DiceRollResult> results = new java.util.ArrayList<>(creates.size());
        for (int i = 0; i < creates.size(); i++) {
            DiceRollResultCreateDTO create = creates.get(i);
            DiceRollResultVO resultData = create.getCharacterId() == null
                    ? DiceUtils.prepare(create.getFormula())
                    : DiceUtils.roll(create.getFormula());
            DiceRollResult result = new DiceRollResult()
                    .setSummaryId(summaryId)
                    .setCharacterId(create.getCharacterId())
                    .setRoundNo(roundNo)
                    .setDisplayOrder(create.getDisplayOrder() == null ? i + 1 : create.getDisplayOrder())
                    .setDisplayType(StringUtils.hasText(create.getDisplayType())
                            ? create.getDisplayType().trim()
                            : null)
                    .setReason(create.getReason().trim())
                    .setResultData(resultData)
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            resultMapper.insert(result);
            results.add(result);
        }
        return results;
    }

    private List<DiceRollResult> listResultEntities(Long summaryId) {
        return resultMapper.selectList(new LambdaQueryWrapper<DiceRollResult>()
                .eq(DiceRollResult::getSummaryId, summaryId)
                .orderByAsc(DiceRollResult::getRoundNo)
                .orderByAsc(DiceRollResult::getDisplayOrder)
                .orderByAsc(DiceRollResult::getId));
    }

    private boolean hasPendingPlayerResult(Long summaryId, Integer roundNo) {
        return resultMapper.selectCount(new LambdaQueryWrapper<DiceRollResult>()
                .eq(DiceRollResult::getSummaryId, summaryId)
                .eq(DiceRollResult::getRoundNo, roundNo)
                .isNull(DiceRollResult::getCharacterId)
                .apply("(result_data ->> 'result') IS NULL")) > 0;
    }

    private boolean hasPlayerResult(List<DiceRollResultCreateDTO> results) {
        return results.stream().anyMatch(result -> result.getCharacterId() == null);
    }

    private void validateResults(List<DiceRollResultCreateDTO> results) {
        if (results == null || results.isEmpty()) {
            throw new UserRequestException("掷骰结果不能为空");
        }
        Set<Integer> displayOrders = new HashSet<>();
        for (int i = 0; i < results.size(); i++) {
            DiceRollResultCreateDTO result = results.get(i);
            if (result == null) {
                throw new UserRequestException("掷骰结果不能为空");
            }
            if (!StringUtils.hasText(result.getReason())) {
                throw new UserRequestException("掷骰结果原因不能为空");
            }
            if (!StringUtils.hasText(result.getFormula())) {
                throw new UserRequestException("骰子公式不能为空");
            }
            int displayOrder = result.getDisplayOrder() == null ? i + 1 : result.getDisplayOrder();
            if (displayOrder < 1 || !displayOrders.add(displayOrder)) {
                throw new UserRequestException("同一轮掷骰展示顺序必须为不重复的正整数");
            }
        }
    }

    private void requireId(Long id, String message) {
        if (id == null) {
            throw new UserRequestException(message);
        }
    }
}

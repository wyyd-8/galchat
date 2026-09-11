package com.me.galchat.service.impl.dice;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.domain.dto.DiceRollResultCreateDTO;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.vo.DiceRollAggregate;
import com.me.galchat.domain.vo.DiceRollResultVO;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.DiceRollResultMapper;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import com.me.galchat.service.IDiceRollInternalService;
import com.me.galchat.utils.DiceUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DiceRollInternalServiceImpl implements IDiceRollInternalService {

    private final DiceRollSummaryMapper summaryMapper;
    private final DiceRollResultMapper resultMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DiceRollAggregate createDiceRoll(
            Long conversationId, String reason, List<DiceRollResultCreateDTO> results) {
        requireId(conversationId, "群聊会话id不能为空");
        if (!StringUtils.hasText(reason)) {
            throw new UserRequestException("掷骰原因不能为空");
        }
        List<PreparedDraft> prepared = prepareDrafts(results);
        LocalDateTime now = LocalDateTime.now();
        DiceRollSummary summary = new DiceRollSummary()
                .setConversationId(conversationId)
                .setReason(reason.trim())
                .setRoundCount(1)
                .setStatus(hasPendingUserDice(prepared)
                        ? DiceRollConstant.STATUS_PENDING
                        : DiceRollConstant.STATUS_COMPLETED)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        summaryMapper.insert(summary);
        List<DiceRollResult> created = insertResults(summary.getId(), 1, prepared, now);
        return new DiceRollAggregate(summary, List.copyOf(created));
    }

    @Override
    public DiceRollSummary requireSummary(Long summaryId) {
        requireId(summaryId, "掷骰概要id不能为空");
        DiceRollSummary summary = summaryMapper.selectById(summaryId);
        if (summary == null) {
            throw new UserRequestException("掷骰概要不存在");
        }
        return summary;
    }

    @Override
    public DiceRollSummary requireSummaryForUpdate(Long summaryId) {
        requireId(summaryId, "掷骰概要id不能为空");
        DiceRollSummary summary = summaryMapper.selectByIdForUpdate(summaryId);
        if (summary == null) {
            throw new UserRequestException("掷骰概要不存在");
        }
        return summary;
    }

    @Override
    public DiceRollResult requireResult(Long resultId) {
        requireId(resultId, "掷骰结果id不能为空");
        DiceRollResult result = resultMapper.selectById(resultId);
        if (result == null) {
            throw new UserRequestException("掷骰结果不存在");
        }
        return result;
    }

    @Override
    public List<DiceRollResult> listResultEntities(Long summaryId) {
        requireId(summaryId, "掷骰概要id不能为空");
        return resultMapper.selectList(new LambdaQueryWrapper<DiceRollResult>()
                .eq(DiceRollResult::getSummaryId, summaryId)
                .orderByAsc(DiceRollResult::getRoundNo)
                .orderByAsc(DiceRollResult::getDisplayOrder)
                .orderByAsc(DiceRollResult::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<DiceRollResult> appendDiceRollRound(
            Long conversationId, Long summaryId, List<DiceRollResultCreateDTO> results) {
        requireId(conversationId, "群聊会话id不能为空");
        List<PreparedDraft> prepared = prepareDrafts(results);
        DiceRollSummary summary = requireSummaryForUpdate(summaryId);
        if (!conversationId.equals(summary.getConversationId())) {
            throw new UserAuthException("掷骰概要不属于当前群聊");
        }
        if (!DiceRollConstant.STATUS_COMPLETED.equals(summary.getStatus())) {
            throw new UserRequestException("当前轮玩家尚未完成掷骰");
        }

        int nextRound = summary.getRoundCount() + 1;
        LocalDateTime now = LocalDateTime.now();
        summary.setRoundCount(nextRound)
                .setStatus(hasPendingUserDice(prepared)
                        ? DiceRollConstant.STATUS_PENDING
                        : DiceRollConstant.STATUS_COMPLETED)
                .setUpdatedAt(now);
        summaryMapper.updateById(summary);
        return List.copyOf(insertResults(summary.getId(), nextRound, prepared, now));
    }

    @Override
    public void saveResult(DiceRollResult result) {
        if (result == null || result.getId() == null) {
            throw new UserRequestException("掷骰结果不能为空");
        }
        if (resultMapper.updateById(result) == 0) {
            throw new UserRequestException("掷骰结果不存在");
        }
    }

    @Override
    public void saveSummary(DiceRollSummary summary) {
        if (summary == null || summary.getId() == null) {
            throw new UserRequestException("掷骰概要不能为空");
        }
        if (summaryMapper.updateById(summary) == 0) {
            throw new UserRequestException("掷骰概要不存在");
        }
    }

    private List<PreparedDraft> prepareDrafts(List<DiceRollResultCreateDTO> results) {
        validateResults(results);
        List<PreparedDraft> prepared = new ArrayList<>(results.size());
        for (DiceRollResultCreateDTO draft : results) {
            DiceRollResultVO parsed = DiceUtils.prepare(draft.getFormula());
            boolean pendingUserDice = draft.getCharacterId() == null
                    && !parsed.getModules().isEmpty();
            DiceRollResultVO resultData = pendingUserDice
                    ? parsed
                    : DiceUtils.roll(draft.getFormula());
            prepared.add(new PreparedDraft(draft, resultData, pendingUserDice));
        }
        return prepared;
    }

    private List<DiceRollResult> insertResults(
            Long summaryId, int roundNo, List<PreparedDraft> drafts, LocalDateTime now) {
        List<DiceRollResult> results = new ArrayList<>(drafts.size());
        for (int index = 0; index < drafts.size(); index++) {
            PreparedDraft prepared = drafts.get(index);
            DiceRollResultCreateDTO draft = prepared.draft();
            DiceRollResult result = new DiceRollResult()
                    .setSummaryId(summaryId)
                    .setCharacterId(draft.getCharacterId())
                    .setRoundNo(roundNo)
                    .setDisplayOrder(draft.getDisplayOrder() == null
                            ? index + 1
                            : draft.getDisplayOrder())
                    .setDisplayType(StringUtils.hasText(draft.getDisplayType())
                            ? draft.getDisplayType().trim()
                            : null)
                    .setReason(draft.getReason().trim())
                    .setResultData(prepared.resultData())
                    .setResolutionData(draft.getResolutionData())
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            resultMapper.insert(result);
            results.add(result);
        }
        return results;
    }

    private boolean hasPendingUserDice(List<PreparedDraft> drafts) {
        return drafts.stream().anyMatch(PreparedDraft::pendingUserDice);
    }

    private void validateResults(List<DiceRollResultCreateDTO> results) {
        if (results == null || results.isEmpty()) {
            throw new UserRequestException("掷骰结果不能为空");
        }
        Set<Integer> displayOrders = new HashSet<>();
        for (int index = 0; index < results.size(); index++) {
            DiceRollResultCreateDTO result = results.get(index);
            if (result == null) {
                throw new UserRequestException("掷骰结果不能为空");
            }
            if (!StringUtils.hasText(result.getReason())) {
                throw new UserRequestException("掷骰结果原因不能为空");
            }
            if (!StringUtils.hasText(result.getFormula())) {
                throw new UserRequestException("骰子公式不能为空");
            }
            if (result.getResolutionData() == null) {
                throw new UserRequestException("掷骰结算数据不能为空");
            }
            int displayOrder = result.getDisplayOrder() == null ? index + 1 : result.getDisplayOrder();
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

    private record PreparedDraft(
            DiceRollResultCreateDTO draft,
            DiceRollResultVO resultData,
            boolean pendingUserDice) {
    }
}

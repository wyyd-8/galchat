package com.me.galchat.groupchat.dice;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.vo.DiceRollResultVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.DiceRollResultMapper;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class GroupDiceMessageFormatter {

    private final DiceRollMessageCodec codec;
    private final DiceRollSummaryMapper summaryMapper;
    private final DiceRollResultMapper resultMapper;

    public String format(String messageContent) {
        DiceRollMessageContent reference = codec.decode(messageContent);
        DiceRollSummary summary = summaryMapper.selectById(reference.summaryId());
        if (summary == null) {
            throw new UserRequestException("找不到掷骰概要");
        }
        List<DiceRollResult> results = resultMapper.selectList(
                        new LambdaQueryWrapper<DiceRollResult>()
                                .eq(DiceRollResult::getSummaryId, reference.summaryId())
                                .in(DiceRollResult::getRoundNo, reference.roundNos())
                                .orderByAsc(DiceRollResult::getRoundNo)
                                .orderByAsc(DiceRollResult::getDisplayOrder)
                                .orderByAsc(DiceRollResult::getId))
                .stream()
                .filter(result -> Objects.equals(
                        reference.summaryId(), result.getSummaryId()))
                .filter(result -> reference.roundNos().contains(result.getRoundNo()))
                .toList();
        Set<Integer> foundRounds = results.stream()
                .map(DiceRollResult::getRoundNo)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!foundRounds.containsAll(reference.roundNos())) {
            throw new UserRequestException("掷骰消息引用了不存在的轮次");
        }

        String rounds = reference.roundNos().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        StringBuilder content = new StringBuilder()
                .append("<dice-roll summary-id=\"").append(reference.summaryId())
                .append("\" rounds=\"").append(rounds).append("\">");
        if (StringUtils.hasText(summary.getReason())) {
            content.append("\n原因：").append(summary.getReason());
        }
        Integer currentRound = null;
        for (DiceRollResult result : results) {
            if (!Objects.equals(currentRound, result.getRoundNo())) {
                currentRound = result.getRoundNo();
                content.append("\n第").append(currentRound).append("轮：");
            }
            content.append("\n- ").append(result.getReason()).append("：");
            DiceRollResultVO data = result.getResultData();
            if (data != null) {
                content.append(data.getFormula()).append(" = ")
                        .append(data.getResult() == null ? "待掷骰" : data.getResult());
            }
        }
        return content.append("\n</dice-roll>").toString();
    }
}

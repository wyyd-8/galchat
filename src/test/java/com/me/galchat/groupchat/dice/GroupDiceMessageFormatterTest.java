package com.me.galchat.groupchat.dice;

import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.vo.DiceResolutionDataVO;
import com.me.galchat.domain.vo.DiceRollResultVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.DiceRollResultMapper;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import com.me.galchat.service.impl.CocDiceSummaryFormatter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroupDiceMessageFormatterTest {

    @Test
    void formatsOnlyDeclaredRoundsAsSemanticResultsWithoutRawDicePoints() {
        DiceRollSummaryMapper summaryMapper = mock(DiceRollSummaryMapper.class);
        DiceRollResultMapper resultMapper = mock(DiceRollResultMapper.class);
        GroupDiceMessageFormatter formatter = formatter(summaryMapper, resultMapper);
        when(summaryMapper.selectById(501L)).thenReturn(new DiceRollSummary()
                .setId(501L)
                .setReason("侦查书房"));
        when(resultMapper.selectList(any())).thenReturn(List.of(
                result(1, "聆听", 44),
                result(2, "侦查", 32),
                result(3, "幸运", 21)));

        String formatted = formatter.format(
                "{\"summaryId\":501,\"roundNos\":[2,3]}");

        assertThat(formatted)
                .contains("<dice-roll summary-id=\"501\" rounds=\"2,3\">")
                .contains(
                        "第2轮",
                        "第3轮",
                        "康特进行“侦查”检定：成功",
                        "康特进行“幸运”检定：成功")
                .doesNotContain(
                        "第1轮",
                        "聆听",
                        "常规成功",
                        "困难成功",
                        "极难成功",
                        "1D100",
                        " = 32",
                        " = 21",
                        " = 44",
                        "目标值");
    }

    @Test
    void kpContextCollapsesInternalCheckRanksIntoFourOutcomeLevels() {
        DiceRollSummaryMapper summaryMapper = mock(DiceRollSummaryMapper.class);
        DiceRollResultMapper resultMapper = mock(DiceRollResultMapper.class);
        GroupDiceMessageFormatter formatter = formatter(summaryMapper, resultMapper);
        when(summaryMapper.selectById(501L)).thenReturn(new DiceRollSummary()
                .setId(501L)
                .setReason("测试成功等级"));
        when(resultMapper.selectList(any())).thenReturn(List.of(
                result(1, "大失败检定", 100, "FUMBLE", "FUMBLE"),
                result(2, "失败检定", 80, "FAILURE", "FAILURE"),
                result(3, "常规检定", 40, "SUCCESS", "REGULAR"),
                result(4, "困难检定", 20, "SUCCESS", "HARD"),
                result(5, "极难检定", 5, "SUCCESS", "EXTREME"),
                result(6, "大成功检定", 1, "CRITICAL_SUCCESS", "CRITICAL")));

        String formatted = formatter.format(
                "{\"summaryId\":501,\"roundNos\":[1,2,3,4,5,6]}");

        assertThat(formatted)
                .contains(
                        "大失败检定”检定：大失败",
                        "失败检定”检定：失败",
                        "常规检定”检定：成功",
                        "困难检定”检定：成功",
                        "极难检定”检定：成功",
                        "大成功检定”检定：大成功")
                .doesNotContain("常规成功", "困难成功", "极难成功");
    }

    @Test
    void pendingRoundTellsTheAgentToWaitWithoutExposingTheDiceFormula() {
        DiceRollSummaryMapper summaryMapper = mock(DiceRollSummaryMapper.class);
        DiceRollResultMapper resultMapper = mock(DiceRollResultMapper.class);
        GroupDiceMessageFormatter formatter = formatter(summaryMapper, resultMapper);
        when(summaryMapper.selectById(501L)).thenReturn(new DiceRollSummary()
                .setId(501L)
                .setReason("调查书房"));
        when(resultMapper.selectList(any())).thenReturn(List.of(pendingResult(2, "侦查")));

        String formatted = formatter.format(
                "{\"summaryId\":501,\"roundNos\":[2]}");

        assertThat(formatted)
                .contains("第2轮", "等待玩家掷骰")
                .doesNotContain("1D100", "目标值");
    }

    @Test
    void rejectsMissingSummary() {
        DiceRollSummaryMapper summaryMapper = mock(DiceRollSummaryMapper.class);
        GroupDiceMessageFormatter formatter =
                formatter(summaryMapper, mock(DiceRollResultMapper.class));
        when(summaryMapper.selectById(501L)).thenReturn(null);

        assertThatThrownBy(() -> formatter.format(
                "{\"summaryId\":501,\"roundNos\":[2]}"))
                .isInstanceOf(UserRequestException.class);
    }

    @Test
    void rejectsRoundWithoutResultRows() {
        DiceRollSummaryMapper summaryMapper = mock(DiceRollSummaryMapper.class);
        DiceRollResultMapper resultMapper = mock(DiceRollResultMapper.class);
        GroupDiceMessageFormatter formatter = formatter(summaryMapper, resultMapper);
        when(summaryMapper.selectById(501L)).thenReturn(new DiceRollSummary()
                .setId(501L)
                .setReason("侦查书房"));
        when(resultMapper.selectList(any())).thenReturn(List.of(result(2, "侦查", 32)));

        assertThatThrownBy(() -> formatter.format(
                "{\"summaryId\":501,\"roundNos\":[2,3]}"))
                .isInstanceOf(UserRequestException.class);
    }

    private GroupDiceMessageFormatter formatter(
            DiceRollSummaryMapper summaryMapper, DiceRollResultMapper resultMapper) {
        return new GroupDiceMessageFormatter(
                new DiceRollMessageCodec(JsonMapper.builder().build()),
                summaryMapper,
                resultMapper,
                new CocDiceSummaryFormatter());
    }

    private DiceRollResult result(int roundNo, String checkName, int result) {
        return result(
                roundNo,
                checkName,
                result,
                "SUCCESS",
                result == 21 ? "HARD" : "REGULAR");
    }

    private DiceRollResult result(
            int roundNo,
            String checkName,
            int result,
            String category,
            String rank) {
        Map<String, Object> rule = checkRule(checkName);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("characterName", "康特");
        outcome.put("checkName", checkName);
        outcome.put("category", category);
        outcome.put("rank", rank);
        return new DiceRollResult()
                .setId((long) roundNo)
                .setSummaryId(501L)
                .setRoundNo(roundNo)
                .setDisplayOrder(1)
                .setReason(checkName)
                .setResultData(new DiceRollResultVO("1D100", List.of(), result))
                .setResolutionData(DiceResolutionDataVO.pending("CHECK", null, rule)
                        .setOutcome(outcome))
                .setResolvedAt(LocalDateTime.now());
    }

    private DiceRollResult pendingResult(int roundNo, String checkName) {
        return new DiceRollResult()
                .setId((long) roundNo)
                .setSummaryId(501L)
                .setRoundNo(roundNo)
                .setDisplayOrder(1)
                .setReason(checkName)
                .setResultData(new DiceRollResultVO("1D100", List.of(), null))
                .setResolutionData(DiceResolutionDataVO.pending(
                        "CHECK", null, checkRule(checkName)));
    }

    private Map<String, Object> checkRule(String checkName) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("cardId", 11L);
        rule.put("characterName", "康特");
        rule.put("checkName", checkName);
        rule.put("targetValue", 50);
        rule.put("difficulty", "REGULAR");
        rule.put("modifier", "NORMAL");
        rule.put("pushed", false);
        return rule;
    }
}

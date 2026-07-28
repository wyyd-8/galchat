package com.me.galchat.groupchat.dice;

import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.vo.DiceRollResultVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.DiceRollResultMapper;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroupDiceMessageFormatterTest {

    @Test
    void formatsOnlyRoundsDeclaredByTheMessage() {
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
                .contains("第2轮", "第3轮", "侦查", "幸运", "1D100 = 32", "1D100 = 21")
                .doesNotContain("第1轮", "聆听", "1D100 = 44");
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
                resultMapper);
    }

    private DiceRollResult result(int roundNo, String reason, int result) {
        return new DiceRollResult()
                .setId((long) roundNo)
                .setSummaryId(501L)
                .setRoundNo(roundNo)
                .setDisplayOrder(1)
                .setReason(reason)
                .setResultData(new DiceRollResultVO("1D100", List.of(), result));
    }
}

package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.exception.UserRequestException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrpgSummaryIntervalSelectorTest {

    private final TrpgSummaryIntervalSelector selector =
            new TrpgSummaryIntervalSelector();

    @Test
    void keepsOnlyMaximalIntervalsAndLatestSameRangeVersion() {
        GroupContextSummary oldAttic = summary(
                1L, 20L, 40L, 1, "旧阁楼摘要");
        GroupContextSummary newAttic = summary(
                2L, 20L, 40L, 2, "新阁楼摘要");
        GroupContextSummary closet = summary(
                3L, 25L, 32L, 1, "夹层摘要");
        GroupContextSummary basement = summary(
                4L, 60L, 80L, 1, "地下室摘要");

        assertThat(selector.select(List.of(
                closet, oldAttic, basement, newAttic)))
                .extracting(GroupContextSummary::getSummary)
                .containsExactly("新阁楼摘要", "地下室摘要");
    }

    @Test
    void parentSummaryReplacesAllContainedChildSummaries() {
        GroupContextSummary church = summary(
                5L, 1L, 100L, 1, "教堂摘要");

        assertThat(selector.select(List.of(
                summary(1L, 20L, 40L, 1, "阁楼摘要"),
                summary(2L, 60L, 80L, 1, "地下室摘要"),
                church)))
                .containsExactly(church);
    }

    @Test
    void rejectsPartiallyOverlappingIntervals() {
        assertThatThrownBy(() -> selector.select(List.of(
                summary(1L, 10L, 25L, 1, "A"),
                summary(2L, 20L, 40L, 1, "B"))))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("部分相交");
    }

    private GroupContextSummary summary(
            Long id,
            Long start,
            Long end,
            Integer version,
            String content) {
        return new GroupContextSummary()
                .setId(id)
                .setStartSequence(start)
                .setEndSequence(end)
                .setVersion(version)
                .setSummary(content);
    }
}

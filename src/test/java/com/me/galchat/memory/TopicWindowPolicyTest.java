package com.me.galchat.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TopicWindowPolicyTest {

    private final TopicWindowPolicy policy = new TopicWindowPolicy(3, 3);

    @Test
    void appendArchivesTheTopicThatLeavesTheThreeTopicContext() {
        List<Long> startsAfterAppend = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L);

        assertThat(policy.intervalToArchiveAfterAppend(startsAfterAppend))
                .contains(new TopicWindowPolicy.TopicInterval(4L, 5L));
        assertThat(policy.retain(startsAfterAppend))
                .containsExactly(2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(policy.contextStart(policy.retain(startsAfterAppend))).isEqualTo(5L);
    }

    @Test
    void popDeletesTheTopicThatReentersTheThreeTopicContext() {
        List<Long> startsAfterPop = List.of(2L, 3L, 4L, 5L, 6L);

        assertThat(policy.intervalToDeleteAfterPop(startsAfterPop))
                .contains(new TopicWindowPolicy.TopicInterval(4L, 5L));
    }

    @Test
    void shortHistoryHasNoColdInterval() {
        assertThat(policy.intervalToArchiveAfterAppend(List.of(1L, 2L, 3L))).isEmpty();
        assertThat(policy.contextStart(List.of(1L, 2L))).isEqualTo(1L);
    }
}

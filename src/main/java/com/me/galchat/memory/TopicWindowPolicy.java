package com.me.galchat.memory;

import java.util.List;
import java.util.Optional;

public record TopicWindowPolicy(int contextTopicCount, int maxWithdrawRounds) {

    public TopicWindowPolicy {
        if (contextTopicCount <= 0 || maxWithdrawRounds < 0) {
            throw new IllegalArgumentException("topic window sizes must be positive");
        }
    }

    public int retainedTopicCount() {
        return contextTopicCount + maxWithdrawRounds;
    }

    public Optional<TopicInterval> intervalToArchiveAfterAppend(List<Long> starts) {
        return interval(starts, starts.size() - contextTopicCount - 1);
    }

    public Optional<TopicInterval> intervalToDeleteAfterPop(List<Long> starts) {
        return interval(starts, starts.size() - contextTopicCount);
    }

    public Long contextStart(List<Long> starts) {
        if (starts == null || starts.isEmpty()) {
            return null;
        }
        return starts.get(Math.max(0, starts.size() - contextTopicCount));
    }

    public List<Long> retain(List<Long> starts) {
        if (starts == null || starts.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, starts.size() - retainedTopicCount());
        return List.copyOf(starts.subList(from, starts.size()));
    }

    private Optional<TopicInterval> interval(List<Long> starts, int startIndex) {
        if (starts == null || startIndex < 0 || startIndex + 1 >= starts.size()) {
            return Optional.empty();
        }
        Long start = starts.get(startIndex);
        Long end = starts.get(startIndex + 1);
        if (start == null || end == null || start >= end) {
            return Optional.empty();
        }
        return Optional.of(new TopicInterval(start, end));
    }

    public record TopicInterval(Long start, Long end) {
    }
}

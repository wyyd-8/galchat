package com.me.galchat.service.impl;

import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.exception.UserRequestException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TrpgSummaryIntervalSelector {

    public List<GroupContextSummary> select(
            List<GroupContextSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        Map<Range, GroupContextSummary> latestByRange =
                new LinkedHashMap<>();
        for (GroupContextSummary summary : summaries) {
            validate(summary);
            Range range = new Range(
                    summary.getStartSequence(),
                    summary.getEndSequence());
            latestByRange.merge(range, summary, this::newer);
        }
        List<GroupContextSummary> ordered =
                new ArrayList<>(latestByRange.values());
        ordered.sort(Comparator
                .comparing(GroupContextSummary::getStartSequence)
                .thenComparing(
                        GroupContextSummary::getEndSequence,
                        Comparator.reverseOrder()));
        rejectPartialOverlaps(ordered);

        List<GroupContextSummary> maximal = new ArrayList<>();
        for (GroupContextSummary summary : ordered) {
            if (!maximal.isEmpty()
                    && maximal.getLast().getEndSequence()
                    >= summary.getEndSequence()) {
                continue;
            }
            maximal.add(summary);
        }
        return List.copyOf(maximal);
    }

    private void rejectPartialOverlaps(
            List<GroupContextSummary> ordered) {
        ArrayDeque<GroupContextSummary> stack = new ArrayDeque<>();
        for (GroupContextSummary summary : ordered) {
            while (!stack.isEmpty()
                    && summary.getStartSequence()
                    > stack.peek().getEndSequence()) {
                stack.pop();
            }
            if (!stack.isEmpty()
                    && summary.getEndSequence()
                    > stack.peek().getEndSequence()) {
                throw new UserRequestException(
                        "场景摘要区间存在部分相交："
                                + interval(stack.peek()) + " 与 "
                                + interval(summary));
            }
            stack.push(summary);
        }
    }

    private GroupContextSummary newer(
            GroupContextSummary left,
            GroupContextSummary right) {
        int versionComparison = Integer.compare(
                version(left), version(right));
        if (versionComparison != 0) {
            return versionComparison > 0 ? left : right;
        }
        return id(left) >= id(right) ? left : right;
    }

    private void validate(GroupContextSummary summary) {
        if (summary == null
                || summary.getStartSequence() == null
                || summary.getEndSequence() == null
                || summary.getStartSequence()
                > summary.getEndSequence()) {
            throw new UserRequestException("场景摘要区间无效");
        }
    }

    private int version(GroupContextSummary summary) {
        return summary.getVersion() == null
                ? 0 : summary.getVersion();
    }

    private long id(GroupContextSummary summary) {
        return summary.getId() == null ? 0L : summary.getId();
    }

    private String interval(GroupContextSummary summary) {
        return "[" + summary.getStartSequence()
                + "," + summary.getEndSequence() + "]";
    }

    private record Range(long start, long end) {
    }
}

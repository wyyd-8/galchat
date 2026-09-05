package com.me.galchat.service.impl.trpg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupContextSummaryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TrpgExplorationRecordService {

    private final GroupChatMessageMapper messageMapper;
    private final GroupContextSummaryMapper summaryMapper;
    private final TrpgSummaryIntervalSelector intervalSelector;

    public List<Part> assemble(
            Long conversationId,
            long startSequence,
            long endSequence) {
        if (endSequence < startSequence) {
            return List.of();
        }
        List<GroupChatMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<GroupChatMessage>()
                        .eq(GroupChatMessage::getConversationId,
                                conversationId)
                        .ge(GroupChatMessage::getSequenceNo,
                                startSequence)
                        .le(GroupChatMessage::getSequenceNo,
                                endSequence)
                        .eq(GroupChatMessage::getStatus,
                                GroupChatConstant.STATUS_COMPLETED)
                        .eq(GroupChatMessage::getVisibility, "public")
                        .orderByAsc(GroupChatMessage::getSequenceNo));
        List<GroupContextSummary> summaries =
                intervalSelector.select(summaryMapper.selectList(
                        new LambdaQueryWrapper<GroupContextSummary>()
                                .eq(GroupContextSummary::getConversationId,
                                        conversationId)
                                .ge(GroupContextSummary::getStartSequence,
                                        startSequence)
                                .le(GroupContextSummary::getEndSequence,
                                        endSequence)
                                .orderByAsc(
                                        GroupContextSummary::getStartSequence)
                                .orderByDesc(
                                        GroupContextSummary::getEndSequence)));

        List<Part> parts = new ArrayList<>();
        long cursor = startSequence;
        for (GroupContextSummary summary : summaries) {
            appendMessages(parts, messages, cursor,
                    summary.getStartSequence() - 1);
            parts.add(Part.summary(summary));
            cursor = summary.getEndSequence() + 1;
        }
        appendMessages(parts, messages, cursor, endSequence);
        return List.copyOf(parts);
    }

    private void appendMessages(
            List<Part> parts,
            List<GroupChatMessage> messages,
            long start,
            long end) {
        if (end < start) {
            return;
        }
        List<GroupChatMessage> selected = messages.stream()
                .filter(message -> message.getSequenceNo() != null
                        && message.getSequenceNo() >= start
                        && message.getSequenceNo() <= end)
                .toList();
        if (!selected.isEmpty()) {
            parts.add(Part.messages(selected));
        }
    }

    public record Part(
            GroupContextSummary summary,
            List<GroupChatMessage> messages) {

        public static Part summary(GroupContextSummary summary) {
            return new Part(summary, List.of());
        }

        public static Part messages(List<GroupChatMessage> messages) {
            return new Part(null, List.copyOf(messages));
        }

        public boolean isSummary() {
            return summary != null;
        }
    }
}

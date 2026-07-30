package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupContextSummaryMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TrpgSceneSummaryService {

    private final GroupChatMessageMapper messageMapper;
    private final GroupContextSummaryMapper summaryMapper;
    private final TrpgExplorationRecordService recordService;
    private final TrpgSummaryTextGenerator textGenerator;

    public TrpgSceneSummaryService(
            GroupChatMessageMapper messageMapper,
            GroupContextSummaryMapper summaryMapper,
            TrpgExplorationRecordService recordService,
            TrpgSummaryTextGenerator textGenerator) {
        this.messageMapper = messageMapper;
        this.summaryMapper = summaryMapper;
        this.recordService = recordService;
        this.textGenerator = textGenerator;
    }

    public GroupContextSummary summarize(
            Long conversationId, Long sceneId, Long scenePlanId) {
        List<GroupChatMessage> planMessages =
                messageMapper.selectCompletedPublicByPlanId(
                        conversationId, scenePlanId);
        if (planMessages == null || planMessages.isEmpty()) {
            return null;
        }
        long startSequence =
                planMessages.getFirst().getSequenceNo();
        long endSequence =
                planMessages.getLast().getSequenceNo();
        List<GroupContextSummary> previousVersions =
                summaryMapper.selectList(
                new LambdaQueryWrapper<GroupContextSummary>()
                        .eq(GroupContextSummary::getConversationId,
                                conversationId)
                        .eq(GroupContextSummary::getScenePlanId,
                                scenePlanId)
                        .orderByDesc(GroupContextSummary::getVersion));
        GroupContextSummary previous =
                previousVersions == null || previousVersions.isEmpty()
                        ? null : previousVersions.getFirst();
        if (previous != null
                && Long.valueOf(startSequence).equals(
                previous.getStartSequence())
                && Long.valueOf(endSequence).equals(
                previous.getEndSequence())) {
            return previous;
        }
        StringBuilder history = new StringBuilder();
        for (TrpgExplorationRecordService.Part part :
                recordService.assemble(
                        conversationId, startSequence, endSequence)) {
            if (part.isSummary()) {
                history.append("[场景摘要 ")
                        .append(part.summary().getStartSequence())
                        .append('-')
                        .append(part.summary().getEndSequence())
                        .append("] ")
                        .append(part.summary().getSummary())
                        .append('\n');
                continue;
            }
            for (GroupChatMessage message : part.messages()) {
                history.append('[').append(message.getSpeakerType())
                        .append("] ").append(message.getContent())
                        .append('\n');
            }
        }
        String summary = textGenerator.summarize(history.toString());
        if (!StringUtils.hasText(summary)) {
            return null;
        }
        GroupContextSummary result = new GroupContextSummary()
                .setConversationId(conversationId)
                .setSceneId(sceneId)
                .setScenePlanId(scenePlanId)
                .setStartSequence(startSequence)
                .setEndSequence(endSequence)
                .setSummary(summary.trim())
                .setVersion(previous == null
                        ? 1 : (previous.getVersion() == null
                                ? 2 : previous.getVersion() + 1))
                .setCreatedAt(LocalDateTime.now());
        summaryMapper.insert(result);
        return result;
    }
}

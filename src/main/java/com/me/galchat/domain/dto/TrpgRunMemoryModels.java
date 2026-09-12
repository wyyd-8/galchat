package com.me.galchat.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.me.galchat.domain.vo.TrpgGameTimeVO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class TrpgRunMemoryModels {

    public static final String SNAPSHOT_NOTICE =
            "以下内容来自当前跑团快照；存档读取或回撤后，历史消息、人物卡、掷骰统计与场景状态都可能改变。";

    private TrpgRunMemoryModels() {
    }

    public record RunListResult(
            LocalDateTime snapshotAt,
            String snapshotNotice,
            List<RunBrief> runs) {
    }

    public record RunBrief(
            Long runId,
            String moduleName,
            String status,
            Map<String, String> names,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record RecentRunBrief(
            Long runId,
            String moduleName,
            String status,
            Map<String, String> names,
            LocalDateTime updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunDetails(
            Long runId,
            String moduleName,
            String status,
            ControlledInvestigator controlledInvestigator,
            OwnDiceStatistics ownDiceStatistics,
            TrpgGameTimeVO gameTime,
            CurrentScene currentScene,
            List<CompletedScene> completedScenes,
            PublicMessage latestPublicMessage,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime closedAt,
            String summary,
            LocalDateTime snapshotAt,
            String snapshotNotice) {
    }

    public record ControlledInvestigator(
            String name,
            String occupation,
            Integer age,
            String sex,
            Map<String, Integer> attributes,
            Map<String, Object> derived,
            List<String> statuses,
            Map<String, Integer> nonBaseSkills) {
    }

    public record OwnDiceStatistics(
            int total,
            int criticalSuccess,
            int success,
            int failure,
            int fumble) {
    }

    public record CurrentScene(Long planId, String name) {
    }

    public record CompletedScene(
            Long scenePlanId,
            Long startSequence,
            Long endSequence,
            String summary) {
    }

    public record PublicMessage(
            Long messageId,
            String speakerName,
            String messageKind,
            String content,
            LocalDateTime createdAt) {
    }

    public record ChatSearchResult(
            Long runId,
            String keyword,
            LocalDateTime snapshotAt,
            String snapshotNotice,
            List<ChatRound> rounds) {
    }

    public record ChatRound(
            Long turnId,
            LocalDateTime occurredAt,
            double score,
            List<PublicMessage> messages) {
    }
}

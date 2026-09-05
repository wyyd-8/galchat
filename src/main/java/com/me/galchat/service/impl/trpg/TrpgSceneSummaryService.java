package com.me.galchat.service.impl.trpg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.vo.TrpgGameTimeVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.material.MaterialMessageCodec;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupContextSummaryMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class TrpgSceneSummaryService {

    private final GroupChatMessageMapper messageMapper;
    private final GroupContextSummaryMapper summaryMapper;
    private final TrpgExplorationRecordService recordService;
    private final TrpgSummaryTextGenerator textGenerator;
    private final GroupConversationMapper conversationMapper;
    private final GroupReplyPlanMapper planMapper;
    private final TrpgSceneParticipantService participantService;
    private final MaterialMessageCodec materialMessageCodec;

    public TrpgSceneSummaryService(
            GroupChatMessageMapper messageMapper,
            GroupContextSummaryMapper summaryMapper,
            TrpgExplorationRecordService recordService,
            TrpgSummaryTextGenerator textGenerator,
            GroupConversationMapper conversationMapper,
            GroupReplyPlanMapper planMapper,
            TrpgSceneParticipantService participantService,
            MaterialMessageCodec materialMessageCodec) {
        this.messageMapper = messageMapper;
        this.summaryMapper = summaryMapper;
        this.recordService = recordService;
        this.textGenerator = textGenerator;
        this.conversationMapper = conversationMapper;
        this.planMapper = planMapper;
        this.participantService = participantService;
        this.materialMessageCodec = materialMessageCodec;
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
        GroupReplyPlan scenePlan = planMapper.selectById(scenePlanId);
        String summary = scenePlan != null
                && scenePlan.getParentPlanId() != null
                ? summarizeChild(
                        conversationId, scenePlan, planMessages)
                : summarizeParent(
                        conversationId, startSequence, endSequence);
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

    private String summarizeParent(
            Long conversationId,
            long startSequence,
            long endSequence) {
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
        return textGenerator.summarize(history.toString());
    }

    private String summarizeChild(
            Long conversationId,
            GroupReplyPlan scenePlan,
            List<GroupChatMessage> planMessages) {
        GroupConversation conversation =
                conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new UserRequestException("群聊不存在");
        }
        TrpgSceneParticipantService.SceneSummaryState sceneState =
                participantService.summaryState(
                        conversation, scenePlan);
        TrpgGameTimeVO gameTime = TrpgGameTimeVO.from(conversation);
        String evidence = childClueEvidence(planMessages);
        String clues = StringUtils.hasText(evidence)
                ? textGenerator.summarizeChildClues(evidence)
                : "无";
        if (!StringUtils.hasText(clues)) {
            clues = "无";
        }
        String participants = sceneState.investigatorNames().isEmpty()
                ? "无"
                : String.join("、", sceneState.investigatorNames());
        String time = gameTime == null
                ? "未设置"
                : gameTime.displayText();
        return "参与者：" + participants
                + "\n时间：" + time
                + "\n地点：" + sceneState.scenePath()
                + "\n可用线索：\n" + clues.trim();
    }

    private String childClueEvidence(
            List<GroupChatMessage> messages) {
        List<String> evidence = new ArrayList<>();
        for (GroupChatMessage message : messages) {
            if (!GroupChatConstant.ACTOR_KP.equals(
                    message.getSpeakerType())
                    || !StringUtils.hasText(message.getContent())) {
                continue;
            }
            if (GroupChatConstant.MESSAGE_MATERIAL.equals(
                    message.getMessageKind())) {
                evidence.add("[已展示材料]\n"
                        + materialMessageCodec.toAgentText(
                        message.getContent()));
                continue;
            }
            if (GroupChatConstant.MESSAGE_DIALOGUE.equals(
                    message.getMessageKind())
                    || GroupChatConstant.MESSAGE_NARRATION.equals(
                    message.getMessageKind())) {
                evidence.add("[KP描述]\n" + message.getContent());
            }
        }
        return String.join("\n", evidence);
    }
}

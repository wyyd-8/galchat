package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TrpgStepInteractionService {

    public static final String KP_CLARIFICATION = "KP_CLARIFICATION";
    public static final String TEAM_RISK_CONFIRMATION =
            "TEAM_RISK_CONFIRMATION";
    private static final int MAX_INTERACTIONS_PER_ROOT = 6;
    private static final Set<String> SCOPES = Set.of("INDIVIDUAL", "TEAM");
    private static final Set<String> REASONS = Set.of(
            "GOAL", "METHOD", "TARGET", "RISK", "CHOICE");

    private final GroupChatReplyStepMapper stepMapper;
    private final GroupChatTurnMapper turnMapper;
    private final TrpgParticipantService participantService;
    private final TransactionTemplate transactionTemplate;

    public InteractionRequest askForClarification(
            GroupConversation conversation,
            Long turnId,
            Long replyStepId,
            String rawScope,
            String rawInvestigatorName,
            String rawQuestion,
            String rawReasonType) {
        String scope = normalizeEnum(rawScope, "追问范围");
        String question = normalizeRequired(rawQuestion, "追问内容");
        String reasonType = normalizeEnum(rawReasonType, "追问原因");
        if (!SCOPES.contains(scope)) {
            throw new UserRequestException(
                    "追问范围只能是INDIVIDUAL或TEAM");
        }
        if (!REASONS.contains(reasonType)) {
            throw new UserRequestException("追问原因类型不受支持");
        }
        if (question.length() > 200) {
            throw new UserRequestException("单次追问不能超过200个字符");
        }
        return transactionTemplate.execute(status -> createInteraction(
                conversation, turnId, replyStepId, scope,
                rawInvestigatorName, question, reasonType));
    }

    private InteractionRequest createInteraction(
            GroupConversation conversation,
            Long turnId,
            Long replyStepId,
            String scope,
            String rawInvestigatorName,
            String question,
            String reasonType) {
        if (conversation == null || conversation.getId() == null
                || !GroupChatConstant.MODE_TRPG.equals(
                conversation.getMode())) {
            throw new UserRequestException("追问只适用于跑团会话");
        }
        GroupChatTurn turn = turnMapper.selectById(turnId);
        if (turn == null
                || !conversation.getId().equals(turn.getConversationId())) {
            throw new UserRequestException("追问行动轮不存在");
        }
        GroupChatReplyStep parent = stepMapper.selectById(replyStepId);
        if (parent == null || !turnId.equals(parent.getTurnId())
                || parent.getParentStepId() != null) {
            throw new UserRequestException("追问父步骤不存在");
        }
        if (!GroupChatConstant.ACTOR_KP.equals(parent.getSpeakerType())
                || !(GroupChatConstant.ACTION_TRPG_SCENE.equals(
                parent.getActionType())
                || GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE.equals(
                parent.getActionType()))) {
            throw new UserRequestException(
                    "当前步骤不允许KP发起追问");
        }
        List<GroupChatReplyStep> interactions = interactions(parent.getId());
        if (interactions.size() >= MAX_INTERACTIONS_PER_ROOT) {
            throw new UserRequestException("一次裁定最多追问6次");
        }
        if (interactions.stream().anyMatch(this::isOpen)) {
            throw new UserRequestException("当前仍有未完成的追问");
        }

        TrpgParticipantService.Participant target = resolveTarget(
                conversation, scope, rawInvestigatorName);
        int interactionSeq = interactions.size() + 1;
        LocalDateTime now = LocalDateTime.now();
        GroupChatReplyStep child = new GroupChatReplyStep()
                .setTurnId(turnId)
                .setParentStepId(parent.getId())
                .setRootStepId(parent.getRootStepId() == null
                        ? parent.getId() : parent.getRootStepId())
                .setInteractionType("TEAM".equals(scope)
                        ? TEAM_RISK_CONFIRMATION : KP_CLARIFICATION)
                .setInteractionSeq(interactionSeq)
                .setPromptMessageId(parent.getOutputMessageId())
                .setGroupKey(parent.getGroupKey())
                .setGroupName(parent.getGroupName())
                .setGroupOrder(parent.getGroupOrder())
                .setItemOrder(parent.getItemOrder())
                .setStepNo(nextStepNo(turnId))
                .setActionType(
                        GroupChatConstant.ACTION_TRPG_INTERACTION_RESPONSE)
                .setSpeakerType(target.actor().type())
                .setSpeakerId(target.actor().id())
                .setSubjectCharacterId(target.cardId())
                .setForceReply(true)
                .setStatus(GroupChatConstant.STATUS_PENDING)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        stepMapper.insert(child);
        parent.setStatus(GroupChatConstant.STATUS_WAITING_INTERACTION)
                .setErrorMessage(null)
                .setUpdatedAt(now);
        stepMapper.updateById(parent);
        return new InteractionRequest(
                child.getId(), child.getRootStepId(),
                child.getInteractionType(), interactionSeq,
                target.actor(), target.cardId(), question, reasonType);
    }

    private List<GroupChatReplyStep> interactions(Long rootStepId) {
        List<GroupChatReplyStep> result = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getRootStepId, rootStepId)
                        .isNotNull(GroupChatReplyStep::getParentStepId)
                        .orderByAsc(
                                GroupChatReplyStep::getInteractionSeq));
        return result == null ? List.of() : result;
    }

    private boolean isOpen(GroupChatReplyStep step) {
        return GroupChatConstant.STATUS_PENDING.equals(step.getStatus())
                || GroupChatConstant.STATUS_RUNNING.equals(step.getStatus())
                || GroupChatConstant.STATUS_WAITING_INPUT.equals(
                step.getStatus());
    }

    private int nextStepNo(Long turnId) {
        List<GroupChatReplyStep> last = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turnId)
                        .orderByDesc(GroupChatReplyStep::getStepNo)
                        .last("limit 1"));
        return last == null || last.isEmpty()
                ? 1 : last.getFirst().getStepNo() + 1;
    }

    private TrpgParticipantService.Participant resolveTarget(
            GroupConversation conversation,
            String scope,
            String rawInvestigatorName) {
        List<TrpgParticipantService.Participant> participants =
                participantService.listInvestigators(conversation);
        if ("TEAM".equals(scope)) {
            return participants.stream()
                    .filter(item -> GroupChatConstant.ACTOR_USER.equals(
                            item.actor().type()))
                    .findFirst()
                    .orElseThrow(() -> new UserRequestException(
                            "团队追问找不到真人玩家调查员"));
        }
        String investigatorName = normalizeRequired(
                rawInvestigatorName, "调查员名称");
        return participants.stream()
                .filter(item -> investigatorName.equals(
                        item.investigatorName()))
                .findFirst()
                .orElseThrow(() -> new UserRequestException(
                        "找不到追问目标调查员：" + investigatorName));
    }

    private String normalizeRequired(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new UserRequestException(label + "不能为空");
        }
        return value.trim();
    }

    private String normalizeEnum(String value, String label) {
        return normalizeRequired(value, label)
                .toUpperCase(java.util.Locale.ROOT);
    }

    public record InteractionRequest(
            Long childStepId,
            Long rootStepId,
            String interactionType,
            Integer interactionSeq,
            GroupActorRef targetActor,
            Long targetCharacterId,
            String question,
            String reasonType) {
    }
}

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
    public static final String INVESTIGATOR_KP_INQUIRY =
            "INVESTIGATOR_KP_INQUIRY";
    private static final int MAX_INTERACTIONS_PER_ROOT = 6;
    private static final Set<String> SCOPES = Set.of("INDIVIDUAL", "TEAM");
    private static final Set<String> REASONS = Set.of(
            "GOAL", "METHOD", "TARGET", "RISK", "CHOICE");
    private static final Set<String> INQUIRY_TYPES = Set.of(
            "CONFIRM_PUBLIC_FACT", "DESCRIBE_VISIBLE_INFORMATION");

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

    public InteractionRequest askKp(
            GroupConversation conversation,
            Long turnId,
            Long replyStepId,
            GroupActorRef requester,
            String rawInquiryType,
            String rawQuestion) {
        String inquiryType = normalizeEnum(
                rawInquiryType, "询问类型");
        String question = normalizeRequired(rawQuestion, "询问内容");
        if (!INQUIRY_TYPES.contains(inquiryType)) {
            throw new UserRequestException("询问类型不受支持");
        }
        if (question.length() > 200) {
            throw new UserRequestException("单次询问不能超过200个字符");
        }
        return transactionTemplate.execute(status ->
                createInvestigatorInquiry(
                        conversation, turnId, replyStepId,
                        requester, inquiryType, question));
    }

    private InteractionRequest createInvestigatorInquiry(
            GroupConversation conversation,
            Long turnId,
            Long replyStepId,
            GroupActorRef requester,
            String inquiryType,
            String question) {
        if (conversation == null || conversation.getId() == null
                || !GroupChatConstant.MODE_TRPG.equals(
                conversation.getMode())) {
            throw new UserRequestException("询问只适用于跑团会话");
        }
        GroupChatTurn turn = turnMapper.selectById(turnId);
        if (turn == null
                || !conversation.getId().equals(turn.getConversationId())) {
            throw new UserRequestException("询问行动轮不存在");
        }
        GroupChatReplyStep root = stepMapper.selectById(replyStepId);
        if (root == null || !turnId.equals(root.getTurnId())) {
            throw new UserRequestException("询问父步骤不存在");
        }
        if (requester == null
                || !(GroupChatConstant.ACTOR_CHARACTER.equals(
                requester.type())
                || GroupChatConstant.ACTOR_USER.equals(
                requester.type()))
                || !requester.type().equals(root.getSpeakerType())
                || !java.util.Objects.equals(
                requester.id(), root.getSpeakerId())) {
            throw new UserRequestException("只有当前调查员可以询问KP");
        }
        if (root.getParentStepId() != null
                || !(GroupChatConstant.ACTION_TRPG_SCENE.equals(
                root.getActionType())
                || GroupChatConstant.ACTION_COMBAT_ATTACK.equals(
                root.getActionType()))) {
            throw new UserRequestException("当前步骤不允许调查员询问KP");
        }
        TrpgParticipantService.Participant participant =
                participantService.listInvestigators(conversation).stream()
                        .filter(item -> requester.equals(item.actor()))
                        .filter(item -> java.util.Objects.equals(
                                root.getSubjectCharacterId(),
                                item.cardId()))
                        .findFirst()
                        .orElseThrow(() -> new UserRequestException(
                                "当前调查员与人物卡不匹配"));
        List<GroupChatReplyStep> interactions = interactions(root.getId());
        if (interactions.size() >= MAX_INTERACTIONS_PER_ROOT) {
            throw new UserRequestException("一次行动最多询问6次");
        }
        if (interactions.stream().anyMatch(this::isOpen)) {
            throw new UserRequestException("当前仍有未完成的询问");
        }

        int interactionSeq = interactions.size() + 1;
        LocalDateTime now = LocalDateTime.now();
        GroupChatReplyStep child = new GroupChatReplyStep()
                .setTurnId(turnId)
                .setParentStepId(root.getId())
                .setRootStepId(root.getId())
                .setInteractionType(INVESTIGATOR_KP_INQUIRY)
                .setInteractionSeq(interactionSeq)
                .setPromptMessageId(root.getOutputMessageId())
                .setGroupKey(root.getGroupKey())
                .setGroupName(root.getGroupName())
                .setGroupOrder(root.getGroupOrder())
                .setItemOrder(root.getItemOrder())
                .setStepNo(nextStepNo(turnId))
                .setActionType(
                        GroupChatConstant.ACTION_TRPG_INTERACTION_RESPONSE)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setSubjectCharacterId(participant.cardId())
                .setForceReply(true)
                .setStatus(GroupChatConstant.STATUS_PENDING)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        stepMapper.insert(child);
        root.setInteractionType(INVESTIGATOR_KP_INQUIRY)
                .setStatus(GroupChatConstant.STATUS_WAITING_INTERACTION)
                .setErrorMessage(null)
                .setUpdatedAt(now);
        stepMapper.updateById(root);
        return new InteractionRequest(
                child.getId(), root.getId(), INVESTIGATOR_KP_INQUIRY,
                interactionSeq,
                new GroupActorRef(GroupChatConstant.ACTOR_KP, null),
                null, question, inquiryType);
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
        GroupChatReplyStep source = stepMapper.selectById(replyStepId);
        if (source == null || !turnId.equals(source.getTurnId())) {
            throw new UserRequestException("追问父步骤不存在");
        }
        if (!GroupChatConstant.ACTOR_KP.equals(source.getSpeakerType())
                || !(GroupChatConstant.ACTION_TRPG_SCENE.equals(
                source.getActionType())
                || GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE.equals(
                source.getActionType()))) {
            throw new UserRequestException(
                    "当前步骤不允许KP发起追问");
        }
        GroupChatReplyStep root = interactionRoot(source, turnId);
        List<GroupChatReplyStep> interactions = interactions(root.getId());
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
                .setParentStepId(root.getId())
                .setRootStepId(root.getId())
                .setInteractionType("TEAM".equals(scope)
                        ? TEAM_RISK_CONFIRMATION : KP_CLARIFICATION)
                .setInteractionSeq(interactionSeq)
                .setPromptMessageId(source.getOutputMessageId())
                .setGroupKey(root.getGroupKey())
                .setGroupName(root.getGroupName())
                .setGroupOrder(root.getGroupOrder())
                .setItemOrder(root.getItemOrder())
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
        root.setStatus(GroupChatConstant.STATUS_WAITING_INTERACTION)
                .setErrorMessage(null)
                .setUpdatedAt(now);
        stepMapper.updateById(root);
        return new InteractionRequest(
                child.getId(), child.getRootStepId(),
                child.getInteractionType(), interactionSeq,
                target.actor(), target.cardId(), question, reasonType);
    }

    private GroupChatReplyStep interactionRoot(
            GroupChatReplyStep source, Long turnId) {
        if (source.getParentStepId() == null) {
            return source;
        }
        if (!GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE.equals(
                source.getActionType())) {
            throw new UserRequestException("追问只支持一层子步骤");
        }
        GroupChatReplyStep root = stepMapper.selectById(
                source.getParentStepId());
        if (root == null || !turnId.equals(root.getTurnId())
                || root.getParentStepId() != null
                || !GroupChatConstant.ACTION_COMBAT_ADJUDICATE.equals(
                root.getActionType())) {
            throw new UserRequestException("战斗裁定根步骤不存在");
        }
        return root;
    }

    private List<GroupChatReplyStep> interactions(Long rootStepId) {
        List<GroupChatReplyStep> result = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getRootStepId, rootStepId)
                        .isNotNull(GroupChatReplyStep::getParentStepId)
                        .isNotNull(GroupChatReplyStep::getInteractionType)
                        .orderByAsc(
                                GroupChatReplyStep::getInteractionSeq));
        return result == null ? List.of() : result;
    }

    private boolean isOpen(GroupChatReplyStep step) {
        return GroupChatConstant.STATUS_PENDING.equals(step.getStatus())
                || GroupChatConstant.STATUS_RUNNING.equals(step.getStatus())
                || GroupChatConstant.STATUS_WAITING_INPUT.equals(
                step.getStatus())
                || GroupChatConstant.STATUS_WAITING_DICE.equals(
                step.getStatus())
                || GroupChatConstant.STATUS_PAUSED.equals(
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

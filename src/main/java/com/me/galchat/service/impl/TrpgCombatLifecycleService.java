package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.KpQuickNpcDTOs;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.TrpgCombat;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.TrpgCombatMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrpgCombatLifecycleService {

    private final GroupConversationService conversationService;
    private final GroupReplyPlanService replyPlanService;
    private final GroupReplyPlanMapper planMapper;
    private final GroupChatTurnMapper turnMapper;
    private final GroupChatToolCallMapper toolCallMapper;
    private final GroupChatReplyStepMapper stepMapper;
    private final GroupChatMessageMapper messageMapper;
    private final CocCharacterMapper characterMapper;
    private final TrpgCombatMapper combatMapper;
    private final TrpgQuickNpcTemplateService quickNpcTemplateService;
    private final ObjectMapper objectMapper;

    @Transactional
    public GroupChatReplyStep prepareAdjudicationRoot(
            GroupChatTurn turn,
            GroupChatReplyStep adjudication) {
        requireAdjudicationRoot(turn, adjudication);
        List<GroupChatReplyStep> children = childrenOf(
                adjudication.getId());
        GroupChatReplyStep next = children.stream()
                .filter(this::isUnfinished)
                .findFirst()
                .orElse(null);
        if (children.isEmpty()) {
            next = appendChild(
                    adjudication,
                    GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE,
                    GroupChatConstant.ACTOR_KP,
                    null,
                    adjudication.getSubjectCharacterId(),
                    GroupChatConstant.STATUS_PENDING);
        }
        if (next != null) {
            adjudication.setStatus(
                            GroupChatConstant.STATUS_WAITING_INTERACTION)
                    .setErrorMessage(null)
                    .setUpdatedAt(LocalDateTime.now());
            stepMapper.updateById(adjudication);
        }
        return next;
    }

    @Transactional
    public GroupChatReplyStep advanceAdjudicationChild(
            GroupChatTurn turn,
            GroupChatReplyStep completedChild) {
        if (completedChild == null
                || completedChild.getParentStepId() == null
                || !GroupChatConstant.STATUS_COMPLETED.equals(
                completedChild.getStatus())) {
            throw new UserRequestException("战斗子步骤尚未完成");
        }
        GroupChatReplyStep adjudication = stepMapper.selectById(
                completedChild.getParentStepId());
        requireAdjudicationRoot(turn, adjudication);
        List<GroupChatReplyStep> children = childrenOf(
                adjudication.getId());
        if (GroupChatConstant.ACTION_TRPG_INTERACTION_RESPONSE.equals(
                completedChild.getActionType())) {
            GroupChatReplyStep retry = children.stream()
                    .filter(step -> step.getStepNo()
                            > completedChild.getStepNo())
                    .filter(step -> GroupChatConstant
                            .ACTION_COMBAT_REACTION_ROUTE.equals(
                                    step.getActionType()))
                    .findFirst()
                    .orElse(null);
            if (retry == null) {
                retry = appendChild(
                        adjudication,
                        GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE,
                        GroupChatConstant.ACTOR_KP,
                        null,
                        adjudication.getSubjectCharacterId(),
                        GroupChatConstant.STATUS_PENDING);
            }
            updateAdjudicationStatus(
                    adjudication,
                    GroupChatConstant.STATUS_WAITING_INTERACTION);
            return retry;
        }
        GroupChatReplyStep next = children.stream()
                .filter(this::isUnfinished)
                .findFirst()
                .orElse(null);
        updateAdjudicationStatus(
                adjudication,
                next == null
                        ? GroupChatConstant.STATUS_PENDING
                        : GroupChatConstant.STATUS_WAITING_INTERACTION);
        return next;
    }

    private void updateAdjudicationStatus(
            GroupChatReplyStep adjudication,
            String status) {
        adjudication.setStatus(status)
                .setErrorMessage(null)
                .setUpdatedAt(LocalDateTime.now());
        stepMapper.updateById(adjudication);
    }

    private void requireAdjudicationRoot(
            GroupChatTurn turn,
            GroupChatReplyStep adjudication) {
        if (turn == null || turn.getId() == null
                || adjudication == null
                || !turn.getId().equals(adjudication.getTurnId())
                || adjudication.getParentStepId() != null
                || !GroupChatConstant.ACTION_COMBAT_ADJUDICATE.equals(
                adjudication.getActionType())) {
            throw new UserRequestException("战斗裁定根步骤不存在");
        }
    }

    private GroupChatReplyStep appendChild(
            GroupChatReplyStep root,
            String actionType,
            String speakerType,
            Long speakerId,
            Long subjectCharacterId,
            String status) {
        LocalDateTime now = LocalDateTime.now();
        GroupChatReplyStep child = new GroupChatReplyStep()
                .setTurnId(root.getTurnId())
                .setParentStepId(root.getId())
                .setRootStepId(root.getId())
                .setGroupKey(root.getGroupKey())
                .setGroupName(root.getGroupName())
                .setGroupOrder(root.getGroupOrder())
                .setItemOrder(root.getItemOrder())
                .setStepNo(nextStepNo(root.getTurnId()))
                .setActionType(actionType)
                .setSpeakerType(speakerType)
                .setSpeakerId(speakerId)
                .setSubjectCharacterId(subjectCharacterId)
                .setForceReply(false)
                .setStatus(status)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        stepMapper.insert(child);
        return child;
    }

    private List<GroupChatReplyStep> childrenOf(Long rootStepId) {
        List<GroupChatReplyStep> children = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getParentStepId,
                                rootStepId)
                        .orderByAsc(GroupChatReplyStep::getStepNo));
        return children == null ? List.of() : children.stream()
                .filter(step -> rootStepId.equals(
                        step.getParentStepId()))
                .toList();
    }

    private boolean isUnfinished(GroupChatReplyStep step) {
        return !GroupChatConstant.STATUS_COMPLETED.equals(step.getStatus())
                && !GroupChatConstant.STATUS_CANCELLED.equals(
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

    @Transactional
    public RouteDecision completeReactionRoute(
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep routeStep,
            String rawJson) {
        if (!GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE.equals(
                routeStep.getActionType())) {
            throw new UserRequestException("当前步骤不是战斗反应路由");
        }
        tools.jackson.databind.JsonNode root;
        try {
            root = objectMapper.readTree(rawJson);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new UserRequestException("KP战斗路由没有返回合法JSON");
        }
        if (root.get("error") != null
                && StringUtils.hasText(root.get("error").asText())) {
            throw new UserRequestException(
                    "战斗目标无法确定：" + root.get("error").asText());
        }
        TrpgCombat combat = requireActiveCombat(conversation);
        String actionKind = root.get("actionKind") == null
                ? "TARGETED" : root.get("actionKind").asText();
        if ("SELF_OR_UTILITY".equals(actionKind)) {
            GroupChatReplyStep defense = defenseForRoute(turn, routeStep);
            defense.setSubjectCharacterId(null)
                    .setStatus(GroupChatConstant.STATUS_CANCELLED)
                    .setUpdatedAt(LocalDateTime.now());
            stepMapper.updateById(defense);
            return new RouteDecision(
                    null, null, false, List.of(),
                    root.get("reason") == null
                            ? null : root.get("reason").asText());
        }
        if (!"TARGETED".equals(actionKind)) {
            throw new UserRequestException("KP战斗路由行动类型无效");
        }
        tools.jackson.databind.JsonNode targetNodes = root.get("targets");
        if (targetNodes != null && targetNodes.isArray()) {
            if (routeStep.getParentStepId() == null) {
                throw new UserRequestException("旧版战斗路由不支持多个目标");
            }
            if (targetNodes.isEmpty()) {
                throw new UserRequestException("KP战斗路由缺少目标人物卡");
            }
            RouteDecision first = null;
            for (tools.jackson.databind.JsonNode targetNode : targetNodes) {
                RouteDecision decision = applyTargetRoute(
                        conversation, turn, routeStep, combat,
                        targetNode, root);
                if (first == null) {
                    first = decision;
                }
            }
            return first;
        }
        return applyTargetRoute(
                conversation, turn, routeStep, combat, root, root);
    }

    private RouteDecision applyTargetRoute(
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep routeStep,
            TrpgCombat combat,
            tools.jackson.databind.JsonNode targetNode,
            tools.jackson.databind.JsonNode root) {
        String targetName = targetNode.get("targetName") == null
                ? null : targetNode.get("targetName").asText();
        if (!StringUtils.hasText(targetName)) {
            throw new UserRequestException("KP战斗路由缺少目标人物卡");
        }
        Long targetId = null;
        for (tools.jackson.databind.JsonNode participant :
                combat.getParticipants()) {
            if (targetName.equals(participant.get("name").asText())) {
                targetId = participant.get("characterId").asLong();
                break;
            }
        }
        if (targetId == null
                || Objects.equals(targetId,
                routeStep.getSubjectCharacterId())) {
            throw new UserRequestException("战斗目标不是合法的其他参战者");
        }
        CocCharacter target = characterMapper.selectById(targetId);
        if (target == null || !Objects.equals(
                target.getRunId(), conversation.getId())) {
            throw new UserRequestException("目标人物卡不存在");
        }
        boolean insertDefense = targetNode.get("insertDefense") != null
                && targetNode.get("insertDefense").asBoolean();
        List<String> options = new ArrayList<>();
        if (targetNode.get("defenseOptions") != null) {
            targetNode.get("defenseOptions").forEach(
                    node -> options.add(node.asText()));
        }
        if (insertDefense
                && (Boolean.TRUE.equals(target.getDead())
                || Boolean.TRUE.equals(target.getDying())
                || Boolean.TRUE.equals(target.getUnconscious())
                || Objects.requireNonNullElse(
                target.getStunnedRemainingRounds(), 0) > 0)) {
            insertDefense = false;
        }
        GroupChatReplyStep defense = defenseForRoute(turn, routeStep);
        if (insertDefense) {
            GroupReplyPlanService.CombatPlanItem controller =
                    toPlanItem(target, defense.getItemOrder());
            defense.setSpeakerType(controller.actorType())
                    .setSpeakerId(controller.actorId())
                    .setSubjectCharacterId(targetId)
                    .setGroupName(options.isEmpty()
                            ? "战斗防守"
                            : "战斗防守：" + String.join(" / ", options))
                    .setStatus(GroupChatConstant.STATUS_PENDING)
                    .setUpdatedAt(LocalDateTime.now());
        } else {
            defense.setSubjectCharacterId(targetId)
                    .setStatus(GroupChatConstant.STATUS_CANCELLED)
                    .setUpdatedAt(LocalDateTime.now());
        }
        stepMapper.updateById(defense);
        return new RouteDecision(targetId, targetName, insertDefense,
                List.copyOf(options),
                root.get("reason") == null
                        ? null : root.get("reason").asText());
    }

    private GroupChatReplyStep defenseForRoute(
            GroupChatTurn turn,
            GroupChatReplyStep routeStep) {
        if (routeStep.getParentStepId() != null) {
            GroupChatReplyStep adjudication = stepMapper.selectById(
                    routeStep.getParentStepId());
            requireAdjudicationRoot(turn, adjudication);
            return appendChild(
                    adjudication,
                    GroupChatConstant.ACTION_COMBAT_DEFENSE,
                    GroupChatConstant.ACTOR_KP,
                    null,
                    null,
                    GroupChatConstant.STATUS_CANCELLED);
        }
        GroupChatReplyStep defense = nextStep(
                turn.getId(), routeStep.getStepNo());
        if (defense == null
                || !GroupChatConstant.ACTION_COMBAT_DEFENSE.equals(
                defense.getActionType())) {
            throw new IllegalStateException("战斗防守占位步骤不存在");
        }
        return defense;
    }

    public String defensePrompt(
            com.me.galchat.groupchat.runtime.GroupActionSpec action) {
        List<GroupChatReplyStep> defenses = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getActionType,
                                GroupChatConstant.ACTION_COMBAT_DEFENSE)
                        .eq(GroupChatReplyStep::getSubjectCharacterId,
                                action.subjectCharacterId())
                        .orderByDesc(GroupChatReplyStep::getId)
                        .last("limit 1"));
        if (defenses == null || defenses.isEmpty()) {
            return "只描述对当前攻击的即时合法防守。";
        }
        GroupChatReplyStep defense = defenses.getFirst();
        GroupChatReplyStep route;
        if (defense.getParentStepId() != null) {
            route = childrenOf(defense.getParentStepId()).stream()
                    .filter(step -> GroupChatConstant
                            .ACTION_COMBAT_REACTION_ROUTE.equals(
                                    step.getActionType()))
                    .filter(step -> step.getStepNo()
                            < defense.getStepNo())
                    .max(Comparator.comparing(
                            GroupChatReplyStep::getStepNo))
                    .orElse(null);
        } else {
            route = previousStep(
                    defense.getTurnId(), defense.getStepNo());
        }
        return "内部路由给出的防守范围：" + messageContent(route)
                + "。只选择并描述其中一个合法反应，不得改选攻击目标。";
    }

    public String adjudicationPrompt(
            Long conversationId,
            com.me.galchat.groupchat.runtime.GroupActionSpec action) {
        List<GroupChatReplyStep> roots = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getActionType,
                                GroupChatConstant
                                        .ACTION_COMBAT_ADJUDICATE)
                        .eq(GroupChatReplyStep::getSubjectCharacterId,
                                action.subjectCharacterId())
                        .orderByDesc(GroupChatReplyStep::getId)
                        .last("limit 1"));
        if (roots == null || roots.isEmpty()) {
            return "读取本主动位的攻击和全部有序子步骤后进行裁定。";
        }
        GroupChatReplyStep adjudication = roots.getFirst();
        List<GroupChatReplyStep> children = childrenOf(
                adjudication.getId());
        GroupChatReplyStep attack = previousRootStep(
                adjudication.getTurnId(), adjudication.getStepNo());
        String attackText = messageContent(attack);
        String childText = orderedChildrenText(children);
        String npcDeathInstruction = "";
        try {
            TrpgCombat combat = requireActiveCombat(
                    conversationService.requireActive(conversationId));
            boolean recorded = false;
            if (combat.getActiveTurnResults() != null) {
                for (tools.jackson.databind.JsonNode result :
                        combat.getActiveTurnResults()) {
                    recorded |= result.get("npcDied") != null
                            && result.get("npcDied").asBoolean();
                }
            }
            if (!recorded && currentNpcDeath(combat)) {
                npcDeathInstruction = """

                        本主动位检测到NPC首次死亡：本次裁定必须明确判断战斗是否结束；若结束，在所有骰点完成后调用markCombatFinished。
                        """;
            }
        } catch (UserRequestException ignored) {
            // Prompt assembly can still proceed from persisted exchange data.
        }
        return """
                本主动位材料：
                攻击：%s
                裁定前有序子步骤：
                %s
                请只根据这些公开行动、当前人物卡状态和骰点裁定。
                %s
                """.formatted(
                attackText, childText,
                npcDeathInstruction);
    }

    private String orderedChildrenText(
            List<GroupChatReplyStep> children) {
        if (children == null || children.isEmpty()) {
            return "无";
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < children.size(); index++) {
            GroupChatReplyStep child = children.get(index);
            if (index > 0) {
                result.append('\n');
            }
            result.append(index + 1)
                    .append(". [")
                    .append(child.getActionType())
                    .append(" / ")
                    .append(child.getStatus())
                    .append("] ")
                    .append(messageContent(child));
        }
        return result.toString();
    }

    @Transactional
    public boolean completeAdjudication(
            GroupConversation conversation,
            GroupChatTurn turn,
            GroupChatReplyStep adjudication,
            GroupChatMessage adjudicationMessage) {
        if (!GroupChatConstant.ACTION_COMBAT_ADJUDICATE.equals(
                adjudication.getActionType())) {
            return false;
        }
        TrpgCombat combat = requireActiveCombat(conversation);
        ArrayNode results = combat.getActiveTurnResults() instanceof ArrayNode array
                ? array.deepCopy() : objectMapper.createArrayNode();
        for (tools.jackson.databind.JsonNode result : results) {
            if (result.get("adjudicationStepId") != null
                    && result.get("adjudicationStepId").asLong()
                    == adjudication.getId()) {
                return Objects.equals(
                        combat.getFinishRequestedStepId(),
                        adjudication.getId());
            }
        }
        List<GroupChatReplyStep> children = childrenOf(
                adjudication.getId());
        GroupChatReplyStep attack;
        GroupChatReplyStep route;
        GroupChatReplyStep defense;
        if (children.isEmpty()) {
            defense = previousStep(
                    turn.getId(), adjudication.getStepNo());
            route = defense == null ? null
                    : previousStep(turn.getId(), defense.getStepNo());
            attack = route == null ? null
                    : previousStep(turn.getId(), route.getStepNo());
        } else {
            attack = previousRootStep(
                    turn.getId(), adjudication.getStepNo());
            route = lastChildOfType(children,
                    GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE);
            defense = lastChildOfType(children,
                    GroupChatConstant.ACTION_COMBAT_DEFENSE);
        }
        ObjectNode result = results.addObject();
        result.put("combatRound", combat.getCurrentRound());
        result.put("activeOrder",
                activeOrder(turn.getId(), adjudication));
        result.put("attackerCharacterId",
                adjudication.getSubjectCharacterId());
        result.put("turnId", turn.getId());
        putStepAndMessage(result, "attack", attack);
        putStepAndMessage(result, "route", route);
        putStepAndMessage(result, "defense", defense);
        ArrayNode childSteps = result.putArray("childSteps");
        children.forEach(child ->
                putChildStep(childSteps.addObject(), child));
        result.put("adjudicationStepId", adjudication.getId());
        result.put("adjudicationMessageId",
                adjudicationMessage.getId());
        result.put("adjudication",
                adjudicationMessage.getContent());
        ArrayNode diceSummaryIds =
                result.putArray("diceSummaryIds");
        toolCallMapper.selectList(
                        new LambdaQueryWrapper<GroupChatToolCall>()
                                .eq(GroupChatToolCall::getReplyStepId,
                                        adjudication.getId())
                                .isNotNull(GroupChatToolCall
                                        ::getDiceRollSummaryId)
                                .orderByAsc(
                                        GroupChatToolCall::getToolStepNo)
                                .orderByAsc(GroupChatToolCall::getId))
                .stream()
                .map(GroupChatToolCall::getDiceRollSummaryId)
                .distinct()
                .forEach(diceSummaryIds::add);
        result.put("completedAt", LocalDateTime.now().toString());
        boolean npcDied = currentNpcDeath(combat);
        result.put("npcDied", npcDied);
        combat.setActiveTurnResults(results)
                .setUpdatedAt(LocalDateTime.now());
        if (combat.getStartSequence() == null && attack != null) {
            GroupChatMessage attackMessage = outputMessage(attack);
            if (attackMessage != null) {
                combat.setStartSequence(
                        attackMessage.getSequenceNo());
            }
        }
        combatMapper.updateById(combat);
        if (!Objects.equals(combat.getFinishRequestedStepId(),
                adjudication.getId())) {
            return false;
        }
        finishCombat(conversation, combat, results);
        return true;
    }

    private GroupChatReplyStep lastChildOfType(
            List<GroupChatReplyStep> children,
            String actionType) {
        return children.stream()
                .filter(step -> actionType.equals(step.getActionType()))
                .max(Comparator.comparing(
                        GroupChatReplyStep::getStepNo))
                .orElse(null);
    }

    private int activeOrder(
            Long turnId,
            GroupChatReplyStep adjudication) {
        List<GroupChatReplyStep> roots = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turnId)
                        .isNull(GroupChatReplyStep::getParentStepId)
                        .eq(GroupChatReplyStep::getActionType,
                                GroupChatConstant
                                        .ACTION_COMBAT_ADJUDICATE)
                        .orderByAsc(GroupChatReplyStep::getStepNo));
        if (roots == null) {
            return 1;
        }
        for (int index = 0; index < roots.size(); index++) {
            if (Objects.equals(
                    roots.get(index).getId(), adjudication.getId())) {
                return index + 1;
            }
        }
        return 1;
    }

    private void putChildStep(
            ObjectNode result,
            GroupChatReplyStep step) {
        result.put("stepId", step.getId());
        result.put("stepNo", step.getStepNo());
        result.put("actionType", step.getActionType());
        result.put("status", step.getStatus());
        if (step.getSubjectCharacterId() != null) {
            result.put("subjectCharacterId",
                    step.getSubjectCharacterId());
        }
        GroupChatMessage message = outputMessage(step);
        if (message != null) {
            result.put("messageId", message.getId());
            result.put("content", message.getContent());
        }
    }

    private void finishCombat(
            GroupConversation conversation,
            TrpgCombat combat,
            ArrayNode results) {
        clearCombatStates(combat);
        String summary = buildSummary(results);
        LocalDateTime now = LocalDateTime.now();
        GroupChatMessage message = new GroupChatMessage()
                .setConversationId(conversation.getId())
                .setSceneId(combat.getSourceSceneId())
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setMessageKind(GroupChatConstant.MESSAGE_COMBAT_RESULT)
                .setVisibility("public")
                .setContent(summary)
                .setSequenceNo(conversationService.nextSequence(
                        conversation.getId()))
                .setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        messageMapper.insert(message);
        combat.setSummary(summary)
                .setEndSequence(message.getSequenceNo())
                .setEndedAt(now)
                .setStatus(GroupChatConstant.COMBAT_STATUS_COMPLETED)
                .setUpdatedAt(now);
        combatMapper.updateById(combat);
        replyPlanService.finishActiveUnderLock(conversation);
        replyPlanService.startPostCombatTransitionUnderLock(
                conversation, combat.getId());
    }

    void clearCombatStates(TrpgCombat combat) {
        if (combat == null || combat.getParticipants() == null
                || !combat.getParticipants().isArray()) {
            return;
        }
        Set<Long> characterIds = new HashSet<>();
        combat.getParticipants().forEach(node -> {
            if (node.get("characterId") != null) {
                characterIds.add(node.get("characterId").asLong());
            }
        });
        if (characterIds.isEmpty()) {
            return;
        }
        List<CocCharacter> cards = characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .in(CocCharacter::getId, characterIds));
        if (cards == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (CocCharacter card : cards) {
            card.setInCover(false)
                    .setCoverActionForfeitPending(false)
                    .setStunnedRemainingRounds(0)
                    .setRestrainedByCharacterId(null)
                    .setMeleeAttackedThisRound(false)
                    .setUpdatedAt(now);
            if (characterMapper.updateById(card) == 0) {
                throw new UserRequestException("战斗人物卡状态清理失败");
            }
        }
    }

    private String buildSummary(ArrayNode results) {
        StringBuilder summary = new StringBuilder("战斗结果：");
        int count = 0;
        for (tools.jackson.databind.JsonNode result : results) {
            String text = result.get("adjudication") == null
                    ? null : result.get("adjudication").asText();
            String compacted = StringUtils.hasText(text)
                    ? text.lines()
                            .map(String::strip)
                            .filter(StringUtils::hasText)
                            .collect(Collectors.joining("\n"))
                    : "";
            if (StringUtils.hasText(compacted)) {
                summary.append(count == 0 ? "\n" : "\n\n")
                        .append(++count)
                        .append(". ").append(compacted);
            }
        }
        if (count == 0) {
            summary.append("KP结束了本场战斗。");
        }
        return summary.toString();
    }

    private boolean currentNpcDeath(TrpgCombat combat) {
        Set<Long> initiallyAliveNpcIds = new HashSet<>();
        combat.getParticipants().forEach(node -> {
            if ("NPC".equals(node.get("cardType").asText())
                    && !node.get("dead").asBoolean()) {
                initiallyAliveNpcIds.add(
                        node.get("characterId").asLong());
            }
        });
        if (initiallyAliveNpcIds.isEmpty()) {
            return false;
        }
        Long dead = characterMapper.selectCount(
                new LambdaQueryWrapper<CocCharacter>()
                        .in(CocCharacter::getId, initiallyAliveNpcIds)
                        .eq(CocCharacter::getDead, true));
        return dead != null && dead > 0;
    }

    private void putStepAndMessage(
            ObjectNode result,
            String prefix,
            GroupChatReplyStep step) {
        if (step == null) {
            return;
        }
        result.put(prefix + "StepId", step.getId());
        if (step.getSubjectCharacterId() != null) {
            result.put(prefix + "CharacterId",
                    step.getSubjectCharacterId());
        }
        GroupChatMessage message = outputMessage(step);
        if (message != null) {
            result.put(prefix + "MessageId", message.getId());
            result.put(prefix + "Content", message.getContent());
        }
    }

    private GroupChatReplyStep previousStep(
            Long turnId, Integer stepNo) {
        List<GroupChatReplyStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turnId)
                        .lt(GroupChatReplyStep::getStepNo, stepNo)
                        .orderByDesc(GroupChatReplyStep::getStepNo)
                        .last("limit 1"));
        return steps == null || steps.isEmpty()
                ? null : steps.getFirst();
    }

    private GroupChatReplyStep previousRootStep(
            Long turnId, Integer stepNo) {
        List<GroupChatReplyStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turnId)
                        .isNull(GroupChatReplyStep::getParentStepId)
                        .lt(GroupChatReplyStep::getStepNo, stepNo)
                        .orderByDesc(GroupChatReplyStep::getStepNo)
                        .last("limit 1"));
        return steps == null || steps.isEmpty()
                ? null : steps.getFirst();
    }

    private GroupChatReplyStep nextStep(
            Long turnId, Integer stepNo) {
        List<GroupChatReplyStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turnId)
                        .gt(GroupChatReplyStep::getStepNo, stepNo)
                        .orderByAsc(GroupChatReplyStep::getStepNo)
                        .last("limit 1"));
        return steps == null || steps.isEmpty()
                ? null : steps.getFirst();
    }

    private String messageContent(GroupChatReplyStep step) {
        GroupChatMessage message = outputMessage(step);
        return message == null || !StringUtils.hasText(
                message.getContent()) ? "无" : message.getContent();
    }

    private GroupChatMessage outputMessage(
            GroupChatReplyStep step) {
        return step == null || step.getOutputMessageId() == null
                ? null : messageMapper.selectById(
                step.getOutputMessageId());
    }

    @Transactional
    public StartResult requestStart(
            Long conversationId,
            Long replyStepId,
            List<String> participantNames,
            String requestedOrderMode,
            List<String> declaredAttackerNames) {
        return requestStart(
                conversationId, replyStepId, participantNames,
                List.of(), requestedOrderMode, declaredAttackerNames);
    }

    @Transactional
    public StartResult requestStart(
            Long conversationId,
            Long replyStepId,
            List<String> participantNames,
            List<KpQuickNpcDTOs.Spec> quickNpcs,
            String requestedOrderMode,
            List<String> declaredAttackerNames) {
        GroupConversation conversation =
                conversationService.requireActive(conversationId);
        requireTrpg(conversation);
        GroupChatReplyStep step = stepMapper.selectById(replyStepId);
        GroupChatTurn turn = step == null
                ? null : turnMapper.selectById(step.getTurnId());
        if (step == null || turn == null
                || !conversationId.equals(turn.getConversationId())
                || !GroupChatConstant.ACTOR_KP.equals(
                step.getSpeakerType())
                || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                turn.getPlanSource())
                || !(GroupChatConstant.ACTION_TRPG_SCENE.equals(
                step.getActionType())
                || GroupChatConstant.ACTION_TRPG_SCENE_INTRO.equals(
                step.getActionType()))) {
            throw new UserRequestException(
                    "只有场景中的 KP 行动可以发起战斗");
        }
        if (conversation.getActiveReplyPlanId() == null) {
            throw new UserRequestException("当前没有活动场景");
        }
        GroupReplyPlan scene = planMapper.selectById(
                conversation.getActiveReplyPlanId());
        if (scene == null
                || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                scene.getSource())
                || !Objects.equals(scene.getId(), turn.getPlanId())) {
            throw new UserRequestException("当前场景与行动轮不一致");
        }
        if (hasOpenCombat(conversationId)) {
            throw new UserRequestException("当前群聊已经存在未结束的战斗");
        }
        String orderMode = normalizeOrderMode(requestedOrderMode);
        List<String> names = normalizeNames(participantNames);
        List<CocCharacter> cards = requireCards(conversationId, names);
        List<KpQuickNpcDTOs.Spec> normalizedQuickNpcs =
                quickNpcTemplateService.validateForRequest(
                        conversationId, quickNpcs, Set.copyOf(names));
        if (names.size() + normalizedQuickNpcs.size() < 2) {
            throw new UserRequestException("战斗至少需要两名参战者");
        }
        Set<Long> declaredAttackerIds = normalizeDeclaredAttackerIds(
                orderMode, declaredAttackerNames, cards);
        LocalDateTime now = LocalDateTime.now();
        TrpgCombat combat = new TrpgCombat()
                .setConversationId(conversationId)
                .setSourceSceneId(turn.getPlanContextId())
                .setStatus(GroupChatConstant.COMBAT_STATUS_START_REQUESTED)
                .setOrderMode(orderMode)
                .setCurrentRound(1)
                .setParticipants(participantSnapshot(
                        cards, declaredAttackerIds))
                .setQuickNpcSpecs(objectMapper.valueToTree(
                        normalizedQuickNpcs))
                .setActiveTurnResults(objectMapper.createArrayNode())
                .setStartRequestedStepId(replyStepId)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        combatMapper.insert(combat);
        List<String> allNames = new ArrayList<>(names);
        normalizedQuickNpcs.stream()
                .map(KpQuickNpcDTOs.Spec::name)
                .forEach(allNames::add);
        return new StartResult(
                combat.getId(), orderMode, List.copyOf(allNames));
    }

    /**
     * Runs after a turn is fully completed while the conversation lock is
     * still held. A request made by a failed KP step is therefore never
     * activated.
     */
    @Transactional
    public boolean finalizeStartAfterTurn(
            GroupConversation conversation,
            GroupChatTurn completedTurn) {
        if (conversation == null || completedTurn == null
                || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                completedTurn.getPlanSource())) {
            return false;
        }
        List<TrpgCombat> requested = combatMapper.selectList(
                new LambdaQueryWrapper<TrpgCombat>()
                        .eq(TrpgCombat::getConversationId,
                                conversation.getId())
                        .eq(TrpgCombat::getStatus,
                                GroupChatConstant
                                        .COMBAT_STATUS_START_REQUESTED)
                        .orderByDesc(TrpgCombat::getId)
                        .last("limit 1"));
        if (requested == null || requested.isEmpty()) {
            return false;
        }
        TrpgCombat combat = requested.getFirst();
        GroupChatReplyStep startStep = stepMapper.selectById(
                combat.getStartRequestedStepId());
        if (startStep == null
                || !Objects.equals(startStep.getTurnId(),
                completedTurn.getId())
                || !GroupChatConstant.STATUS_COMPLETED.equals(
                startStep.getStatus())) {
            return false;
        }
        Set<Long> declaredAttackerIds = declaredAttackerIds(
                combat.getParticipants());
        List<CocCharacter> participants = new ArrayList<>(
                cardsFromParticipantSnapshot(combat));
        participants.addAll(quickNpcTemplateService.materialize(
                combat.getConversationId(),
                quickNpcSpecs(combat.getQuickNpcSpecs())));
        combat.setParticipants(participantSnapshot(
                participants, declaredAttackerIds));
        List<GroupReplyPlanService.CombatPlanItem> order =
                currentOrder(combat);
        replyPlanService.startCombatUnderLock(
                conversation, combat.getId(), 1, order);
        combat.setStatus(GroupChatConstant.COMBAT_STATUS_ACTIVE)
                .setUpdatedAt(LocalDateTime.now());
        combatMapper.updateById(combat);
        return true;
    }

    @Transactional
    public void startNextRoundUnderLock(
            GroupConversation conversation) {
        TrpgCombat combat = requireActiveCombat(conversation);
        clearRoundMeleeAttackStates(combat);
        int nextRound = combat.getCurrentRound() + 1;
        combat.setCurrentRound(nextRound)
                .setUpdatedAt(LocalDateTime.now());
        combatMapper.updateById(combat);
        replyPlanService.replaceCombatRoundUnderLock(
                conversation, nextRound, currentOrder(combat));
    }

    void clearRoundMeleeAttackStates(TrpgCombat combat) {
        if (combat == null || combat.getParticipants() == null
                || !combat.getParticipants().isArray()) {
            return;
        }
        Set<Long> characterIds = new HashSet<>();
        combat.getParticipants().forEach(node -> {
            if (node.get("characterId") != null) {
                characterIds.add(node.get("characterId").asLong());
            }
        });
        if (characterIds.isEmpty()) {
            return;
        }
        List<CocCharacter> cards = characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .in(CocCharacter::getId, characterIds));
        if (cards == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (CocCharacter card : cards) {
            if (!Boolean.TRUE.equals(
                    card.getMeleeAttackedThisRound())) {
                continue;
            }
            card.setMeleeAttackedThisRound(false)
                    .setUpdatedAt(now);
            if (characterMapper.updateById(card) == 0) {
                throw new UserRequestException("近战轮次状态清理失败");
            }
        }
    }

    /**
     * Permanently removes a character's still-pending active slot from the
     * current combat round. Cancelled steps are deliberately never restored;
     * a later recovery only affects the next round generated from card state.
     */
    @Transactional
    public void forfeitCurrentRoundSlot(
            Long conversationId, Long characterId) {
        if (conversationId == null || characterId == null) {
            return;
        }
        List<GroupChatTurn> turns = turnMapper.selectList(
                new LambdaQueryWrapper<GroupChatTurn>()
                        .eq(GroupChatTurn::getConversationId, conversationId)
                        .eq(GroupChatTurn::getPlanSource,
                                GroupChatConstant.PLAN_SOURCE_COMBAT)
                        .in(GroupChatTurn::getStatus, List.of(
                                GroupChatConstant.STATUS_RUNNING,
                                GroupChatConstant.STATUS_WAITING_INPUT,
                                GroupChatConstant.STATUS_WAITING_DICE,
                                GroupChatConstant.STATUS_PAUSED))
                        .orderByDesc(GroupChatTurn::getId)
                        .last("limit 1"));
        if (turns == null || turns.isEmpty()) {
            return;
        }
        List<GroupChatReplyStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId,
                                turns.getFirst().getId())
                        .orderByAsc(GroupChatReplyStep::getItemOrder));
        if (steps == null || steps.isEmpty()) {
            return;
        }
        GroupChatReplyStep attack = steps.stream()
                .filter(step -> GroupChatConstant.ACTION_COMBAT_ATTACK.equals(
                        step.getActionType()))
                .filter(step -> Objects.equals(
                        characterId, step.getSubjectCharacterId()))
                .filter(step -> GroupChatConstant.STATUS_PENDING.equals(
                        step.getStatus()))
                .findFirst()
                .orElse(null);
        if (attack == null || attack.getItemOrder() == null) {
            return;
        }
        int first = attack.getItemOrder();
        int nextSlot = steps.stream()
                .filter(step -> step.getParentStepId() == null)
                .filter(step -> GroupChatConstant.ACTION_COMBAT_ATTACK
                        .equals(step.getActionType()))
                .map(GroupChatReplyStep::getItemOrder)
                .filter(Objects::nonNull)
                .filter(order -> order > first)
                .min(Integer::compareTo)
                .orElse(Integer.MAX_VALUE);
        LocalDateTime now = LocalDateTime.now();
        steps.stream()
                .filter(step -> step.getItemOrder() != null
                        && step.getItemOrder() >= first
                        && step.getItemOrder() < nextSlot)
                .filter(step -> GroupChatConstant.STATUS_PENDING.equals(
                        step.getStatus()))
                .forEach(step -> {
                    step.setStatus(GroupChatConstant.STATUS_CANCELLED)
                            .setUpdatedAt(now);
                    stepMapper.updateById(step);
                });
    }

    @Transactional
    public MarkFinishedResult requestFinish(
            Long conversationId,
            Long replyStepId) {
        GroupConversation conversation =
                conversationService.requireActive(conversationId);
        TrpgCombat combat = requireActiveCombat(conversation);
        GroupChatReplyStep step = stepMapper.selectById(replyStepId);
        if (step == null
                || !GroupChatConstant.ACTOR_KP.equals(
                step.getSpeakerType())
                || !GroupChatConstant.ACTION_COMBAT_ADJUDICATE.equals(
                step.getActionType())) {
            throw new UserRequestException(
                    "只有战斗裁定中的 KP 可以标记战斗结束");
        }
        GroupChatTurn turn = turnMapper.selectById(step.getTurnId());
        if (turn == null
                || !conversationId.equals(turn.getConversationId())
                || !GroupChatConstant.PLAN_SOURCE_COMBAT.equals(
                turn.getPlanSource())
                || !Objects.equals(turn.getPlanContextId(),
                combat.getId())) {
            throw new UserRequestException("战斗裁定上下文不一致");
        }
        combat.setFinishRequestedStepId(replyStepId)
                .setUpdatedAt(LocalDateTime.now());
        combatMapper.updateById(combat);
        return new MarkFinishedResult(
                combat.getId(),
                "已标记；请继续输出完整裁定和战斗收束。");
    }

    @Transactional
    public void clearControlMarkersForRetry(Long replyStepId) {
        if (replyStepId == null) {
            return;
        }
        List<TrpgCombat> rows = combatMapper.selectList(
                new LambdaQueryWrapper<TrpgCombat>()
                        .and(wrapper -> wrapper
                                .eq(TrpgCombat::getStartRequestedStepId,
                                        replyStepId)
                                .or()
                                .eq(TrpgCombat::getFinishRequestedStepId,
                                        replyStepId)));
        for (TrpgCombat combat : rows) {
            if (Objects.equals(combat.getStartRequestedStepId(),
                    replyStepId)
                    && GroupChatConstant
                    .COMBAT_STATUS_START_REQUESTED.equals(
                    combat.getStatus())) {
                combat.setStatus(
                        GroupChatConstant.COMBAT_STATUS_CANCELLED);
            }
            if (Objects.equals(combat.getFinishRequestedStepId(),
                    replyStepId)) {
                combat.setFinishRequestedStepId(null);
                combatMapper.update(null,
                        new LambdaUpdateWrapper<TrpgCombat>()
                                .eq(TrpgCombat::getId,
                                        combat.getId())
                                .set(TrpgCombat
                                                ::getFinishRequestedStepId,
                                        null)
                                .set(TrpgCombat::getUpdatedAt,
                                        LocalDateTime.now()));
                if (!Objects.equals(combat.getStartRequestedStepId(),
                        replyStepId)
                        || !GroupChatConstant
                        .COMBAT_STATUS_START_REQUESTED.equals(
                        combat.getStatus())) {
                    continue;
                }
            }
            combat.setUpdatedAt(LocalDateTime.now());
            combatMapper.updateById(combat);
        }
    }

    public TrpgCombat requireActiveCombat(
            GroupConversation conversation) {
        if (conversation == null
                || conversation.getActiveReplyPlanId() == null) {
            throw new UserRequestException("当前没有活动战斗");
        }
        GroupReplyPlan plan = planMapper.selectById(
                conversation.getActiveReplyPlanId());
        if (plan == null
                || !GroupChatConstant.PLAN_SOURCE_COMBAT.equals(
                plan.getSource())) {
            throw new UserRequestException("当前没有活动战斗");
        }
        TrpgCombat combat = combatMapper.selectById(plan.getContextId());
        if (combat == null
                || !GroupChatConstant.COMBAT_STATUS_ACTIVE.equals(
                combat.getStatus())
                || !Objects.equals(combat.getConversationId(),
                conversation.getId())) {
            throw new UserRequestException("活动战斗记录不存在");
        }
        return combat;
    }

    private List<GroupReplyPlanService.CombatPlanItem> currentOrder(
            TrpgCombat combat) {
        List<CocCharacter> cards = characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId,
                                combat.getConversationId()));
        Set<Long> ids = new HashSet<>();
        Set<Long> declaredAttackerIds = new HashSet<>();
        combat.getParticipants().forEach(node -> {
            long characterId = node.get("characterId").asLong();
            ids.add(characterId);
            if (node.path("declaredFirstRoundAttack")
                    .asBoolean(false)) {
                declaredAttackerIds.add(characterId);
            }
        });
        Comparator<CocCharacter> dexOrder = Comparator
                .comparing((CocCharacter card) ->
                                card.getDex() == null
                                        ? Integer.MIN_VALUE : card.getDex(),
                        Comparator.reverseOrder())
                .thenComparing(CocCharacter::getId);
        Comparator<CocCharacter> order = dexOrder;
        if (combat.getCurrentRound() == 1
                && GroupChatConstant
                .COMBAT_ORDER_INVESTIGATORS_FIRST.equals(
                combat.getOrderMode())) {
            order = Comparator
                    .comparing((CocCharacter card) ->
                            declaredAttackerIds.contains(card.getId())
                                    ? 0 : 1)
                    .thenComparing(dexOrder);
        }
        return cards.stream()
                .filter(card -> ids.contains(card.getId()))
                .filter(card -> !Boolean.TRUE.equals(card.getDead()))
                .filter(card -> !Boolean.TRUE.equals(card.getDying()))
                .sorted(order)
                .map(card -> toPlanItem(card, 0))
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(ArrayList::new),
                        ordered -> {
                            for (int index = 0;
                                 index < ordered.size(); index++) {
                                GroupReplyPlanService.CombatPlanItem item =
                                        ordered.get(index);
                                ordered.set(index,
                                        new GroupReplyPlanService
                                                .CombatPlanItem(
                                                item.actorType(),
                                                item.actorId(),
                                                item.subjectCharacterId(),
                                                item.subjectCharacterName(),
                                                index + 1));
                            }
                            return List.copyOf(ordered);
                        }));
    }

    private GroupReplyPlanService.CombatPlanItem toPlanItem(
            CocCharacter card, int order) {
        return switch (card.getActorType()) {
            case "PLAYER" -> new GroupReplyPlanService.CombatPlanItem(
                    GroupChatConstant.ACTOR_USER, card.getId(),
                    card.getId(), card.getName(), order);
            case "BOT" -> new GroupReplyPlanService.CombatPlanItem(
                    GroupChatConstant.ACTOR_CHARACTER,
                    card.getParticipantId(), card.getId(),
                    card.getName(), order);
            case "NPC" -> new GroupReplyPlanService.CombatPlanItem(
                    GroupChatConstant.ACTOR_KP, null,
                    card.getId(), card.getName(), order);
            default -> throw new UserRequestException(
                    "不支持的战斗人物卡类型：" + card.getActorType());
        };
    }

    private ArrayNode participantSnapshot(
            List<CocCharacter> cards,
            Set<Long> declaredAttackerIds) {
        ArrayNode result = objectMapper.createArrayNode();
        for (CocCharacter card : cards) {
            GroupReplyPlanService.CombatPlanItem controller =
                    toPlanItem(card, 0);
            ObjectNode node = result.addObject();
            node.put("characterId", card.getId());
            node.put("name", card.getName());
            node.put("cardType", card.getActorType());
            node.put("declaredFirstRoundAttack",
                    declaredAttackerIds.contains(card.getId()));
            node.put("controllerType", controller.actorType());
            if (controller.actorId() != null) {
                node.put("controllerId", controller.actorId());
            }
            if (card.getParticipantId() != null) {
                node.put("participantId", card.getParticipantId());
            }
            putNullable(node, "dex", card.getDex());
            putNullable(node, "hpCurrent", card.getHpCurrent());
            putNullable(node, "sanCurrent", card.getSanCurrent());
            node.put("majorWound",
                    Boolean.TRUE.equals(card.getMajorWound()));
            node.put("unconscious",
                    Boolean.TRUE.equals(card.getUnconscious()));
            node.put("dying", Boolean.TRUE.equals(card.getDying()));
            node.put("dead", Boolean.TRUE.equals(card.getDead()));
        }
        return result;
    }

    private Set<Long> declaredAttackerIds(JsonNode participants) {
        if (participants == null || !participants.isArray()) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        participants.forEach(node -> {
            if (node.path("declaredFirstRoundAttack")
                    .asBoolean(false)
                    && node.get("characterId") != null) {
                ids.add(node.get("characterId").asLong());
            }
        });
        return Set.copyOf(ids);
    }

    private List<CocCharacter> cardsFromParticipantSnapshot(
            TrpgCombat combat) {
        if (combat.getParticipants() == null
                || !combat.getParticipants().isArray()
                || combat.getParticipants().isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        combat.getParticipants().forEach(node -> {
            if (node.get("characterId") != null) {
                ids.add(node.get("characterId").asLong());
            }
        });
        if (ids.isEmpty()) {
            return List.of();
        }
        List<CocCharacter> cards = characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId,
                                combat.getConversationId())
                        .in(CocCharacter::getId, ids));
        Map<Long, CocCharacter> byId = cards.stream().collect(
                Collectors.toMap(
                        CocCharacter::getId,
                        Function.identity(),
                        (first, ignored) -> first));
        List<CocCharacter> ordered = new ArrayList<>();
        for (Long id : ids) {
            CocCharacter card = byId.get(id);
            if (card == null) {
                throw new UserRequestException(
                        "待激活战斗的参战人物卡不存在：" + id);
            }
            ordered.add(card);
        }
        return List.copyOf(ordered);
    }

    private List<KpQuickNpcDTOs.Spec> quickNpcSpecs(JsonNode specs) {
        if (specs == null || !specs.isArray() || specs.isEmpty()) {
            return List.of();
        }
        List<KpQuickNpcDTOs.Spec> result = new ArrayList<>();
        specs.forEach(node -> result.add(new KpQuickNpcDTOs.Spec(
                node.path("name").asText(null),
                node.path("strength").asText(null),
                node.path("weapon").asText(null))));
        return List.copyOf(result);
    }

    private Set<Long> normalizeDeclaredAttackerIds(
            String orderMode,
            List<String> rawNames,
            List<CocCharacter> participants) {
        if (rawNames == null || rawNames.isEmpty()) {
            return Set.of();
        }
        if (!GroupChatConstant.COMBAT_ORDER_INVESTIGATORS_FIRST.equals(
                orderMode)) {
            throw new UserRequestException(
                    "只有 INVESTIGATORS_FIRST 可以指定提前声明攻击者");
        }
        var byName = participants.stream().collect(Collectors.toMap(
                CocCharacter::getName, Function.identity()));
        Set<String> uniqueNames = new HashSet<>();
        Set<Long> result = new HashSet<>();
        for (String rawName : rawNames) {
            if (!StringUtils.hasText(rawName)) {
                throw new UserRequestException(
                        "提前声明攻击者名称不能为空");
            }
            String name = rawName.trim();
            if (!uniqueNames.add(name)) {
                throw new UserRequestException(
                        "提前声明攻击者不能重复：" + name);
            }
            CocCharacter card = byName.get(name);
            if (card == null) {
                throw new UserRequestException(
                        "提前声明攻击者不在参战名单中：" + name);
            }
            if ("NPC".equals(card.getActorType())) {
                throw new UserRequestException(
                        "只有调查员可以提前声明攻击：" + name);
            }
            result.add(card.getId());
        }
        return Set.copyOf(result);
    }

    private void putNullable(
            ObjectNode node, String field, Integer value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private List<CocCharacter> requireCards(
            Long runId, List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<CocCharacter> cards = characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId, runId)
                        .in(CocCharacter::getName, names));
        var byName = cards.stream().collect(Collectors.toMap(
                CocCharacter::getName, Function.identity(),
                (first, ignored) -> first));
        List<CocCharacter> ordered = new ArrayList<>();
        for (String name : names) {
            CocCharacter card = byName.get(name);
            if (card == null) {
                throw new UserRequestException(
                        "参战人物卡不存在：" + name);
            }
            ordered.add(card);
        }
        return List.copyOf(ordered);
    }

    private List<String> normalizeNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String raw : names) {
            if (!StringUtils.hasText(raw)) {
                throw new UserRequestException("参战人物名称不能为空");
            }
            String name = raw.trim();
            if (!unique.add(name)) {
                throw new UserRequestException(
                        "参战人物不能重复：" + name);
            }
            result.add(name);
        }
        return List.copyOf(result);
    }

    private String normalizeOrderMode(String mode) {
        String normalized = StringUtils.hasText(mode)
                ? mode.trim().toUpperCase(Locale.ROOT)
                : GroupChatConstant.COMBAT_ORDER_DEX;
        if (!Set.of(
                GroupChatConstant.COMBAT_ORDER_DEX,
                GroupChatConstant.COMBAT_ORDER_INVESTIGATORS_FIRST)
                .contains(normalized)) {
            throw new UserRequestException(
                    "战斗顺序仅支持 DEX 或 INVESTIGATORS_FIRST");
        }
        return normalized;
    }

    private boolean hasOpenCombat(Long conversationId) {
        Long count = combatMapper.selectCount(
                new LambdaQueryWrapper<TrpgCombat>()
                        .eq(TrpgCombat::getConversationId,
                                conversationId)
                        .in(TrpgCombat::getStatus,
                                GroupChatConstant
                                        .COMBAT_STATUS_START_REQUESTED,
                                GroupChatConstant.COMBAT_STATUS_ACTIVE));
        return count != null && count > 0;
    }

    private void requireTrpg(GroupConversation conversation) {
        if (!GroupChatConstant.MODE_TRPG.equals(
                conversation.getMode())) {
            throw new UserRequestException("只有 TRPG 群聊支持战斗");
        }
    }

    public record StartResult(
            Long combatId,
            String orderMode,
            List<String> participants) {
    }

    public record MarkFinishedResult(
            Long combatId,
            String message) {
    }

    public record RouteDecision(
            Long targetCharacterId,
            String targetName,
            boolean insertDefense,
            List<String> defenseOptions,
            String reason) {
    }
}

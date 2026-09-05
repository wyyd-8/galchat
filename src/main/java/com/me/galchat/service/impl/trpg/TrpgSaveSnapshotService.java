package com.me.galchat.service.impl.trpg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.GroupChatAgentDecision;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.po.GroupTurnCheckpoint;
import com.me.galchat.domain.po.TrpgCombat;
import com.me.galchat.domain.po.TrpgRuntimeChildScene;
import com.me.galchat.domain.po.TrpgInvestigatorSuspension;
import com.me.galchat.domain.po.TrpgWeaponStash;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.CocCharacterSkillMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.DiceRollResultMapper;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import com.me.galchat.mapper.GroupChatAgentDecisionMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.GroupTurnCheckpointMapper;
import com.me.galchat.mapper.TrpgCombatMapper;
import com.me.galchat.mapper.TrpgRuntimeChildSceneMapper;
import com.me.galchat.mapper.TrpgInvestigatorSuspensionMapper;
import com.me.galchat.mapper.TrpgSaveRestoreMapper;
import com.me.galchat.mapper.TrpgWeaponStashMapper;
import com.me.galchat.mapper.VectorStoreCleanupMapper;
import com.me.galchat.service.ITrpgRedisStateService;
import com.me.galchat.service.ITrpgSaveSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrpgSaveSnapshotService implements ITrpgSaveSnapshotService {

    private static final Set<String> RESTORABLE_TURN_STATUSES = Set.of(
            GroupChatConstant.STATUS_WAITING_INPUT,
            GroupChatConstant.STATUS_PAUSED,
            GroupChatConstant.STATUS_WAITING_DICE,
            GroupChatConstant.STATUS_FAILED,
            GroupChatConstant.STATUS_BLOCKED);

    private final TrpgSaveRestoreMapper restoreMapper;
    private final GroupConversationMapper conversationMapper;
    private final GroupReplyPlanMapper planMapper;
    private final GroupReplyPlanItemMapper planItemMapper;
    private final TrpgRuntimeChildSceneMapper runtimeChildSceneMapper;
    private final CocCharacterMapper characterMapper;
    private final CocCharacterProfileMapper profileMapper;
    private final CocCharacterSkillMapper skillMapper;
    private final CocCharacterWeaponMapper weaponMapper;
    private final TrpgWeaponStashMapper weaponStashMapper;
    private final TrpgCombatMapper combatMapper;
    private final GroupTurnCheckpointMapper checkpointMapper;
    private final GroupChatTurnMapper turnMapper;
    private final GroupChatReplyStepMapper stepMapper;
    private final GroupChatMessageMapper messageMapper;
    private final GroupChatToolCallMapper toolCallMapper;
    private final GroupChatAgentDecisionMapper decisionMapper;
    private final DiceRollSummaryMapper diceSummaryMapper;
    private final DiceRollResultMapper diceResultMapper;
    private final ITrpgRedisStateService redisStateService;
    private final VectorStoreCleanupMapper vectorCleanupMapper;
    private TrpgInvestigatorSuspensionMapper suspensionMapper;

    @Autowired(required = false)
    void setSuspensionMapper(
            TrpgInvestigatorSuspensionMapper suspensionMapper) {
        this.suspensionMapper = suspensionMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public TrpgSaveSnapshotDTO capture(GroupConversation conversation) {
        requireTrpgConversation(conversation);
        Long conversationId = conversation.getId();
        List<GroupReplyPlan> plans = planMapper.selectList(
                new LambdaQueryWrapper<GroupReplyPlan>()
                        .eq(GroupReplyPlan::getConversationId, conversationId)
                        .orderByAsc(GroupReplyPlan::getId));
        List<Long> planIds = plans.stream()
                .map(GroupReplyPlan::getId)
                .filter(Objects::nonNull)
                .toList();
        List<GroupReplyPlanItem> planItems = planIds.isEmpty()
                ? List.of()
                : planItemMapper.selectList(
                        new LambdaQueryWrapper<GroupReplyPlanItem>()
                                .in(GroupReplyPlanItem::getPlanId, planIds)
                                .orderByAsc(GroupReplyPlanItem::getId));
        List<CocCharacter> characters = characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId, conversationId)
                        .orderByAsc(CocCharacter::getId));
        List<Long> characterIds = characters.stream()
                .map(CocCharacter::getId)
                .filter(Objects::nonNull)
                .toList();
        List<Long> sceneIds = sceneIds(plans);
        TrpgSaveSnapshotDTO snapshot = new TrpgSaveSnapshotDTO()
                .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                .setConversationId(conversationId)
                .setUserWorldId(conversation.getUserWorldId())
                .setWorldId(conversation.getWorldId())
                .setModuleId(conversation.getModuleId())
                .setCursors(normalizeCursors(
                        restoreMapper.selectCursors(conversationId)))
                .setConversationState(conversationState(conversation))
                .setReplyPlans(plans)
                .setReplyPlanItems(planItems)
                .setRuntimeChildScenes(
                        runtimeChildSceneMapper.selectList(
                                new LambdaQueryWrapper<
                                        TrpgRuntimeChildScene>()
                                        .eq(TrpgRuntimeChildScene::
                                                        getConversationId,
                                                conversationId)
                                        .orderByAsc(
                                                TrpgRuntimeChildScene::
                                                        getPlanId)))
                .setInvestigatorSuspensions(suspensionMapper == null
                        ? List.of()
                        : suspensionMapper.selectList(
                                new LambdaQueryWrapper<
                                        TrpgInvestigatorSuspension>()
                                        .eq(TrpgInvestigatorSuspension::
                                                        getConversationId,
                                                conversationId)
                                        .orderByAsc(
                                                TrpgInvestigatorSuspension::
                                                        getId)))
                .setCharacters(characters)
                .setCharacterQuickNotes(characterQuickNotes(characters))
                .setCharacterProfiles(characterIds.isEmpty() ? List.of()
                        : profileMapper.selectList(
                                new LambdaQueryWrapper<CocCharacterProfile>()
                                        .in(CocCharacterProfile::getCharacterId, characterIds)
                                        .orderByAsc(CocCharacterProfile::getId)))
                .setCharacterSkills(characterIds.isEmpty() ? List.of()
                        : skillMapper.selectList(
                                new LambdaQueryWrapper<CocCharacterSkill>()
                                        .in(CocCharacterSkill::getCharacterId, characterIds)
                                        .orderByAsc(CocCharacterSkill::getId)))
                .setCharacterWeapons(characterIds.isEmpty() ? List.of()
                        : weaponMapper.selectList(
                                new LambdaQueryWrapper<CocCharacterWeapon>()
                                        .in(CocCharacterWeapon::getCharacterId, characterIds)
                                        .orderByAsc(CocCharacterWeapon::getId)))
                .setWeaponStash(weaponStashMapper.selectList(
                        new LambdaQueryWrapper<TrpgWeaponStash>()
                                .eq(TrpgWeaponStash::getRunId,
                                        conversationId)
                                .orderByAsc(
                                        TrpgWeaponStash::getWeaponId)))
                .setCombats(combatMapper.selectList(
                        new LambdaQueryWrapper<TrpgCombat>()
                                .eq(TrpgCombat::getConversationId, conversationId)
                                .orderByAsc(TrpgCombat::getId)))
                .setRestorableTurns(restorableTurns(conversationId))
                .setCheckpoint(checkpointMapper.selectById(conversationId))
                .setRedisState(redisStateService.capture(
                        conversationId, sceneIds));
        validate(conversation, snapshot);
        return snapshot;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreDatabase(
            GroupConversation conversation, TrpgSaveSnapshotDTO snapshot) {
        validate(conversation, snapshot);
        Long conversationId = conversation.getId();
        TrpgSaveSnapshotDTO.CursorSnapshot cursors = snapshot.getCursors();

        vectorCleanupMapper.deleteGroupTopicsByConversationAfterTopicId(
                conversationId, cursors.getMaxTopicId());
        vectorCleanupMapper.deleteTrpgTurnsByConversationAfterTurnId(
                conversationId, cursors.getMaxTurnId());
        List<Long> restoredTurnIds = safe(snapshot.getRestorableTurns())
                .stream()
                .map(TrpgSaveSnapshotDTO.RestorableTurnSnapshot::getTurn)
                .map(GroupChatTurn::getId)
                .toList();
        if (!restoredTurnIds.isEmpty()) {
            vectorCleanupMapper.deleteTrpgTurnsByConversationAndTurnIds(
                    conversationId, restoredTurnIds);
        }
        restoreMapper.deleteAgentDecisionsAfter(
                conversationId, cursors.getMaxAgentDecisionId());
        restoreMapper.deleteToolCallsAfter(
                conversationId, cursors.getMaxToolCallId());
        restoreMapper.deleteMessagesAfter(
                conversationId, cursors.getMaxMessageId());
        restoreMapper.deleteReplyStepsAfter(
                conversationId, cursors.getMaxReplyStepId());
        restoreMapper.deleteTurnsAfter(
                conversationId, cursors.getMaxTurnId());
        restoreMapper.deleteContextSummariesAfter(
                conversationId, cursors.getMaxContextSummaryId());
        restoreMapper.deleteTopicsAfter(
                conversationId, cursors.getMaxTopicId());
        restoreMapper.deleteDiceResultsAfter(
                conversationId,
                cursors.getMaxDiceSummaryId(),
                cursors.getMaxDiceResultId());
        restoreMapper.deleteDiceSummariesAfter(
                conversationId, cursors.getMaxDiceSummaryId());
        restoreRestorableTurns(snapshot.getRestorableTurns());
        restorePlans(conversationId, snapshot);
        restoreCharacters(conversationId, snapshot);
        restoreSuspensions(conversationId, snapshot);
        restoreWeaponStash(conversationId, snapshot.getWeaponStash());
        restoreCombats(conversationId, snapshot.getCombats());
        restoreCheckpoint(conversationId, snapshot.getCheckpoint());
        restoreConversation(conversation, snapshot.getConversationState());
    }

    @Override
    public void restoreDerivedState(
            GroupConversation conversation, TrpgSaveSnapshotDTO snapshot) {
        requireTrpgConversation(conversation);
        redisStateService.restore(
                conversation.getId(), snapshot.getRedisState());
    }

    private void validate(
            GroupConversation conversation, TrpgSaveSnapshotDTO snapshot) {
        requireTrpgConversation(conversation);
        if (snapshot == null
                || !Objects.equals(snapshot.getFormatVersion(),
                TrpgSaveServiceImpl.FORMAT_VERSION)
                || !Objects.equals(snapshot.getConversationId(), conversation.getId())
                || !Objects.equals(snapshot.getUserWorldId(), conversation.getUserWorldId())
                || !Objects.equals(snapshot.getWorldId(), conversation.getWorldId())
                || !Objects.equals(snapshot.getModuleId(), conversation.getModuleId())) {
            throw new UserRequestException("跑团存档身份信息不一致");
        }
        validateCursors(snapshot.getCursors());
        List<GroupReplyPlan> plans = safe(snapshot.getReplyPlans());
        Set<Long> planIds = new HashSet<>();
        for (GroupReplyPlan plan : plans) {
            if (plan == null || plan.getId() == null
                    || !Objects.equals(plan.getConversationId(), conversation.getId())
                    || !StringUtils.hasText(plan.getExecutionKey())
                    || !StringUtils.hasText(plan.getDisplayName())
                    || !planIds.add(plan.getId())) {
                throw new UserRequestException("跑团回复计划存档不合法");
            }
        }
        for (GroupReplyPlan plan : plans) {
            requirePlanReference(plan.getParentPlanId(), planIds);
            requirePlanReference(plan.getResumePlanId(), planIds);
            requirePlanReference(plan.getNextPlanId(), planIds);
        }
        Map<Long, GroupReplyPlan> planById = plans.stream()
                .collect(Collectors.toMap(
                        GroupReplyPlan::getId,
                        Function.identity()));
        for (TrpgRuntimeChildScene runtimeScene :
                safe(snapshot.getRuntimeChildScenes())) {
            GroupReplyPlan plan = runtimeScene == null
                    ? null : planById.get(runtimeScene.getPlanId());
            if (plan == null
                    || plan.getParentPlanId() == null
                    || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                    plan.getSource())
                    || !Objects.equals(runtimeScene.getConversationId(),
                    snapshot.getConversationId())
                    || !StringUtils.hasText(
                    runtimeScene.getSceneName())
                    || runtimeScene.getCreatedStepId() == null) {
                throw new UserRequestException(
                        "跑团动态子场景存档不合法");
            }
        }
        TrpgSaveSnapshotDTO.ConversationStateSnapshot state =
                snapshot.getConversationState();
        if (state == null || state.getActiveReplyPlanId() != null
                && !planIds.contains(state.getActiveReplyPlanId())) {
            throw new UserRequestException("跑团活动回复计划存档不合法");
        }
        Set<Long> characterIds = new HashSet<>();
        for (CocCharacter character : safe(snapshot.getCharacters())) {
            if (character == null || character.getId() == null
                    || !Objects.equals(character.getRunId(), conversation.getId())
                    || !characterIds.add(character.getId())) {
                throw new UserRequestException("跑团人物卡存档不合法");
            }
        }
        for (GroupReplyPlanItem item : safe(snapshot.getReplyPlanItems())) {
            if (item == null || item.getId() == null
                    || !planIds.contains(item.getPlanId())
                    || item.getSubjectCharacterId() != null
                    && !characterIds.contains(
                    item.getSubjectCharacterId())
                    || item.getSubjectCharacterId() != null
                    && !StringUtils.hasText(
                    item.getSubjectCharacterName())
                    || item.getSubjectCharacterId() == null
                    && StringUtils.hasText(
                    item.getSubjectCharacterName())) {
                throw new UserRequestException("跑团回复计划项目存档不合法");
            }
            GroupReplyPlan itemPlan = planById.get(item.getPlanId());
            boolean investigator = GroupChatConstant.ACTOR_USER.equals(
                    item.getActorType())
                    || GroupChatConstant.ACTOR_CHARACTER.equals(
                    item.getActorType());
            if (GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                    itemPlan.getSource())
                    && investigator
                    && item.getSubjectCharacterId() == null) {
                throw new UserRequestException(
                        "跑团场景计划项目缺少人物卡");
            }
        }
        Set<String> suspensionStates = Set.of(
                TrpgInvestigatorSuspension.STATE_SUSPENDED,
                TrpgInvestigatorSuspension.STATE_RECOVERY_QUEUED,
                TrpgInvestigatorSuspension.STATE_REENTRY_PENDING);
        Set<Long> suspendedCharacters = new HashSet<>();
        for (TrpgInvestigatorSuspension suspension :
                safe(snapshot.getInvestigatorSuspensions())) {
            if (suspension == null || suspension.getId() == null
                    || !Objects.equals(suspension.getConversationId(),
                            conversation.getId())
                    || !characterIds.contains(
                            suspension.getSubjectCharacterId())
                    || !suspendedCharacters.add(
                            suspension.getSubjectCharacterId())
                    || !suspensionStates.contains(suspension.getState())
                    || !StringUtils.hasText(
                            suspension.getSuspensionContext())
                    || suspension.getOriginContextId() == null
                    || suspension.getRecoveryPlanId() != null
                    && !planIds.contains(
                            suspension.getRecoveryPlanId())) {
                throw new UserRequestException(
                        "跑团调查员剧情悬置存档不合法");
            }
        }
        validateCharacterChildren(snapshot, characterIds);
        validateWeaponStash(snapshot, characterIds);
        if (!characterIds.containsAll(
                safeMap(snapshot.getCharacterQuickNotes()).keySet())) {
            throw new UserRequestException("跑团人物卡速记存档不合法");
        }
        for (TrpgCombat combat : safe(snapshot.getCombats())) {
            if (combat == null || combat.getId() == null
                    || !Objects.equals(combat.getConversationId(), conversation.getId())) {
                throw new UserRequestException("跑团战斗存档不合法");
            }
        }
        GroupTurnCheckpoint checkpoint = snapshot.getCheckpoint();
        if (checkpoint != null
                && !Objects.equals(checkpoint.getConversationId(), conversation.getId())) {
            throw new UserRequestException("跑团行动轮检查点存档不合法");
        }
        validateRestorableTurns(snapshot, characterIds);
    }

    private void validateRestorableTurns(
            TrpgSaveSnapshotDTO snapshot, Set<Long> characterIds) {
        TrpgSaveSnapshotDTO.CursorSnapshot cursors = snapshot.getCursors();
        Set<Long> turnIds = new HashSet<>();
        Set<Long> stepIds = new HashSet<>();
        Set<Long> messageIds = new HashSet<>();
        Set<Long> toolCallIds = new HashSet<>();
        Set<Long> decisionIds = new HashSet<>();
        Set<Long> diceSummaryIds = new HashSet<>();
        Set<Long> diceResultIds = new HashSet<>();
        for (TrpgSaveSnapshotDTO.RestorableTurnSnapshot turnSnapshot
                : safe(snapshot.getRestorableTurns())) {
            GroupChatTurn turn = turnSnapshot == null
                    ? null : turnSnapshot.getTurn();
            if (turn == null
                    || !validId(turn.getId(), cursors.getMaxTurnId(), turnIds)
                    || !Objects.equals(turn.getConversationId(), snapshot.getConversationId())
                    || !RESTORABLE_TURN_STATUSES.contains(turn.getStatus())) {
                throw new UserRequestException("跑团可恢复轮次存档不合法");
            }
            Set<Long> currentStepIds = new HashSet<>();
            Set<Long> currentMessageIds = new HashSet<>();
            for (GroupChatReplyStep step : safe(turnSnapshot.getReplySteps())) {
                if (step == null
                        || !validId(step.getId(), cursors.getMaxReplyStepId(), stepIds)
                        || !Objects.equals(step.getTurnId(), turn.getId())) {
                    throw new UserRequestException("跑团回复步骤存档不合法");
                }
                currentStepIds.add(step.getId());
                Long subjectCharacterId = step.getSubjectCharacterId();
                if (subjectCharacterId != null
                        && !characterIds.contains(subjectCharacterId)) {
                    throw new UserRequestException("跑团回复步骤引用了未知人物卡");
                }
            }
            for (GroupChatMessage message : safe(turnSnapshot.getMessages())) {
                if (message == null
                        || !validId(message.getId(), cursors.getMaxMessageId(), messageIds)
                        || !Objects.equals(message.getConversationId(), snapshot.getConversationId())
                        || !Objects.equals(message.getTurnId(), turn.getId())
                        || message.getReplyStepId() != null
                        && !currentStepIds.contains(message.getReplyStepId())) {
                    throw new UserRequestException("跑团轮次消息存档不合法");
                }
                currentMessageIds.add(message.getId());
            }
            for (GroupChatReplyStep step
                    : safe(turnSnapshot.getReplySteps())) {
                if (step.getParentStepId() != null
                        && (!currentStepIds.contains(step.getParentStepId())
                        || !currentStepIds.contains(step.getRootStepId())
                        || step.getInteractionSeq() == null
                        || step.getInteractionSeq() <= 0
                        || !StringUtils.hasText(
                        step.getInteractionType()))) {
                    throw new UserRequestException(
                            "跑团交互步骤存档不合法");
                }
                if (step.getPromptMessageId() != null
                        && !currentMessageIds.contains(
                        step.getPromptMessageId())) {
                    throw new UserRequestException(
                            "跑团交互步骤引用了未知问题消息");
                }
            }
            Set<Long> currentSummaryIds = new HashSet<>();
            for (DiceRollSummary summary : safe(turnSnapshot.getDiceSummaries())) {
                if (summary == null
                        || !validId(summary.getId(), cursors.getMaxDiceSummaryId(), diceSummaryIds)
                        || !Objects.equals(summary.getConversationId(), snapshot.getConversationId())) {
                    throw new UserRequestException("跑团骰点概要存档不合法");
                }
                currentSummaryIds.add(summary.getId());
            }
            for (GroupChatToolCall toolCall : safe(turnSnapshot.getToolCalls())) {
                if (toolCall == null
                        || !validId(toolCall.getId(), cursors.getMaxToolCallId(), toolCallIds)
                        || !currentStepIds.contains(toolCall.getReplyStepId())
                        || toolCall.getDiceRollSummaryId() != null
                        && !currentSummaryIds.contains(toolCall.getDiceRollSummaryId())) {
                    throw new UserRequestException("跑团工具调用存档不合法");
                }
            }
            for (GroupChatAgentDecision decision
                    : safe(turnSnapshot.getAgentDecisions())) {
                if (decision == null
                        || !validId(decision.getId(), cursors.getMaxAgentDecisionId(), decisionIds)
                        || !currentStepIds.contains(decision.getReplyStepId())) {
                    throw new UserRequestException("跑团角色决策存档不合法");
                }
            }
            for (DiceRollResult result : safe(turnSnapshot.getDiceResults())) {
                if (result == null
                        || !validId(result.getId(), cursors.getMaxDiceResultId(), diceResultIds)
                        || !currentSummaryIds.contains(result.getSummaryId())) {
                    throw new UserRequestException("跑团骰点结果存档不合法");
                }
            }
        }
    }

    private boolean validId(Long id, Long maxId, Set<Long> ids) {
        return id != null && id > 0 && id <= maxId && ids.add(id);
    }

    private void validateCharacterChildren(
            TrpgSaveSnapshotDTO snapshot, Set<Long> characterIds) {
        for (CocCharacterProfile profile : safe(snapshot.getCharacterProfiles())) {
            if (profile == null || profile.getId() == null
                    || !characterIds.contains(profile.getCharacterId())) {
                throw new UserRequestException("跑团人物背景存档不合法");
            }
        }
        for (CocCharacterSkill skill : safe(snapshot.getCharacterSkills())) {
            if (skill == null || skill.getId() == null
                    || !characterIds.contains(skill.getCharacterId())) {
                throw new UserRequestException("跑团技能存档不合法");
            }
        }
        for (CocCharacterWeapon weapon : safe(snapshot.getCharacterWeapons())) {
            if (weapon == null || weapon.getId() == null
                    || !characterIds.contains(weapon.getCharacterId())) {
                throw new UserRequestException("跑团武器存档不合法");
            }
        }
    }

    private void validateWeaponStash(
            TrpgSaveSnapshotDTO snapshot, Set<Long> characterIds) {
        Set<Long> activeWeaponIds = safe(snapshot.getCharacterWeapons())
                .stream()
                .map(CocCharacterWeapon::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> stashIds = new HashSet<>();
        for (TrpgWeaponStash stash : safe(snapshot.getWeaponStash())) {
            if (stash == null || stash.getWeaponId() == null
                    || stash.getWeaponId() <= 0
                    || !Objects.equals(stash.getRunId(),
                    snapshot.getConversationId())
                    || !StringUtils.hasText(
                    stash.getSourceCharacterName())
                    || !StringUtils.hasText(stash.getLocationName())
                    || !StringUtils.hasText(stash.getStashReason())
                    || stash.getWeaponSnapshot() == null
                    || !StringUtils.hasText(
                    stash.getWeaponSnapshot().getName())
                    || !stashIds.add(stash.getWeaponId())
                    || activeWeaponIds.contains(stash.getWeaponId())) {
                throw new UserRequestException(
                        "跑团武器暂存库存档不合法");
            }
            try {
                com.me.galchat.domain.dto.KpEquipmentDTOs.StashReason
                        .valueOf(stash.getStashReason());
            } catch (IllegalArgumentException ex) {
                throw new UserRequestException(
                        "跑团武器暂存原因不合法", ex);
            }
        }
    }

    private void validateCursors(TrpgSaveSnapshotDTO.CursorSnapshot cursors) {
        if (cursors == null) {
            throw new UserRequestException("跑团存档游标不合法");
        }
        Long[] values = {
                cursors.getMaxMessageId(),
                cursors.getMaxTurnId(),
                cursors.getMaxReplyStepId(),
                cursors.getMaxToolCallId(),
                cursors.getMaxAgentDecisionId(),
                cursors.getMaxContextSummaryId(),
                cursors.getMaxTopicId(),
                cursors.getMaxDiceSummaryId(),
                cursors.getMaxDiceResultId()
        };
        for (Long value : values) {
            if (value == null || value < 0) {
                throw new UserRequestException("跑团存档游标不合法");
            }
        }
    }

    private void requirePlanReference(Long planId, Set<Long> planIds) {
        if (planId != null && !planIds.contains(planId)) {
            throw new UserRequestException("跑团回复计划引用不完整");
        }
    }

    private void restorePlans(
            Long conversationId, TrpgSaveSnapshotDTO snapshot) {
        List<Long> currentPlanIds = planMapper.selectList(
                        new LambdaQueryWrapper<GroupReplyPlan>()
                                .eq(GroupReplyPlan::getConversationId, conversationId))
                .stream().map(GroupReplyPlan::getId).toList();
        runtimeChildSceneMapper.delete(
                new LambdaQueryWrapper<TrpgRuntimeChildScene>()
                        .eq(TrpgRuntimeChildScene::getConversationId,
                                conversationId));
        if (!currentPlanIds.isEmpty()) {
            planItemMapper.delete(new LambdaQueryWrapper<GroupReplyPlanItem>()
                    .in(GroupReplyPlanItem::getPlanId, currentPlanIds));
            planMapper.delete(new LambdaQueryWrapper<GroupReplyPlan>()
                    .in(GroupReplyPlan::getId, currentPlanIds));
        }
        safe(snapshot.getReplyPlans()).forEach(planMapper::insert);
        safe(snapshot.getRuntimeChildScenes())
                .forEach(runtimeChildSceneMapper::insert);
        safe(snapshot.getReplyPlanItems()).forEach(planItemMapper::insert);
    }

    private void restoreRestorableTurns(
            List<TrpgSaveSnapshotDTO.RestorableTurnSnapshot> snapshots) {
        for (TrpgSaveSnapshotDTO.RestorableTurnSnapshot snapshot
                : safe(snapshots)) {
            Long turnId = snapshot.getTurn().getId();
            List<Long> stepIds = safe(snapshot.getReplySteps()).stream()
                    .map(GroupChatReplyStep::getId).toList();
            List<Long> summaryIds = safe(snapshot.getDiceSummaries()).stream()
                    .map(DiceRollSummary::getId).toList();
            if (!stepIds.isEmpty()) {
                decisionMapper.delete(
                        new LambdaQueryWrapper<GroupChatAgentDecision>()
                                .in(GroupChatAgentDecision::getReplyStepId, stepIds));
                toolCallMapper.delete(
                        new LambdaQueryWrapper<GroupChatToolCall>()
                                .in(GroupChatToolCall::getReplyStepId, stepIds));
            }
            messageMapper.delete(new LambdaQueryWrapper<GroupChatMessage>()
                    .eq(GroupChatMessage::getTurnId, turnId));
            stepMapper.delete(new LambdaQueryWrapper<GroupChatReplyStep>()
                    .eq(GroupChatReplyStep::getTurnId, turnId));
            if (!summaryIds.isEmpty()) {
                diceResultMapper.delete(new LambdaQueryWrapper<DiceRollResult>()
                        .in(DiceRollResult::getSummaryId, summaryIds));
                diceSummaryMapper.delete(new LambdaQueryWrapper<DiceRollSummary>()
                        .in(DiceRollSummary::getId, summaryIds));
            }
            turnMapper.deleteById(turnId);

            turnMapper.insert(snapshot.getTurn());
            safe(snapshot.getReplySteps()).forEach(stepMapper::insert);
            safe(snapshot.getMessages()).forEach(messageMapper::insert);
            safe(snapshot.getDiceSummaries()).forEach(diceSummaryMapper::insert);
            safe(snapshot.getDiceResults()).forEach(diceResultMapper::insert);
            safe(snapshot.getToolCalls()).forEach(toolCallMapper::insert);
            safe(snapshot.getAgentDecisions()).forEach(decisionMapper::insert);
        }
    }

    private void restoreCharacters(
            Long conversationId, TrpgSaveSnapshotDTO snapshot) {
        List<Long> currentCharacterIds = characterMapper.selectList(
                        new LambdaQueryWrapper<CocCharacter>()
                                .eq(CocCharacter::getRunId, conversationId))
                .stream().map(CocCharacter::getId).toList();
        if (!currentCharacterIds.isEmpty()) {
            profileMapper.delete(new LambdaQueryWrapper<CocCharacterProfile>()
                    .in(CocCharacterProfile::getCharacterId, currentCharacterIds));
            skillMapper.delete(new LambdaQueryWrapper<CocCharacterSkill>()
                    .in(CocCharacterSkill::getCharacterId, currentCharacterIds));
            weaponMapper.delete(new LambdaQueryWrapper<CocCharacterWeapon>()
                    .in(CocCharacterWeapon::getCharacterId, currentCharacterIds));
            characterMapper.delete(new LambdaQueryWrapper<CocCharacter>()
                    .in(CocCharacter::getId, currentCharacterIds));
        }
        Map<Long, String> quickNotes = safeMap(
                snapshot.getCharacterQuickNotes());
        for (CocCharacter character : safe(snapshot.getCharacters())) {
            character.setQuickNotes(quickNotes.get(character.getId()));
            characterMapper.insert(character);
        }
        safe(snapshot.getCharacterProfiles()).forEach(profileMapper::insert);
        safe(snapshot.getCharacterSkills()).forEach(skillMapper::insert);
        safe(snapshot.getCharacterWeapons()).forEach(weaponMapper::insert);
    }

    private void restoreCombats(Long conversationId, List<TrpgCombat> combats) {
        combatMapper.delete(new LambdaQueryWrapper<TrpgCombat>()
                .eq(TrpgCombat::getConversationId, conversationId));
        safe(combats).forEach(combatMapper::insert);
    }

    private void restoreSuspensions(
            Long conversationId, TrpgSaveSnapshotDTO snapshot) {
        if (suspensionMapper == null) {
            return;
        }
        suspensionMapper.delete(
                new LambdaQueryWrapper<TrpgInvestigatorSuspension>()
                        .eq(TrpgInvestigatorSuspension::getConversationId,
                                conversationId));
        safe(snapshot.getInvestigatorSuspensions())
                .forEach(suspensionMapper::insert);
    }

    private void restoreWeaponStash(
            Long conversationId, List<TrpgWeaponStash> weaponStash) {
        weaponStashMapper.delete(
                new LambdaQueryWrapper<TrpgWeaponStash>()
                        .eq(TrpgWeaponStash::getRunId, conversationId));
        safe(weaponStash).forEach(weaponStashMapper::insert);
    }

    private void restoreCheckpoint(
            Long conversationId, GroupTurnCheckpoint checkpoint) {
        checkpointMapper.deleteById(conversationId);
        if (checkpoint != null) {
            checkpointMapper.insert(checkpoint);
        }
    }

    private void restoreConversation(
            GroupConversation conversation,
            TrpgSaveSnapshotDTO.ConversationStateSnapshot state) {
        conversation.setActiveReplyPlanId(state.getActiveReplyPlanId())
                .setTitle(state.getTitle())
                .setSummary(state.getSummary())
                .setStatus(state.getStatus())
                .setVersion(state.getVersion())
                .setGameDayNo(state.getGameDayNo())
                .setGameTimePeriod(state.getGameTimePeriod())
                .setGameTimeRevision(state.getGameTimeRevision())
                .setGameTimeChangedStepId(
                        state.getGameTimeChangedStepId())
                .setGameTimeUpdatedAt(state.getGameTimeUpdatedAt())
                .setUpdatedAt(state.getUpdatedAt())
                .setClosedAt(state.getClosedAt());
        conversationMapper.updateById(conversation);
        conversationMapper.update(null,
                new UpdateWrapper<GroupConversation>()
                        .eq("id", conversation.getId())
                        .set("active_reply_plan_id",
                                state.getActiveReplyPlanId())
                        .set("summary", state.getSummary())
                        .set("game_day_no", state.getGameDayNo())
                        .set("game_time_period",
                                state.getGameTimePeriod())
                        .set("game_time_revision",
                                state.getGameTimeRevision())
                        .set("game_time_changed_step_id",
                                state.getGameTimeChangedStepId())
                        .set("game_time_updated_at",
                                state.getGameTimeUpdatedAt())
                        .set("updated_at", state.getUpdatedAt())
                        .set("closed_at", state.getClosedAt()));
    }

    private TrpgSaveSnapshotDTO.ConversationStateSnapshot conversationState(
            GroupConversation conversation) {
        return new TrpgSaveSnapshotDTO.ConversationStateSnapshot()
                .setActiveReplyPlanId(conversation.getActiveReplyPlanId())
                .setTitle(conversation.getTitle())
                .setSummary(conversation.getSummary())
                .setStatus(conversation.getStatus())
                .setVersion(conversation.getVersion())
                .setGameDayNo(conversation.getGameDayNo())
                .setGameTimePeriod(conversation.getGameTimePeriod())
                .setGameTimeRevision(
                        conversation.getGameTimeRevision())
                .setGameTimeChangedStepId(
                        conversation.getGameTimeChangedStepId())
                .setGameTimeUpdatedAt(
                        conversation.getGameTimeUpdatedAt())
                .setUpdatedAt(conversation.getUpdatedAt())
                .setClosedAt(conversation.getClosedAt());
    }

    private List<Long> sceneIds(List<GroupReplyPlan> plans) {
        return plans.stream()
                .filter(plan -> GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                        plan.getSource()))
                .map(GroupReplyPlan::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private List<TrpgSaveSnapshotDTO.RestorableTurnSnapshot> restorableTurns(
            Long conversationId) {
        List<GroupChatTurn> turns = turnMapper.selectList(
                new LambdaQueryWrapper<GroupChatTurn>()
                        .eq(GroupChatTurn::getConversationId, conversationId)
                        .in(GroupChatTurn::getStatus, RESTORABLE_TURN_STATUSES)
                        .orderByAsc(GroupChatTurn::getId));
        if (turns.isEmpty()) {
            return List.of();
        }
        List<Long> turnIds = turns.stream().map(GroupChatTurn::getId).toList();
        List<GroupChatReplyStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .in(GroupChatReplyStep::getTurnId, turnIds)
                        .orderByAsc(GroupChatReplyStep::getId));
        List<GroupChatMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<GroupChatMessage>()
                        .in(GroupChatMessage::getTurnId, turnIds)
                        .orderByAsc(GroupChatMessage::getId));
        List<Long> stepIds = steps.stream()
                .map(GroupChatReplyStep::getId).toList();
        List<GroupChatToolCall> toolCalls = stepIds.isEmpty() ? List.of()
                : toolCallMapper.selectList(
                        new LambdaQueryWrapper<GroupChatToolCall>()
                                .in(GroupChatToolCall::getReplyStepId, stepIds)
                                .orderByAsc(GroupChatToolCall::getId));
        List<GroupChatAgentDecision> decisions = stepIds.isEmpty() ? List.of()
                : decisionMapper.selectList(
                        new LambdaQueryWrapper<GroupChatAgentDecision>()
                                .in(GroupChatAgentDecision::getReplyStepId, stepIds)
                                .orderByAsc(GroupChatAgentDecision::getId));
        List<Long> diceSummaryIds = toolCalls.stream()
                .map(GroupChatToolCall::getDiceRollSummaryId)
                .filter(Objects::nonNull)
                .distinct().toList();
        List<DiceRollSummary> diceSummaries = diceSummaryIds.isEmpty()
                ? List.of() : diceSummaryMapper.selectList(
                        new LambdaQueryWrapper<DiceRollSummary>()
                                .in(DiceRollSummary::getId, diceSummaryIds)
                                .eq(DiceRollSummary::getConversationId, conversationId)
                                .orderByAsc(DiceRollSummary::getId));
        Set<Long> capturedSummaryIds = diceSummaries.stream()
                .map(DiceRollSummary::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<DiceRollResult> diceResults = capturedSummaryIds.isEmpty()
                ? List.of() : diceResultMapper.selectList(
                        new LambdaQueryWrapper<DiceRollResult>()
                                .in(DiceRollResult::getSummaryId, capturedSummaryIds)
                                .orderByAsc(DiceRollResult::getId));
        return turns.stream().map(turn -> {
            List<GroupChatReplyStep> turnSteps = steps.stream()
                    .filter(step -> Objects.equals(step.getTurnId(), turn.getId()))
                    .toList();
            Set<Long> turnStepIds = turnSteps.stream()
                    .map(GroupChatReplyStep::getId)
                    .collect(java.util.stream.Collectors.toSet());
            List<GroupChatToolCall> turnToolCalls = toolCalls.stream()
                    .filter(call -> turnStepIds.contains(call.getReplyStepId()))
                    .toList();
            Set<Long> turnSummaryIds = turnToolCalls.stream()
                    .map(GroupChatToolCall::getDiceRollSummaryId)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            return new TrpgSaveSnapshotDTO.RestorableTurnSnapshot()
                    .setTurn(turn)
                    .setReplySteps(turnSteps)
                    .setMessages(messages.stream()
                            .filter(message -> Objects.equals(
                                    message.getTurnId(), turn.getId()))
                            .toList())
                    .setToolCalls(turnToolCalls)
                    .setAgentDecisions(decisions.stream()
                            .filter(decision -> turnStepIds.contains(
                                    decision.getReplyStepId()))
                            .toList())
                    .setDiceSummaries(diceSummaries.stream()
                            .filter(summary -> turnSummaryIds.contains(summary.getId()))
                            .toList())
                    .setDiceResults(diceResults.stream()
                            .filter(result -> turnSummaryIds.contains(result.getSummaryId()))
                            .toList());
        }).toList();
    }

    private Map<Long, String> characterQuickNotes(
            List<CocCharacter> characters) {
        Map<Long, String> result = new LinkedHashMap<>();
        for (CocCharacter character : characters) {
            if (character.getId() != null && character.getQuickNotes() != null) {
                result.put(character.getId(), character.getQuickNotes());
            }
        }
        return Map.copyOf(result);
    }

    private TrpgSaveSnapshotDTO.CursorSnapshot normalizeCursors(
            TrpgSaveSnapshotDTO.CursorSnapshot cursors) {
        if (cursors != null) {
            return cursors;
        }
        return new TrpgSaveSnapshotDTO.CursorSnapshot()
                .setMaxMessageId(0L)
                .setMaxTurnId(0L)
                .setMaxReplyStepId(0L)
                .setMaxToolCallId(0L)
                .setMaxAgentDecisionId(0L)
                .setMaxContextSummaryId(0L)
                .setMaxTopicId(0L)
                .setMaxDiceSummaryId(0L)
                .setMaxDiceResultId(0L);
    }

    private void requireTrpgConversation(GroupConversation conversation) {
        if (conversation == null || conversation.getId() == null
                || !GroupChatConstant.MODE_TRPG.equals(conversation.getMode())) {
            throw new UserRequestException("跑团存档仅支持TRPG群聊");
        }
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private <K, V> Map<K, V> safeMap(Map<K, V> values) {
        return values == null ? Map.of() : values;
    }
}

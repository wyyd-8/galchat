package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.constant.TrpgGameTimePeriod;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.domain.vo.TrpgGameTimeVO;
import com.me.galchat.mapper.CocModuleLocationMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.me.galchat.exception.UserRequestException;

import java.util.List;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TrpgSceneSelectionService {

    private final GroupConversationService conversationService;
    private final CocModuleLocationMapper locationMapper;
    private final GroupConversationMapper conversationMapper;
    private final GroupReplyPlanMapper planMapper;
    private final GroupReplyPlanItemMapper itemMapper;
    private final TrpgSceneSelectionStore store;
    private final TrpgParticipantService participantService;
    private final TrpgSelectionRandomizer randomizer;
    private TrpgInvestigatorSuspensionService suspensionService;

    @Autowired(required = false)
    void setSuspensionService(
            TrpgInvestigatorSuspensionService suspensionService) {
        this.suspensionService = suspensionService;
    }

    public List<GroupActionSpec> selectionActions(GroupConversation conversation) {
        List<GroupActionSpec> actions = new ArrayList<>();
        actions.add(selectionAction(
                GroupChatConstant.ACTOR_KP, null, 1));
        int itemOrder = 2;
        for (TrpgParticipantService.Participant participant :
                activeInvestigators(conversation)) {
            actions.add(selectionAction(
                    participant.actor().type(),
                    participant.actor().id(),
                    participant.cardId(),
                    itemOrder++));
        }
        return List.copyOf(actions);
    }

    public void selectLocation(
            Long conversationId, Long characterId, String locationName) {
        if (!StringUtils.hasText(locationName)) {
            throw new UserRequestException("地点名称不能为空");
        }
        GroupConversation conversation =
                conversationService.requireActive(conversationId);
        if (!GroupChatConstant.MODE_TRPG.equals(conversation.getMode())
                || conversation.getModuleId() == null) {
            throw new UserRequestException("只有绑定模组的TRPG群聊可以选景");
        }
        if (conversation.getActiveReplyPlanId() != null) {
            throw new UserRequestException("当前不处于选景阶段");
        }
        conversationService.checkReplyMember(
                conversationId, GroupChatConstant.ACTOR_CHARACTER,
                characterId, false);
        String exactName = locationName.trim();
        List<CocModuleLocation> matches = locationMapper.selectList(
                new LambdaQueryWrapper<CocModuleLocation>()
                        .eq(CocModuleLocation::getModuleId,
                                conversation.getModuleId())
                        .eq(CocModuleLocation::getName, exactName))
                .stream()
                .filter(location -> exactName.equals(location.getName()))
                .toList();
        if (matches.isEmpty()) {
            throw new UserRequestException("地点不存在");
        }
        if (matches.size() > 1) {
            throw new UserRequestException("地点名称不唯一");
        }
        store.put(conversationId, characterId, matches.getFirst().getId());
    }

    public SceneOptionsResult publishOptions(
            Long conversationId, List<String> locationNames) {
        Long turnId = store.currentTurnId(conversationId);
        return publishOptions(
                conversationId,
                turnId == null ? 0L : turnId,
                null, locationNames, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public SceneOptionsResult publishOptions(
            Long conversationId,
            Long turnId,
            List<String> locationNames) {
        return publishOptions(
                conversationId, turnId, null,
                locationNames, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public SceneOptionsResult publishOptions(
            Long conversationId,
            Long turnId,
            Long replyStepId,
            List<String> locationNames,
            Integer targetDay,
            String targetPeriod) {
        GroupConversation conversation =
                requireSelectionConversation(conversationId);
        TimeChange timeChange = validateTimeChange(
                conversation, replyStepId,
                targetDay, targetPeriod);
        if (locationNames == null || locationNames.isEmpty()) {
            throw new UserRequestException("KP至少需要提供一个可选地点");
        }
        List<String> supplied = locationNames.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        List<String> normalized = supplied.stream()
                .distinct().toList();
        if (normalized.size() != supplied.size()) {
            throw new UserRequestException(
                    "KP提供的可选地点名称不能重复");
        }
        if (normalized.isEmpty()) {
            throw new UserRequestException("KP至少需要提供一个可选地点");
        }
        Map<String, TrpgSceneSelectionStore.LocationOption> existing =
                store.getOptions(conversationId, turnId);
        if (!existing.isEmpty()) {
            List<String> existingNames = existing.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(
                            java.util.Comparator.comparingInt(
                                    Integer::parseInt)))
                    .map(entry -> entry.getValue().name())
                    .toList();
            if (!existingNames.equals(normalized)) {
                throw new UserRequestException(
                        "同一选景行动轮不能修改已公布地点");
            }
            Map<String, String> names = new LinkedHashMap<>();
            existing.forEach((number, option) ->
                    names.put(number, option.name()));
            return new SceneOptionsResult(
                    java.util.Collections.unmodifiableMap(names),
                    existing.size() == 1,
                    false,
                    TrpgGameTimeVO.from(conversation));
        }
        List<CocModuleLocation> locations = locationMapper.selectList(
                new LambdaQueryWrapper<CocModuleLocation>()
                        .eq(CocModuleLocation::getModuleId,
                                conversation.getModuleId())
                        .in(CocModuleLocation::getName, normalized));
        Map<String, CocModuleLocation> byName =
                new LinkedHashMap<>();
        for (CocModuleLocation location : locations) {
            if (byName.put(location.getName(), location) != null) {
                throw new UserRequestException("地点名称不唯一："
                        + location.getName());
            }
        }
        if (!byName.keySet().containsAll(normalized)) {
            throw new UserRequestException(
                    "KP提供的可选地点包含模组中不存在的名称");
        }
        boolean timeChanged = applyTimeChange(
                conversation, replyStepId, timeChange);
        Map<String, TrpgSceneSelectionStore.LocationOption> options =
                new LinkedHashMap<>();
        for (int index = 0; index < normalized.size(); index++) {
            CocModuleLocation location =
                    byName.get(normalized.get(index));
            options.put(String.valueOf(index + 1),
                    new TrpgSceneSelectionStore.LocationOption(
                            location.getId(), location.getName()));
        }
        store.putOptions(conversationId, turnId, options);
        boolean autoAssigned = options.size() == 1;
        if (autoAssigned) {
            Long locationId =
                    options.values().iterator().next().locationId();
            for (TrpgParticipantService.Participant participant :
                    activeInvestigators(conversation)) {
                store.put(conversationId, turnId,
                        participant.actor(), locationId);
            }
            finalizeSelections(conversation);
        }
        Map<String, String> names = new LinkedHashMap<>();
        options.forEach((number, option) ->
                names.put(number, option.name()));
        return new SceneOptionsResult(
                java.util.Collections.unmodifiableMap(names),
                autoAssigned,
                timeChanged,
                TrpgGameTimeVO.from(conversation));
    }

    private TimeChange validateTimeChange(
            GroupConversation conversation,
            Long replyStepId,
            Integer targetDay,
            String targetPeriod) {
        boolean hasDay = targetDay != null;
        boolean hasPeriod = StringUtils.hasText(targetPeriod);
        if (hasDay != hasPeriod) {
            throw new UserRequestException(
                    "目标天数和时段必须同时提供或同时省略");
        }
        boolean initialized = conversation.getGameDayNo() != null
                && StringUtils.hasText(
                conversation.getGameTimePeriod());
        if (!initialized && !hasDay) {
            throw new UserRequestException(
                    "首次选景必须设置当前时间");
        }
        if (!hasDay) {
            return null;
        }
        if (targetDay <= 0) {
            throw new UserRequestException("游戏天数必须大于0");
        }
        TrpgGameTimePeriod parsed =
                TrpgGameTimePeriod.parse(targetPeriod);
        if (!initialized) {
            return new TimeChange(targetDay, parsed);
        }
        if (replyStepId != null && Objects.equals(
                conversation.getGameTimeChangedStepId(),
                replyStepId)) {
            return null;
        }
        TrpgGameTimePeriod current =
                TrpgGameTimePeriod.parse(
                        conversation.getGameTimePeriod());
        boolean future = targetDay > conversation.getGameDayNo()
                || targetDay.equals(conversation.getGameDayNo())
                && parsed.ordinal() > current.ordinal();
        if (!future) {
            throw new UserRequestException(
                    "KP只能将时间推进到未来");
        }
        return new TimeChange(targetDay, parsed);
    }

    private boolean applyTimeChange(
            GroupConversation conversation,
            Long replyStepId,
            TimeChange timeChange) {
        if (timeChange == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        conversation.setGameDayNo(timeChange.dayNo())
                .setGameTimePeriod(timeChange.period().name())
                .setGameTimeRevision(
                        conversation.getGameTimeRevision() == null
                                ? 1
                                : conversation.getGameTimeRevision() + 1)
                .setGameTimeChangedStepId(replyStepId)
                .setGameTimeUpdatedAt(now)
                .setUpdatedAt(now);
        if (conversationMapper.updateById(conversation) == 0) {
            throw new IllegalStateException("游戏时间更新失败");
        }
        return true;
    }

    public SceneChoiceResult selectOption(
            Long conversationId,
            GroupActorRef actor,
            String optionNo) {
        Long turnId = store.currentTurnId(conversationId);
        return selectOption(conversationId,
                turnId == null ? 0L : turnId,
                actor, optionNo);
    }

    public SceneChoiceResult selectOption(
            Long conversationId,
            Long turnId,
            GroupActorRef actor,
            String optionNo) {
        GroupConversation conversation =
                requireSelectionConversation(conversationId);
        TrpgParticipantService.Participant participant =
                activeInvestigators(conversation)
                        .stream()
                        .filter(candidate ->
                                candidate.actor().equals(actor))
                        .findFirst()
                        .orElseThrow(() -> new UserRequestException(
                                "当前Actor不是本次跑团调查员"));
        Map<String, TrpgSceneSelectionStore.LocationOption> options =
                store.getOptions(conversationId, turnId);
        if (options.isEmpty()) {
            throw new UserRequestException("KP尚未公布可选地点");
        }
        TrpgSceneSelectionStore.LocationOption selected =
                options.get(optionNo == null ? null : optionNo.trim());
        boolean randomized = selected == null;
        if (randomized) {
            Map<String, Long> selections =
                    store.getSelections(conversationId, turnId);
            java.util.Set<Long> used =
                    new java.util.HashSet<>(selections.values());
            List<TrpgSceneSelectionStore.LocationOption> preferred =
                    options.values().stream()
                            .filter(option ->
                                    !used.contains(option.locationId()))
                            .toList();
            selected = randomizer.choose(preferred.isEmpty()
                    ? List.copyOf(options.values()) : preferred);
        }
        TrpgSceneSelectionStore.LocationOption finalSelected = selected;
        String selectedNumber = options.entrySet().stream()
                .filter(entry -> entry.getValue().equals(finalSelected))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
        store.put(conversationId, turnId,
                actor, finalSelected.locationId());
        return new SceneChoiceResult(
                selectedNumber,
                participant.controllerName(),
                participant.investigatorName(),
                finalSelected.name(), randomized);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean finalizeSelections(GroupConversation conversation) {
        if (conversation == null || conversation.getId() == null
                || conversation.getModuleId() == null
                || conversation.getActiveReplyPlanId() != null) {
            return false;
        }
        List<TrpgParticipantService.Participant> participants =
                activeInvestigators(conversation);
        if (participants.isEmpty()) {
            return false;
        }
        Map<String, Long> selections =
                store.getSelections(conversation.getId());
        if (participants.stream().anyMatch(participant ->
                !selections.containsKey(
                        TrpgSceneSelectionStore.actorKey(
                                participant.actor())))) {
            return false;
        }
        List<Long> selectedLocationIds = participants.stream()
                .map(participant -> selections.get(
                        TrpgSceneSelectionStore.actorKey(
                                participant.actor())))
                .distinct()
                .toList();
        Map<Long, CocModuleLocation> locationById = new LinkedHashMap<>();
        for (CocModuleLocation location : locationMapper.selectList(
                new LambdaQueryWrapper<CocModuleLocation>()
                        .eq(CocModuleLocation::getModuleId,
                                conversation.getModuleId())
                        .in(CocModuleLocation::getId, selectedLocationIds))) {
            if (Objects.equals(location.getModuleId(),
                    conversation.getModuleId())) {
                locationById.put(location.getId(), location);
            }
        }
        if (!locationById.keySet().containsAll(selectedLocationIds)) {
            throw new UserRequestException("选景结果包含无效模组地点");
        }

        Map<Long, List<TrpgParticipantService.Participant>>
                membersByLocation =
                new LinkedHashMap<>();
        for (TrpgParticipantService.Participant participant :
                participants) {
            Long locationId = selections.get(
                    TrpgSceneSelectionStore.actorKey(
                            participant.actor()));
            membersByLocation.computeIfAbsent(
                    locationId, ignored -> new ArrayList<>())
                    .add(participant);
        }

        LocalDateTime now = LocalDateTime.now();
        Long nextPlanId = null;
        List<Map.Entry<Long,
                List<TrpgParticipantService.Participant>>> groups =
                new ArrayList<>(membersByLocation.entrySet());
        for (int index = groups.size() - 1; index >= 0; index--) {
            Map.Entry<Long, List<TrpgParticipantService.Participant>>
                    group = groups.get(index);
            CocModuleLocation location = locationById.get(group.getKey());
            GroupReplyPlan plan = new GroupReplyPlan()
                    .setConversationId(conversation.getId())
                    .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                    .setContextId(location.getId())
                    .setExecutionKey("scene:" + location.getId())
                    .setDisplayName(location.getName())
                    .setNextPlanId(nextPlanId)
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            planMapper.insert(plan);
            insertSceneItems(plan, location, group.getValue(), now);
            nextPlanId = plan.getId();
        }
        conversation.setActiveReplyPlanId(nextPlanId).setUpdatedAt(now);
        conversationMapper.updateById(conversation);
        store.clear(conversation.getId());
        return true;
    }

    private GroupActionSpec selectionAction(
            String actorType, Long actorId, int itemOrder) {
        return selectionAction(actorType, actorId, null, itemOrder);
    }

    private List<TrpgParticipantService.Participant> activeInvestigators(
            GroupConversation conversation) {
        return participantService.listInvestigators(conversation).stream()
                .filter(participant -> suspensionService == null
                        || !suspensionService.isUnavailable(
                                conversation.getId(),
                                participant.cardId(), null))
                .toList();
    }

    private GroupActionSpec selectionAction(
            String actorType, Long actorId, Long subjectCharacterId,
            int itemOrder) {
        return new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE_SELECTION,
                actorType,
                actorId,
                subjectCharacterId,
                "scene-selection",
                "选景",
                1,
                itemOrder);
    }

    private void insertSceneItems(
            GroupReplyPlan plan,
            CocModuleLocation location,
            List<TrpgParticipantService.Participant> participants,
            LocalDateTime now) {
        int itemOrder = 1;
        for (TrpgParticipantService.Participant participant :
                participants) {
            itemMapper.insert(new GroupReplyPlanItem()
                    .setPlanId(plan.getId())
                    .setItemOrder(itemOrder++)
                    .setActorType(participant.actor().type())
                    .setActorId(participant.actor().id())
                    .setSubjectCharacterId(participant.cardId())
                    .setSubjectCharacterName(
                            participant.investigatorName())
                    .setCreatedAt(now)
                    .setUpdatedAt(now));
        }
        itemMapper.insert(new GroupReplyPlanItem()
                .setPlanId(plan.getId())
                .setItemOrder(itemOrder)
                .setActorType(GroupChatConstant.ACTOR_KP)
                .setActorId(null)
                .setCreatedAt(now)
                .setUpdatedAt(now));
    }

    private GroupConversation requireSelectionConversation(
            Long conversationId) {
        GroupConversation conversation =
                conversationService.requireActive(conversationId);
        if (!GroupChatConstant.MODE_TRPG.equals(conversation.getMode())
                || conversation.getModuleId() == null) {
            throw new UserRequestException(
                    "只有绑定模组的TRPG群聊可以选景");
        }
        if (conversation.getActiveReplyPlanId() != null) {
            throw new UserRequestException("当前不处于选景阶段");
        }
        return conversation;
    }

    public record SceneOptionsResult(
            Map<String, String> options,
            boolean autoAssigned,
            boolean timeChanged,
            TrpgGameTimeVO gameTime) {
    }

    private record TimeChange(
            Integer dayNo,
            TrpgGameTimePeriod period) {
    }

    public record SceneChoiceResult(
            String optionNo,
            String controllerName,
            String investigatorName,
            String locationName,
            boolean randomized) {
    }
}

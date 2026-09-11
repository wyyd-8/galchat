package com.me.galchat.service.impl.trpg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.CocCheckOutcome;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.TrpgRunMemoryModels;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.vo.CharacterCardVO;
import com.me.galchat.domain.vo.DiceResolutionDataVO;
import com.me.galchat.domain.vo.TrpgGameTimeVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.dice.DiceRollMessageCodec;
import com.me.galchat.groupchat.dice.DiceRollMessageContent;
import com.me.galchat.groupchat.dice.GroupDiceMessageFormatter;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocModuleMapper;
import com.me.galchat.mapper.DiceRollResultMapper;
import com.me.galchat.mapper.GroupChatMemberMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupContextSummaryMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.vector.TrpgTurnVectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
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
public class TrpgRunMemoryService {

    private static final Set<String> PUBLIC_MEMORY_KINDS = Set.of(
            GroupChatConstant.MESSAGE_DIALOGUE,
            GroupChatConstant.MESSAGE_NARRATION,
            GroupChatConstant.MESSAGE_DICE_ROLL,
            GroupChatConstant.MESSAGE_COMBAT_RESULT,
            GroupChatConstant.MESSAGE_EPILOGUE);

    private final GroupConversationMapper conversationMapper;
    private final GroupChatMemberMapper memberMapper;
    private final CocModuleMapper moduleMapper;
    private final CocCharacterMapper characterMapper;
    private final ICharacterCardService cardService;
    private final GroupReplyPlanMapper planMapper;
    private final GroupContextSummaryMapper summaryMapper;
    private final GroupChatMessageMapper messageMapper;
    private final GroupChatTurnMapper turnMapper;
    private final DiceRollMessageCodec diceCodec;
    private final DiceRollResultMapper diceResultMapper;
    private final GroupDiceMessageFormatter diceFormatter;
    private final TrpgTurnVectorService vectorService;

    public TrpgRunMemoryModels.RunListResult listRuns(
            Long userWorldId, Long characterId) {
        requireContext(userWorldId, characterId);
        List<Long> participatedIds = memberMapper.selectList(
                        new LambdaQueryWrapper<GroupChatMember>()
                                .eq(GroupChatMember::getActorType,
                                        GroupChatConstant.ACTOR_CHARACTER)
                                .eq(GroupChatMember::getActorId, characterId))
                .stream()
                .map(GroupChatMember::getConversationId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        LocalDateTime snapshotAt = LocalDateTime.now();
        if (participatedIds.isEmpty()) {
            return new TrpgRunMemoryModels.RunListResult(
                    snapshotAt, TrpgRunMemoryModels.SNAPSHOT_NOTICE,
                    List.of());
        }
        List<GroupConversation> runs = conversationMapper.selectList(
                new LambdaQueryWrapper<GroupConversation>()
                        .in(GroupConversation::getId, participatedIds)
                        .eq(GroupConversation::getUserWorldId, userWorldId)
                        .eq(GroupConversation::getMode,
                                GroupChatConstant.MODE_TRPG)
                        .orderByDesc(GroupConversation::getUpdatedAt)
                        .orderByDesc(GroupConversation::getId));
        if (runs.isEmpty()) {
            return new TrpgRunMemoryModels.RunListResult(
                    snapshotAt, TrpgRunMemoryModels.SNAPSHOT_NOTICE,
                    List.of());
        }
        Map<Long, String> moduleNames = moduleNames(runs);
        Map<Long, List<CocCharacter>> cardsByRun = cardsByRun(runs);
        List<TrpgRunMemoryModels.RunBrief> result = runs.stream()
                .map(run -> new TrpgRunMemoryModels.RunBrief(
                        run.getId(), moduleNames.get(run.getModuleId()),
                        run.getStatus(), names(cardsByRun.get(run.getId())),
                        run.getCreatedAt(), run.getUpdatedAt()))
                .toList();
        return new TrpgRunMemoryModels.RunListResult(
                snapshotAt, TrpgRunMemoryModels.SNAPSHOT_NOTICE, result);
    }

    public TrpgRunMemoryModels.RunDetails getRunDetails(
            Long userWorldId, Long characterId, Long runId) {
        GroupConversation run = requireParticipatedRun(
                userWorldId, characterId, runId);
        CocCharacter controlled = requireControlledCard(runId, characterId);
        CharacterCardVO card = cardService.getById(controlled.getId());
        List<GroupChatMessage> diceMessages = publicDiceMessages(runId);
        boolean closed = GroupChatConstant.STATUS_CLOSED.equals(
                run.getStatus());
        return new TrpgRunMemoryModels.RunDetails(
                run.getId(), moduleName(run.getModuleId()), run.getStatus(),
                briefCard(card), ownDiceStatistics(controlled.getId(),
                        diceMessages),
                TrpgGameTimeVO.from(run),
                closed ? null : currentScene(run),
                completedScenes(runId),
                closed ? null : latestPublicMessage(
                        runId, cardsById(runId)),
                run.getCreatedAt(), run.getUpdatedAt(),
                closed ? run.getClosedAt() : null,
                closed ? run.getSummary() : null,
                LocalDateTime.now(), TrpgRunMemoryModels.SNAPSHOT_NOTICE);
    }

    public TrpgRunMemoryModels.ChatSearchResult searchChatRounds(
            Long userWorldId, Long characterId, Long runId,
            String keyword) {
        requireParticipatedRun(userWorldId, characterId, runId);
        if (!StringUtils.hasText(keyword)) {
            throw new UserRequestException("检索关键词不能为空");
        }
        String normalized = keyword.trim();
        List<TrpgTurnVectorService.Candidate> candidates =
                vectorService.search(runId, normalized);
        Map<Long, CocCharacter> cards = cardsById(runId);
        List<TrpgRunMemoryModels.ChatRound> rounds = new ArrayList<>();
        for (TrpgTurnVectorService.Candidate candidate : candidates) {
            GroupChatTurn turn = turnMapper.selectById(candidate.turnId());
            if (turn == null
                    || !Objects.equals(runId, turn.getConversationId())
                    || !GroupChatConstant.STATUS_COMPLETED.equals(
                    turn.getStatus())) {
                continue;
            }
            List<GroupChatMessage> messages = publicMessages(
                    runId, turn.getId());
            if (messages.isEmpty()) {
                continue;
            }
            rounds.add(new TrpgRunMemoryModels.ChatRound(
                    turn.getId(), candidate.occurredAt(), candidate.score(),
                    messages.stream()
                            .map(message -> publicMessage(message, cards))
                            .toList()));
            if (rounds.size() == 3) {
                break;
            }
        }
        return new TrpgRunMemoryModels.ChatSearchResult(
                runId, normalized, LocalDateTime.now(),
                TrpgRunMemoryModels.SNAPSHOT_NOTICE, List.copyOf(rounds));
    }

    private GroupConversation requireParticipatedRun(
            Long userWorldId, Long characterId, Long runId) {
        requireContext(userWorldId, characterId);
        if (runId == null) {
            throw new UserRequestException("跑团不存在或当前角色未参与");
        }
        GroupConversation run = conversationMapper.selectById(runId);
        if (run == null
                || !Objects.equals(userWorldId, run.getUserWorldId())
                || !GroupChatConstant.MODE_TRPG.equals(run.getMode())) {
            throw new UserRequestException("跑团不存在或当前角色未参与");
        }
        Long count = memberMapper.selectCount(
                new LambdaQueryWrapper<GroupChatMember>()
                        .eq(GroupChatMember::getConversationId, runId)
                        .eq(GroupChatMember::getActorType,
                                GroupChatConstant.ACTOR_CHARACTER)
                        .eq(GroupChatMember::getActorId, characterId));
        if (count == null || count == 0) {
            throw new UserRequestException("跑团不存在或当前角色未参与");
        }
        return run;
    }

    private void requireContext(Long userWorldId, Long characterId) {
        if (userWorldId == null || characterId == null) {
            throw new UserRequestException("当前角色上下文不完整");
        }
    }

    private CocCharacter requireControlledCard(
            Long runId, Long characterId) {
        List<CocCharacter> cards = characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId, runId)
                        .eq(CocCharacter::getActorType, "BOT")
                        .eq(CocCharacter::getParticipantId, characterId)
                        .orderByAsc(CocCharacter::getId));
        if (cards.size() != 1) {
            throw new UserRequestException("当前角色没有唯一的调查员人物卡");
        }
        return cards.getFirst();
    }

    private TrpgRunMemoryModels.ControlledInvestigator briefCard(
            CharacterCardVO card) {
        CocCharacter character = card.getCharacter();
        Map<String, Integer> attributes = new LinkedHashMap<>();
        attributes.put("STR", character.getStr());
        attributes.put("CON", character.getCon());
        attributes.put("SIZ", character.getSiz());
        attributes.put("DEX", character.getDex());
        attributes.put("APP", character.getApp());
        attributes.put("INT", character.getIntValue());
        attributes.put("POW", character.getPow());
        attributes.put("EDU", character.getEdu());
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("hpCurrent", character.getHpCurrent());
        derived.put("hpMax", character.getHpMax());
        derived.put("sanCurrent", character.getSanCurrent());
        derived.put("sanMax", character.getSanMax());
        derived.put("mpCurrent", character.getMpCurrent());
        derived.put("mpMax", character.getMpMax());
        derived.put("luckCurrent", character.getLuckCurrent());
        derived.put("mov", character.getMov());
        derived.put("build", character.getBuild());
        derived.put("damageBonus", character.getDamageBonus());
        derived.put("armor", character.getArmor());
        Map<String, Integer> nonBaseSkills = new LinkedHashMap<>();
        for (CocCharacterSkill skill : safe(card.getSkills())) {
            if (skill != null && StringUtils.hasText(skill.getDisplayName())
                    && skill.getValue() != null
                    && (Boolean.TRUE.equals(skill.getIsCustom())
                    || skill.getBaseValue() == null
                    || !Objects.equals(
                    skill.getValue(), skill.getBaseValue()))) {
                nonBaseSkills.put(skill.getDisplayName(), skill.getValue());
            }
        }
        return new TrpgRunMemoryModels.ControlledInvestigator(
                character.getName(), character.getOccupation(),
                character.getAge(), character.getSex(), attributes, derived,
                statuses(character), nonBaseSkills);
    }

    private List<String> statuses(CocCharacter character) {
        List<String> statuses = new ArrayList<>();
        addStatus(statuses, character.getMajorWound(), "majorWound");
        addStatus(statuses, character.getUnconscious(), "unconscious");
        addStatus(statuses, character.getDying(), "dying");
        addStatus(statuses, character.getDead(), "dead");
        addStatus(statuses, character.getTemporaryInsanity(),
                "temporaryInsanity");
        addStatus(statuses, character.getInCover(), "inCover");
        if (character.getStunnedRemainingRounds() != null
                && character.getStunnedRemainingRounds() > 0) {
            statuses.add("stunned");
        }
        if (character.getRestrainedByCharacterId() != null) {
            statuses.add("restrained");
        }
        return List.copyOf(statuses);
    }

    private void addStatus(
            List<String> statuses, Boolean active, String value) {
        if (Boolean.TRUE.equals(active)) {
            statuses.add(value);
        }
    }

    private TrpgRunMemoryModels.OwnDiceStatistics ownDiceStatistics(
            Long cardId, List<GroupChatMessage> publicMessages) {
        Set<DiceReference> visible = new HashSet<>();
        for (GroupChatMessage message : publicMessages) {
            if (!GroupChatConstant.MESSAGE_DICE_ROLL.equals(
                    message.getMessageKind())) {
                continue;
            }
            DiceRollMessageContent reference = diceCodec.decode(
                    message.getContent());
            for (Integer roundNo : reference.roundNos()) {
                visible.add(new DiceReference(
                        reference.summaryId(), roundNo));
            }
        }
        if (visible.isEmpty()) {
            return new TrpgRunMemoryModels.OwnDiceStatistics(0, 0, 0, 0, 0);
        }
        List<Long> summaryIds = visible.stream()
                .map(DiceReference::summaryId).distinct().toList();
        List<DiceRollResult> results = diceResultMapper.selectList(
                new LambdaQueryWrapper<DiceRollResult>()
                        .in(DiceRollResult::getSummaryId, summaryIds)
                        .isNotNull(DiceRollResult::getResolvedAt));
        EnumMap<CocCheckOutcome, Integer> counts =
                new EnumMap<>(CocCheckOutcome.class);
        for (DiceRollResult result : results) {
            if (!visible.contains(new DiceReference(
                    result.getSummaryId(), result.getRoundNo()))
                    || !Objects.equals(cardId, ruleCardId(
                    result.getResolutionData()))) {
                continue;
            }
            CocCheckOutcome outcome = outcome(result.getResolutionData());
            if (outcome != null) {
                counts.merge(outcome, 1, Integer::sum);
            }
        }
        int critical = counts.getOrDefault(
                CocCheckOutcome.CRITICAL_SUCCESS, 0);
        int success = counts.getOrDefault(CocCheckOutcome.SUCCESS, 0);
        int failure = counts.getOrDefault(CocCheckOutcome.FAILURE, 0);
        int fumble = counts.getOrDefault(CocCheckOutcome.FUMBLE, 0);
        return new TrpgRunMemoryModels.OwnDiceStatistics(
                critical + success + failure + fumble,
                critical, success, failure, fumble);
    }

    private Long ruleCardId(DiceResolutionDataVO data) {
        if (data == null || data.getRule() == null) {
            return null;
        }
        Object value = data.getRule().get("cardId");
        return value instanceof Number number ? number.longValue() : null;
    }

    private CocCheckOutcome outcome(DiceResolutionDataVO data) {
        if (data == null || data.getOutcome() == null) {
            return null;
        }
        Object value = data.getOutcome().get("category");
        if (!(value instanceof String name)) {
            return null;
        }
        try {
            return CocCheckOutcome.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private TrpgRunMemoryModels.CurrentScene currentScene(
            GroupConversation run) {
        GroupReplyPlan plan = run.getActiveReplyPlanId() == null
                ? null : planMapper.selectById(run.getActiveReplyPlanId());
        return plan == null ? null : new TrpgRunMemoryModels.CurrentScene(
                plan.getId(), plan.getDisplayName());
    }

    private List<TrpgRunMemoryModels.CompletedScene> completedScenes(
            Long runId) {
        return summaryMapper.selectList(
                        new LambdaQueryWrapper<GroupContextSummary>()
                                .eq(GroupContextSummary::getConversationId,
                                        runId)
                                .isNotNull(GroupContextSummary::getScenePlanId)
                                .orderByAsc(GroupContextSummary::getStartSequence)
                                .orderByDesc(GroupContextSummary::getVersion))
                .stream()
                .filter(summary -> summary.getScenePlanId() != null)
                .collect(Collectors.toMap(
                        GroupContextSummary::getScenePlanId,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new))
                .values().stream()
                .map(summary -> new TrpgRunMemoryModels.CompletedScene(
                        summary.getScenePlanId(), summary.getStartSequence(),
                        summary.getEndSequence(), summary.getSummary()))
                .toList();
    }

    private List<GroupChatMessage> publicMessages(
            Long runId, Long turnId) {
        return messageMapper.selectList(
                        new LambdaQueryWrapper<GroupChatMessage>()
                                .eq(GroupChatMessage::getConversationId,
                                        runId)
                                .eq(turnId != null,
                                        GroupChatMessage::getTurnId, turnId)
                                .eq(GroupChatMessage::getVisibility, "public")
                                .eq(GroupChatMessage::getStatus,
                                        GroupChatConstant.STATUS_COMPLETED)
                                .orderByAsc(GroupChatMessage::getSequenceNo)
                                .orderByAsc(GroupChatMessage::getId))
                .stream()
                .filter(message -> PUBLIC_MEMORY_KINDS.contains(
                        message.getMessageKind()))
                .toList();
    }

    private List<GroupChatMessage> publicDiceMessages(Long runId) {
        return messageMapper.selectList(
                new LambdaQueryWrapper<GroupChatMessage>()
                        .eq(GroupChatMessage::getConversationId, runId)
                        .eq(GroupChatMessage::getVisibility, "public")
                        .eq(GroupChatMessage::getStatus,
                                GroupChatConstant.STATUS_COMPLETED)
                        .eq(GroupChatMessage::getMessageKind,
                                GroupChatConstant.MESSAGE_DICE_ROLL)
                        .orderByAsc(GroupChatMessage::getId));
    }

    private TrpgRunMemoryModels.PublicMessage latestPublicMessage(
            Long runId, Map<Long, CocCharacter> cards) {
        List<GroupChatMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<GroupChatMessage>()
                        .eq(GroupChatMessage::getConversationId, runId)
                        .eq(GroupChatMessage::getVisibility, "public")
                        .eq(GroupChatMessage::getStatus,
                                GroupChatConstant.STATUS_COMPLETED)
                        .in(GroupChatMessage::getMessageKind,
                                PUBLIC_MEMORY_KINDS)
                        .orderByDesc(GroupChatMessage::getSequenceNo)
                        .orderByDesc(GroupChatMessage::getId)
                        .last("limit 1"));
        return messages.isEmpty()
                ? null : publicMessage(messages.getFirst(), cards);
    }

    private TrpgRunMemoryModels.PublicMessage publicMessage(
            GroupChatMessage message, Map<Long, CocCharacter> cards) {
        return new TrpgRunMemoryModels.PublicMessage(
                message.getId(), speakerName(message, cards),
                message.getMessageKind(), content(message),
                message.getCreatedAt());
    }

    private String content(GroupChatMessage message) {
        return GroupChatConstant.MESSAGE_DICE_ROLL.equals(
                message.getMessageKind())
                ? diceFormatter.format(message.getContent())
                : message.getContent();
    }

    private String speakerName(
            GroupChatMessage message, Map<Long, CocCharacter> cards) {
        if (GroupChatConstant.ACTOR_KP.equals(message.getSpeakerType())) {
            return "KP";
        }
        if (GroupChatConstant.ACTOR_NARRATOR.equals(
                message.getSpeakerType())) {
            return "旁白";
        }
        if (GroupChatConstant.ACTOR_USER.equals(message.getSpeakerType())) {
            return cards.values().stream()
                    .filter(card -> "PLAYER".equals(card.getActorType()))
                    .map(CocCharacter::getName).findFirst().orElse("用户");
        }
        CocCharacter card = cards.get(message.getSpeakerId());
        return card == null ? "角色" : card.getName();
    }

    private Map<Long, CocCharacter> cardsById(Long runId) {
        Map<Long, CocCharacter> result = new HashMap<>();
        for (CocCharacter card : characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId, runId)
                        .orderByAsc(CocCharacter::getId))) {
            if ("BOT".equals(card.getActorType())
                    && card.getParticipantId() != null) {
                result.put(card.getParticipantId(), card);
            } else if (card.getId() != null) {
                result.putIfAbsent(card.getId(), card);
            }
        }
        return result;
    }

    private Map<Long, String> moduleNames(List<GroupConversation> runs) {
        List<Long> ids = runs.stream().map(GroupConversation::getModuleId)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return moduleMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(
                        CocModule::getId, CocModule::getName));
    }

    private String moduleName(Long moduleId) {
        CocModule module = moduleId == null
                ? null : moduleMapper.selectById(moduleId);
        return module == null ? null : module.getName();
    }

    private Map<Long, List<CocCharacter>> cardsByRun(
            List<GroupConversation> runs) {
        List<Long> ids = runs.stream().map(GroupConversation::getId).toList();
        return characterMapper.selectList(
                        new LambdaQueryWrapper<CocCharacter>()
                                .in(CocCharacter::getRunId, ids)
                                .orderByAsc(CocCharacter::getId))
                .stream().collect(Collectors.groupingBy(
                        CocCharacter::getRunId, LinkedHashMap::new,
                        Collectors.toList()));
    }

    private Map<String, String> names(List<CocCharacter> cards) {
        Map<String, String> result = new LinkedHashMap<>();
        safe(cards).stream()
                .sorted(Comparator.comparingInt(card ->
                        "PLAYER".equals(card.getActorType()) ? 0 : 1))
                .forEach(card -> {
                    String controller = "PLAYER".equals(card.getActorType())
                            ? "用户" : StringUtils.hasText(
                            card.getPlayerName())
                            ? card.getPlayerName().trim()
                            : "角色";
                    result.put(controller, card.getName());
                });
        return Map.copyOf(result);
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record DiceReference(Long summaryId, Integer roundNo) {
    }
}

package com.me.galchat.service.impl.trpg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.CocCheckOutcome;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.TrpgCompletionModels.*;
import com.me.galchat.domain.po.*;
import com.me.galchat.groupchat.dice.DiceRollMessageCodec;
import com.me.galchat.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrpgCompletionMaterialsService {
    private final GroupContextSummaryMapper summaryMapper;
    private final TrpgSummaryIntervalSelector intervalSelector;
    private final GroupChatMessageMapper messageMapper;
    private final GroupChatTurnMapper turnMapper;
    private final CocCharacterMapper characterMapper;
    private final CocModuleMapper moduleMapper;
    private final TrpgAutoSaveMapper autoSaveMapper;
    private final DiceRollResultMapper diceResultMapper;
    private final DiceRollMessageCodec diceCodec;
    private final TrpgEpilogueService epilogueService;
    private final TrpgCombatMapper combatMapper;
    private final CocModuleLocationMapper locationMapper;

    public Materials capture(GroupConversation conversation, Long completingTurnId) {
        List<GroupChatMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<GroupChatMessage>()
                .eq(GroupChatMessage::getConversationId, conversation.getId())
                .eq(GroupChatMessage::getVisibility, "public")
                .eq(GroupChatMessage::getStatus, GroupChatConstant.STATUS_COMPLETED)
                .ne(GroupChatMessage::getMessageKind, GroupChatConstant.MESSAGE_EPILOGUE)
                .orderByAsc(GroupChatMessage::getSequenceNo));
        long endSequence = messages.stream().map(GroupChatMessage::getSequenceNo).filter(Objects::nonNull)
                .mapToLong(Long::longValue).max().orElse(0);
        List<Source> sources = intervalSelector.select(summaryMapper.selectList(
                        new LambdaQueryWrapper<GroupContextSummary>()
                                .eq(GroupContextSummary::getConversationId, conversation.getId())
                                .isNotNull(GroupContextSummary::getScenePlanId)
                                .le(GroupContextSummary::getEndSequence, endSequence)))
                .stream().filter(source -> source.getSummary() != null && !source.getSummary().isBlank())
                .map(source -> new Source(source.getStartSequence(), source.getEndSequence(), source.getSummary())).toList();
        List<CocCharacter> cards = characterMapper.selectList(new LambdaQueryWrapper<CocCharacter>()
                .eq(CocCharacter::getRunId, conversation.getId()).in(CocCharacter::getActorType, "PLAYER", "BOT")
                .orderByAsc(CocCharacter::getId));
        var subjects = epilogueService.subjects(cards);
        Map<Long, CocCharacter> initial = initialCharacters(conversation.getId());
        List<Investigator> people = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            CocCharacter card = cards.get(i), start = initial.get(card.getId());
            people.add(new Investigator(subjects.get(i), card.getImage(), "PLAYER".equals(card.getActorType()),
                    start == null ? null : start.getHpCurrent(), start == null ? null : start.getSanCurrent()));
        }
        List<GroupChatTurn> turns = turnMapper.selectList(new LambdaQueryWrapper<GroupChatTurn>()
                .eq(GroupChatTurn::getConversationId, conversation.getId())
                .eq(GroupChatTurn::getStatus, GroupChatConstant.STATUS_COMPLETED)
                .le(GroupChatTurn::getId, completingTurnId).orderByAsc(GroupChatTurn::getId));
        Map<Long, Integer> turnNumbers = new HashMap<>();
        for (int i = 0; i < turns.size(); i++) turnNumbers.put(turns.get(i).getId(), i + 1);
        CocModule module = conversation.getModuleId() == null ? null : moduleMapper.selectById(conversation.getModuleId());
        return new Materials(module == null ? conversation.getTitle() : module.getName(),
                module == null ? null : module.getCoverUrl(), endSequence, turns.size(), sources, people,
                rolls(messages, cards.stream().map(CocCharacter::getId).collect(Collectors.toSet()), turnNumbers),
                combats(conversation.getId(), endSequence));
    }

    private List<Combat> combats(Long conversationId, long endSequence) {
        List<TrpgCombat> completed = combatMapper.selectList(new LambdaQueryWrapper<TrpgCombat>()
                .eq(TrpgCombat::getConversationId, conversationId)
                .eq(TrpgCombat::getStatus, GroupChatConstant.COMBAT_STATUS_COMPLETED)
                .le(TrpgCombat::getEndSequence, endSequence)
                .orderByAsc(TrpgCombat::getStartRequestedStepId, TrpgCombat::getId));
        if (completed.isEmpty()) return List.of();
        return withSceneNames(completed.stream()
                .map(combat -> new Combat(combat.getId(), null, combat.getSummary())).toList(), completed);
    }

    /** Older reports may contain empty names because runtime plans had already been deleted. */
    public List<Combat> fillMissingCombatSceneNames(Long conversationId, List<Combat> entries) {
        if (entries == null || entries.isEmpty()) return entries;
        List<Long> missing = entries.stream().filter(entry -> !StringUtils.hasText(entry.sceneName()))
                .map(Combat::combatId).filter(Objects::nonNull).distinct().toList();
        if (missing.isEmpty()) return entries;
        List<TrpgCombat> records = combatMapper.selectList(new LambdaQueryWrapper<TrpgCombat>()
                .eq(TrpgCombat::getConversationId, conversationId).in(TrpgCombat::getId, missing));
        return withSceneNames(entries, records);
    }

    private List<Combat> withSceneNames(List<Combat> entries, List<TrpgCombat> records) {
        List<Long> locationIds = records.stream().map(TrpgCombat::getSourceSceneId)
                .filter(Objects::nonNull).distinct().toList();
        if (locationIds.isEmpty()) return entries;
        Map<Long, String> names = locationMapper.selectList(new LambdaQueryWrapper<CocModuleLocation>()
                        .in(CocModuleLocation::getId, locationIds)).stream()
                .filter(location -> StringUtils.hasText(location.getName()))
                .collect(Collectors.toMap(CocModuleLocation::getId, CocModuleLocation::getName));
        Map<Long, TrpgCombat> byId = records.stream().collect(Collectors.toMap(TrpgCombat::getId, record -> record));
        return entries.stream().map(entry -> {
            TrpgCombat record = byId.get(entry.combatId());
            if (StringUtils.hasText(entry.sceneName()) || record == null) return entry;
            return new Combat(entry.combatId(), names.get(record.getSourceSceneId()), entry.summary());
        }).toList();
    }

    private Map<Long, CocCharacter> initialCharacters(Long conversationId) {
        TrpgAutoSave initial = autoSaveMapper.selectByConversationAndType(conversationId, TrpgSaveServiceImpl.CHECKPOINT_INITIAL);
        if (initial == null || initial.getSnapshot() == null || initial.getSnapshot().getCharacters() == null
                || !Objects.equals(initial.getSnapshot().getConversationId(), conversationId)) return Map.of();
        return initial.getSnapshot().getCharacters().stream()
                .filter(card -> Objects.equals(card.getRunId(), conversationId))
                .collect(Collectors.toMap(CocCharacter::getId, card -> card));
    }

    private record DiceReference(Long summaryId, Integer roundNo) {}

    List<Roll> rolls(List<GroupChatMessage> messages, Set<Long> investigatorIds, Map<Long, Integer> turnNumbers) {
        Map<DiceReference, Integer> visible = new LinkedHashMap<>();
        for (GroupChatMessage message : messages) {
            if (!GroupChatConstant.MESSAGE_DICE_ROLL.equals(message.getMessageKind())) continue;
            var reference = diceCodec.decode(message.getContent());
            for (Integer round : reference.roundNos()) {
                visible.putIfAbsent(new DiceReference(reference.summaryId(), round), turnNumbers.getOrDefault(message.getTurnId(), 0));
            }
        }
        if (visible.isEmpty()) return List.of();
        List<DiceRollResult> results = diceResultMapper.selectList(new LambdaQueryWrapper<DiceRollResult>()
                .in(DiceRollResult::getSummaryId, visible.keySet().stream().map(DiceReference::summaryId).distinct().toList())
                .isNotNull(DiceRollResult::getResolvedAt).orderByAsc(DiceRollResult::getId));
        List<Roll> rolls = new ArrayList<>();
        for (DiceRollResult result : results) {
            var reference = new DiceReference(result.getSummaryId(), result.getRoundNo());
            var data = result.getResolutionData();
            if (!visible.containsKey(reference) || data == null || data.getRule() == null || data.getOutcome() == null) continue;
            Object cardId = data.getRule().get("cardId"), category = data.getOutcome().get("category");
            if (!(cardId instanceof Number id) || !investigatorIds.contains(id.longValue()) || !(category instanceof String outcome)) continue;
            try { CocCheckOutcome.valueOf(outcome); } catch (IllegalArgumentException ignored) { continue; }
            var publicData = data.publicView();
            rolls.add(new Roll(id.longValue(), visible.get(reference), publicData.getCheckName(),
                    result.getResultData() == null ? null : result.getResultData().getResult(), publicData.getTargetValue(), outcome));
        }
        return rolls.stream().sorted(Comparator.comparingInt(Roll::turnNo)).toList();
    }
}

package com.me.galchat.service.impl.trpg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.vo.TrpgCombatParticipantOverviewVO;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrpgCombatOverviewService {

    private final GroupConversationMapper conversationMapper;
    private final GroupReplyPlanMapper planMapper;
    private final GroupReplyPlanItemMapper itemMapper;
    private final CocCharacterMapper characterMapper;

    public List<TrpgCombatParticipantOverviewVO> list(
            Long conversationId) {
        GroupConversation conversation = conversationMapper.selectById(
                conversationId);
        if (conversation == null
                || conversation.getActiveReplyPlanId() == null) {
            return List.of();
        }
        GroupReplyPlan plan = planMapper.selectById(
                conversation.getActiveReplyPlanId());
        if (plan == null
                || !GroupChatConstant.PLAN_SOURCE_COMBAT.equals(
                plan.getSource())
                || !conversationId.equals(plan.getConversationId())) {
            return List.of();
        }
        List<GroupReplyPlanItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<GroupReplyPlanItem>()
                        .eq(GroupReplyPlanItem::getPlanId, plan.getId())
                        .orderByAsc(GroupReplyPlanItem::getItemOrder)
                        .orderByAsc(GroupReplyPlanItem::getId));
        LinkedHashSet<Long> participantIds = items.stream()
                .map(GroupReplyPlanItem::getSubjectCharacterId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (participantIds.isEmpty()) {
            return List.of();
        }
        List<CocCharacter> runCharacters = characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId, conversationId)
                        .orderByAsc(CocCharacter::getId));
        Map<Long, CocCharacter> charactersById = runCharacters.stream()
                .filter(character -> character.getId() != null)
                .collect(Collectors.toMap(
                        CocCharacter::getId, Function.identity(),
                        (left, right) -> left));
        Map<Long, String> namesById = runCharacters.stream()
                .filter(character -> character.getId() != null)
                .collect(Collectors.toMap(
                        CocCharacter::getId, CocCharacter::getName,
                        (left, right) -> left));
        return participantIds.stream()
                .map(charactersById::get)
                .filter(java.util.Objects::nonNull)
                .map(character -> overview(character, namesById))
                .toList();
    }

    private TrpgCombatParticipantOverviewVO overview(
            CocCharacter character, Map<Long, String> namesById) {
        boolean investigator = "PLAYER".equals(character.getActorType())
                || "BOT".equals(character.getActorType());
        return new TrpgCombatParticipantOverviewVO(
                character.getId(), character.getName(), investigator,
                statuses(character, namesById),
                investigator ? character.getHpCurrent() : null,
                investigator ? character.getHpMax() : null,
                investigator ? character.getArmor() : null,
                investigator ? character.getDex() : null,
                investigator ? character.getBuild() : null,
                investigator ? character.getMov() : null,
                investigator ? character.getDamageBonus() : null);
    }

    private List<String> statuses(
            CocCharacter character, Map<Long, String> namesById) {
        List<String> statuses = new ArrayList<>();
        addStatus(statuses, character.getMajorWound(), "重伤");
        addStatus(statuses, character.getUnconscious(), "昏迷");
        addStatus(statuses, character.getDying(), "濒死");
        addStatus(statuses, character.getDead(), "死亡");
        addStatus(statuses, character.getInCover(), "处于掩护");
        addStatus(statuses, character.getCoverActionForfeitPending(),
                "下次行动将被跳过");
        if (character.getStunnedRemainingRounds() != null
                && character.getStunnedRemainingRounds() > 0) {
            statuses.add("眩晕（剩余"
                    + character.getStunnedRemainingRounds() + "回合）");
        }
        if (character.getRestrainedByCharacterId() != null) {
            String restrainer = namesById.get(
                    character.getRestrainedByCharacterId());
            statuses.add(restrainer == null || restrainer.isBlank()
                    ? "被钳制" : "被" + restrainer + "钳制");
        }
        addStatus(statuses, character.getMeleeAttackedThisRound(),
                "本轮已遭近战攻击");
        return List.copyOf(statuses);
    }

    private void addStatus(
            List<String> statuses, Boolean active, String label) {
        if (Boolean.TRUE.equals(active)) {
            statuses.add(label);
        }
    }
}

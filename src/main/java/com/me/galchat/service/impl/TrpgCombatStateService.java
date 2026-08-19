package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.domain.dto.KpCombatStateDTOs;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.TrpgCombat;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TrpgCombatStateService {

    private final GroupConversationService conversationService;
    private final TrpgCombatLifecycleService combatLifecycleService;
    private final CocCharacterMapper characterMapper;

    @Transactional(rollbackFor = Exception.class)
    public KpCombatStateDTOs.Result updateCombatStates(
            Long conversationId, KpCombatStateDTOs.Update update) {
        if (update == null || update.changes() == null
                || update.changes().isEmpty()) {
            throw new UserRequestException("至少需要提交一项战斗状态修改");
        }
        GroupConversation conversation =
                conversationService.requireActive(conversationId);
        TrpgCombat combat = combatLifecycleService.requireActiveCombat(
                conversation);
        Set<Long> participantIds = participantIds(combat);
        List<CocCharacter> cards = characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId, conversationId)
                        .in(CocCharacter::getId, participantIds));
        Map<String, CocCharacter> byName = new LinkedHashMap<>();
        Map<Long, CocCharacter> byId = new LinkedHashMap<>();
        for (CocCharacter card : cards == null ? List.<CocCharacter>of()
                : cards) {
            byName.put(card.getName(), card);
            byId.put(card.getId(), card);
        }
        Set<String> changedNames = new HashSet<>();
        List<PreparedChange> prepared = new ArrayList<>();
        for (KpCombatStateDTOs.Change change : update.changes()) {
            if (change == null || !StringUtils.hasText(
                    change.characterName())) {
                throw new UserRequestException("战斗状态目标名称不能为空");
            }
            String name = change.characterName().trim();
            if (!changedNames.add(name)) {
                throw new UserRequestException(
                        "同一人物卡不能重复提交战斗状态：" + name);
            }
            CocCharacter target = byName.get(name);
            if (target == null) {
                throw new UserRequestException(
                        "战斗状态目标不在当前战斗中：" + name);
            }
            if (change.inCover() == null
                    && change.coverActionForfeitPending() == null
                    && change.restrainedByCharacterName() == null) {
                throw new UserRequestException(
                        "人物卡没有需要修改的KP战斗状态：" + name);
            }
            Long restrainedById = target.getRestrainedByCharacterId();
            boolean changesRestraint =
                    change.restrainedByCharacterName() != null;
            if (changesRestraint) {
                String restrainerName =
                        change.restrainedByCharacterName().trim();
                if (restrainerName.isEmpty()) {
                    restrainedById = null;
                } else {
                    CocCharacter restrainer = byName.get(restrainerName);
                    if (restrainer == null) {
                        throw new UserRequestException(
                                "钳制者不在当前战斗中：" + restrainerName);
                    }
                    if (Objects.equals(target.getId(), restrainer.getId())) {
                        throw new UserRequestException("角色不能钳制自己");
                    }
                    restrainedById = restrainer.getId();
                }
            }
            prepared.add(new PreparedChange(
                    target, change.inCover(),
                    change.coverActionForfeitPending(),
                    changesRestraint, restrainedById));
        }

        List<KpCombatStateDTOs.State> states = new ArrayList<>();
        for (PreparedChange change : prepared) {
            CocCharacter target = change.target();
            boolean changed = false;
            if (change.inCover() != null
                    && !Objects.equals(target.getInCover(),
                    change.inCover())) {
                target.setInCover(change.inCover());
                changed = true;
            }
            if (change.coverActionForfeitPending() != null
                    && !Objects.equals(
                    target.getCoverActionForfeitPending(),
                    change.coverActionForfeitPending())) {
                target.setCoverActionForfeitPending(
                        change.coverActionForfeitPending());
                changed = true;
            }
            if (change.changesRestraint()
                    && !Objects.equals(target.getRestrainedByCharacterId(),
                    change.restrainedById())) {
                target.setRestrainedByCharacterId(change.restrainedById());
                changed = true;
            }
            if (changed) {
                target.setUpdatedAt(LocalDateTime.now());
                if (characterMapper.updateById(target) == 0) {
                    throw new UserRequestException("人物卡战斗状态更新失败");
                }
            }
            CocCharacter restrainer = byId.get(
                    target.getRestrainedByCharacterId());
            states.add(new KpCombatStateDTOs.State(
                    target.getName(),
                    Boolean.TRUE.equals(target.getInCover()),
                    Boolean.TRUE.equals(
                            target.getCoverActionForfeitPending()),
                    restrainer == null ? null : restrainer.getName()));
        }
        return new KpCombatStateDTOs.Result(List.copyOf(states));
    }

    private Set<Long> participantIds(TrpgCombat combat) {
        if (combat == null || combat.getParticipants() == null
                || !combat.getParticipants().isArray()) {
            throw new UserRequestException("当前战斗参战者记录不完整");
        }
        Set<Long> result = new HashSet<>();
        combat.getParticipants().forEach(node -> {
            if (node.get("characterId") != null) {
                result.add(node.get("characterId").asLong());
            }
        });
        if (result.isEmpty()) {
            throw new UserRequestException("当前战斗没有参战人物卡");
        }
        return Set.copyOf(result);
    }

    private record PreparedChange(
            CocCharacter target,
            Boolean inCover,
            Boolean coverActionForfeitPending,
            boolean changesRestraint,
            Long restrainedById) {
    }
}

package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.TrpgCombat;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocModuleLocationMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.TrpgCombatMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TrpgNpcContextSelector {

    private static final int RECENT_TURN_COUNT = 2;

    private final CocCharacterMapper characterMapper;
    private final CocModuleLocationMapper locationMapper;
    private final GroupReplyPlanMapper planMapper;
    private final GroupChatTurnMapper turnMapper;
    private final GroupChatMessageMapper messageMapper;
    private final TrpgCombatMapper combatMapper;

    public Set<Long> select(
            GroupConversation conversation, GroupActionSpec action) {
        if (conversation == null || conversation.getId() == null
                || action == null
                || GroupChatConstant.ACTION_TRPG_SCENE_SELECTION.equals(
                action.actionType())) {
            return Set.of();
        }
        List<CocCharacter> npcs = characterMapper.selectList(
                new QueryWrapper<CocCharacter>()
                        .eq("run_id", conversation.getId())
                        .eq("actor_type", "NPC")
                        .orderByAsc("id"));
        if (npcs == null || npcs.isEmpty()) {
            return Set.of();
        }

        Set<Long> selected = new LinkedHashSet<>();
        GroupReplyPlan activePlan = activePlan(conversation);
        matchText(currentSceneContent(conversation, activePlan),
                npcs, selected);
        matchText(recentPublicText(conversation.getId()),
                npcs, selected);
        addCombatParticipants(conversation, activePlan, npcs, selected);
        addNpcId(action.subjectCharacterId(), npcs, selected);
        return Set.copyOf(selected);
    }

    private GroupReplyPlan activePlan(GroupConversation conversation) {
        return conversation.getActiveReplyPlanId() == null
                ? null : planMapper.selectById(
                conversation.getActiveReplyPlanId());
    }

    private String currentSceneContent(
            GroupConversation conversation, GroupReplyPlan activePlan) {
        GroupReplyPlan scene = scenePlan(activePlan);
        if (scene == null || scene.getContextId() == null) {
            return "";
        }
        CocModuleLocation location = locationMapper.selectById(
                scene.getContextId());
        if (location == null || !Objects.equals(
                location.getModuleId(), conversation.getModuleId())) {
            return "";
        }
        return location.getContent();
    }

    private GroupReplyPlan scenePlan(GroupReplyPlan activePlan) {
        GroupReplyPlan current = activePlan;
        if (current != null
                && GroupChatConstant.PLAN_SOURCE_COMBAT.equals(
                current.getSource())) {
            current = current.getResumePlanId() == null
                    ? null : planMapper.selectById(
                    current.getResumePlanId());
        }
        Set<Long> visited = new HashSet<>();
        while (current != null && current.getParentPlanId() != null) {
            if (current.getId() == null || !visited.add(current.getId())) {
                return null;
            }
            current = planMapper.selectById(current.getParentPlanId());
        }
        return current != null
                && GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                current.getSource()) ? current : null;
    }

    private String recentPublicText(Long conversationId) {
        List<GroupChatTurn> turns = turnMapper.selectList(
                new QueryWrapper<GroupChatTurn>()
                        .select("id")
                        .eq("conversation_id", conversationId)
                        .orderByDesc("id")
                        .last("limit " + RECENT_TURN_COUNT));
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        List<Long> turnIds = turns.stream()
                .map(GroupChatTurn::getId)
                .filter(Objects::nonNull)
                .toList();
        if (turnIds.isEmpty()) {
            return "";
        }
        List<GroupChatMessage> messages = messageMapper.selectList(
                new QueryWrapper<GroupChatMessage>()
                        .select("content")
                        .eq("conversation_id", conversationId)
                        .in("turn_id", turnIds)
                        .eq("status",
                                GroupChatConstant.STATUS_COMPLETED)
                        .eq("visibility", "public")
                        .orderByAsc("sequence_no"));
        if (messages == null) {
            return "";
        }
        return messages.stream()
                .map(GroupChatMessage::getContent)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n"));
    }

    private void addCombatParticipants(
            GroupConversation conversation,
            GroupReplyPlan activePlan,
            List<CocCharacter> npcs,
            Set<Long> selected) {
        if (activePlan == null
                || !GroupChatConstant.PLAN_SOURCE_COMBAT.equals(
                activePlan.getSource())
                || activePlan.getContextId() == null) {
            return;
        }
        TrpgCombat combat = combatMapper.selectById(
                activePlan.getContextId());
        if (combat == null || !Objects.equals(
                combat.getConversationId(), conversation.getId())
                || combat.getParticipants() == null) {
            return;
        }
        Set<Long> npcIds = npcs.stream()
                .map(CocCharacter::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        combat.getParticipants().forEach(node -> {
            if (node.get("characterId") == null) {
                return;
            }
            long characterId = node.get("characterId").asLong();
            if (npcIds.contains(characterId)) {
                selected.add(characterId);
            }
        });
    }

    private void addNpcId(
            Long characterId,
            List<CocCharacter> npcs,
            Set<Long> selected) {
        if (characterId != null && npcs.stream().anyMatch(
                npc -> characterId.equals(npc.getId()))) {
            selected.add(characterId);
        }
    }

    private void matchText(
            String text,
            List<CocCharacter> npcs,
            Set<Long> selected) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        String normalizedText = normalize(text);
        List<NpcPattern> patterns = npcs.stream()
                .filter(npc -> npc.getId() != null
                        && StringUtils.hasText(npc.getName()))
                .map(npc -> new NpcPattern(
                        npc.getId(), normalize(npc.getName())))
                .sorted(java.util.Comparator.comparingInt(
                        (NpcPattern pattern) -> pattern.name().length())
                        .reversed())
                .toList();
        List<TextRange> accepted = new ArrayList<>();
        for (NpcPattern pattern : patterns) {
            int from = 0;
            while (from < normalizedText.length()) {
                int start = normalizedText.indexOf(pattern.name(), from);
                if (start < 0) {
                    break;
                }
                TextRange candidate = new TextRange(
                        start, start + pattern.name().length());
                boolean contained = accepted.stream()
                        .anyMatch(range -> range.contains(candidate));
                if (!contained) {
                    selected.add(pattern.characterId());
                    accepted.add(candidate);
                    break;
                }
                from = start + 1;
            }
        }
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('•', '·')
                .replace('・', '·')
                .replace('‧', '·')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private record NpcPattern(Long characterId, String name) {
    }

    private record TextRange(int start, int end) {
        private boolean contains(TextRange other) {
            return start <= other.start && end >= other.end;
        }
    }
}

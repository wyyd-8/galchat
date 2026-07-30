package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.TrpgCombat;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.TrpgCombatMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TrpgCombatSnapshotService {

    private final GroupConversationMapper conversationMapper;
    private final TrpgCombatMapper combatMapper;

    public List<TrpgCombat> capture(Long userWorldId) {
        List<Long> conversationIds = conversationIds(userWorldId);
        if (conversationIds.isEmpty()) {
            return List.of();
        }
        return combatMapper.selectList(
                new LambdaQueryWrapper<TrpgCombat>()
                        .in(TrpgCombat::getConversationId,
                                conversationIds)
                        .orderByAsc(TrpgCombat::getId));
    }

    public void restore(
            Long userWorldId, List<TrpgCombat> snapshots) {
        List<Long> conversationIds = conversationIds(userWorldId);
        if (!conversationIds.isEmpty()) {
            combatMapper.delete(
                    new LambdaQueryWrapper<TrpgCombat>()
                            .in(TrpgCombat::getConversationId,
                                    conversationIds));
        }
        if (snapshots == null) {
            return;
        }
        for (TrpgCombat combat : snapshots) {
            if (combat != null
                    && conversationIds.contains(
                    combat.getConversationId())) {
                combatMapper.insert(combat);
            }
        }
    }

    private List<Long> conversationIds(Long userWorldId) {
        return conversationMapper.selectList(
                        new LambdaQueryWrapper<GroupConversation>()
                                .eq(GroupConversation::getUserWorldId,
                                        userWorldId))
                .stream().map(GroupConversation::getId).toList();
    }
}

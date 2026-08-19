package com.me.galchat.service.impl;

import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrpgCombatOverviewServiceTest {

    @Test
    void exposesCombatBasicsOnlyForParticipatingInvestigators() {
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper =
                mock(GroupReplyPlanItemMapper.class);
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        TrpgCombatOverviewService service = new TrpgCombatOverviewService(
                conversationMapper, planMapper, itemMapper, characterMapper);
        when(conversationMapper.selectById(7L)).thenReturn(
                new GroupConversation().setId(7L)
                        .setMode("trpg").setActiveReplyPlanId(40L));
        when(planMapper.selectById(40L)).thenReturn(
                new GroupReplyPlan().setId(40L).setConversationId(7L)
                        .setSource("COMBAT"));
        when(itemMapper.selectList(any())).thenReturn(List.of(
                item(1, "user", 501L, "林恩"),
                item(2, "kp", 601L, "食尸鬼"),
                new GroupReplyPlanItem().setItemOrder(3)
                        .setActorType("kp")));
        when(characterMapper.selectList(any())).thenReturn(List.of(
                investigator(501L), npc(601L),
                npc(699L).setName("未参战的深潜者")));

        var overview = service.list(7L);

        assertThat(overview).hasSize(2);
        assertThat(overview.getFirst())
                .extracting(
                        item -> item.characterId(),
                        item -> item.name(),
                        item -> item.investigator(),
                        item -> item.hpCurrent(),
                        item -> item.hpMax(),
                        item -> item.armor(),
                        item -> item.dex(),
                        item -> item.build(),
                        item -> item.mov(),
                        item -> item.damageBonus())
                .containsExactly(
                        501L, "林恩", true, 7, 12, 2, 65, 0, 8, "0");
        assertThat(overview.getFirst().statuses())
                .containsExactly("重伤", "处于掩护", "下次行动将被跳过");

        assertThat(overview.get(1).investigator()).isFalse();
        assertThat(overview.get(1).statuses())
                .containsExactly("眩晕（剩余2回合）", "被林恩钳制", "本轮已遭近战攻击");
        assertThat(overview.get(1))
                .extracting(
                        item -> item.hpCurrent(),
                        item -> item.hpMax(),
                        item -> item.armor(),
                        item -> item.dex(),
                        item -> item.build(),
                        item -> item.mov(),
                        item -> item.damageBonus())
                .containsOnlyNulls();
        assertThat(overview).extracting(item -> item.name())
                .doesNotContain("未参战的深潜者");
    }

    private GroupReplyPlanItem item(
            int order, String actorType, Long subjectId, String name) {
        return new GroupReplyPlanItem().setItemOrder(order)
                .setActorType(actorType)
                .setSubjectCharacterId(subjectId)
                .setSubjectCharacterName(name)
                .setParticipantStatus("ACTIVE");
    }

    private CocCharacter investigator(Long id) {
        return basics(id, "PLAYER", "林恩")
                .setMajorWound(true)
                .setInCover(true)
                .setCoverActionForfeitPending(true);
    }

    private CocCharacter npc(Long id) {
        return basics(id, "NPC", "食尸鬼")
                .setStunnedRemainingRounds(2)
                .setRestrainedByCharacterId(501L)
                .setMeleeAttackedThisRound(true);
    }

    private CocCharacter basics(Long id, String actorType, String name) {
        return new CocCharacter().setId(id).setRunId(7L)
                .setActorType(actorType).setName(name)
                .setHpCurrent(7).setHpMax(12).setArmor(2)
                .setDex(65).setBuild(0).setMov(8)
                .setDamageBonus("0");
    }
}

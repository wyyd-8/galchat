package com.me.galchat.service.impl;

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
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrpgNpcContextSelectorTest {

    @Test
    void selectsNpcsMentionedByCurrentSceneAndRecentTwoTurns() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = fixture.sceneConversation();
        when(fixture.characterMapper.selectList(any())).thenReturn(List.of(
                npc(81L, "乔瑟夫·特纳"),
                npc(82L, "食尸鬼"),
                new CocCharacter().setId(9L).setActorType("BOT")
                        .setName("艾琳")));
        when(fixture.turnMapper.selectList(any())).thenReturn(List.of(
                new GroupChatTurn().setId(101L),
                new GroupChatTurn().setId(100L)));
        when(fixture.messageMapper.selectList(any())).thenReturn(List.of(
                new GroupChatMessage().setContent("墓穴附近出现了食尸鬼脚印。")));
        when(fixture.locationMapper.selectById(21L)).thenReturn(
                new CocModuleLocation().setId(21L).setModuleId(3L)
                        .setContent("乔瑟夫•特纳正在黑湖边等待。"));

        assertThat(fixture.selector.select(
                conversation, action(null)))
                .containsExactlyInAnyOrder(81L, 82L);
    }

    @Test
    void combatParticipantsAndBoundSubjectRemainRelevantWithoutMentions() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = fixture.sceneConversation()
                .setActiveReplyPlanId(30L);
        when(fixture.characterMapper.selectList(any())).thenReturn(List.of(
                npc(81L, "乔瑟夫·特纳"),
                npc(82L, "食尸鬼"),
                npc(83L, "守墓人")));
        when(fixture.planMapper.selectById(30L)).thenReturn(
                new GroupReplyPlan().setId(30L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_COMBAT)
                        .setContextId(40L).setResumePlanId(10L));
        when(fixture.planMapper.selectById(10L)).thenReturn(
                new GroupReplyPlan().setId(10L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                        .setContextId(21L));
        when(fixture.locationMapper.selectById(21L)).thenReturn(
                new CocModuleLocation().setId(21L).setModuleId(3L)
                        .setContent("空旷的墓园。"));
        ArrayNode participants = fixture.objectMapper.createArrayNode();
        participants.addObject().put("characterId", 82L);
        when(fixture.combatMapper.selectById(40L)).thenReturn(
                new TrpgCombat().setId(40L).setConversationId(7L)
                        .setParticipants(participants));
        when(fixture.turnMapper.selectList(any())).thenReturn(List.of());

        assertThat(fixture.selector.select(
                conversation, action(83L)))
                .containsExactlyInAnyOrder(82L, 83L);
    }

    @Test
    void sceneSelectionDoesNotLoadActiveNpcDetails() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = fixture.sceneConversation();
        when(fixture.characterMapper.selectList(any())).thenReturn(List.of(
                npc(81L, "乔瑟夫·特纳")));
        when(fixture.locationMapper.selectById(21L)).thenReturn(
                new CocModuleLocation().setId(21L).setModuleId(3L)
                        .setContent("乔瑟夫·特纳正在等待。"));

        GroupActionSpec selection = new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE_SELECTION,
                GroupChatConstant.ACTOR_KP,
                null, "scene-selection", "选景", 1, 1);

        assertThat(fixture.selector.select(conversation, selection))
                .isEmpty();
    }

    @Test
    void longerExactNameSuppressesContainedShortNameAtSameOccurrence() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = fixture.sceneConversation();
        when(fixture.characterMapper.selectList(any())).thenReturn(List.of(
                npc(81L, "乔瑟夫·特纳"),
                npc(82L, "特纳")));
        when(fixture.locationMapper.selectById(21L)).thenReturn(
                new CocModuleLocation().setId(21L).setModuleId(3L)
                        .setContent("乔瑟夫·特纳正在黑湖边等待。"));
        when(fixture.turnMapper.selectList(any())).thenReturn(List.of());

        assertThat(fixture.selector.select(conversation, action(null)))
                .containsExactly(81L);
    }

    private static CocCharacter npc(Long id, String name) {
        return new CocCharacter().setId(id).setRunId(7L)
                .setActorType("NPC").setName(name);
    }

    private static GroupActionSpec action(Long subjectId) {
        return new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE,
                GroupChatConstant.ACTOR_KP,
                null, subjectId, "scene:1", "墓园", 1, 1);
    }

    private static final class Fixture {
        private final CocCharacterMapper characterMapper =
                mock(CocCharacterMapper.class);
        private final CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        private final GroupReplyPlanMapper planMapper =
                mock(GroupReplyPlanMapper.class);
        private final GroupChatTurnMapper turnMapper =
                mock(GroupChatTurnMapper.class);
        private final GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        private final TrpgCombatMapper combatMapper =
                mock(TrpgCombatMapper.class);
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final TrpgNpcContextSelector selector =
                new TrpgNpcContextSelector(
                        characterMapper, locationMapper, planMapper,
                        turnMapper, messageMapper, combatMapper);

        private GroupConversation sceneConversation() {
            when(planMapper.selectById(10L)).thenReturn(
                    new GroupReplyPlan().setId(10L)
                            .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                            .setContextId(21L));
            return new GroupConversation().setId(7L).setModuleId(3L)
                    .setActiveReplyPlanId(10L);
        }
    }
}

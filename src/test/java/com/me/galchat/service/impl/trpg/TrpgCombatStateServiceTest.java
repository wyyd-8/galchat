package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.domain.dto.KpCombatStateDTOs;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.TrpgCombat;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgCombatStateServiceTest {

    private GroupConversationService conversationService;
    private TrpgCombatLifecycleService combatLifecycleService;
    private CocCharacterMapper characterMapper;
    private TrpgCombatStateService service;
    private CocCharacter target;
    private CocCharacter restrainer;

    @BeforeEach
    void setUp() {
        conversationService = mock(GroupConversationService.class);
        combatLifecycleService = mock(TrpgCombatLifecycleService.class);
        characterMapper = mock(CocCharacterMapper.class);
        service = new TrpgCombatStateService(
                conversationService, combatLifecycleService,
                characterMapper);
        GroupConversation conversation = new GroupConversation().setId(7L);
        target = new CocCharacter()
                .setId(71L).setRunId(7L).setName("林恩")
                .setInCover(false)
                .setCoverActionForfeitPending(false)
                .setStunnedRemainingRounds(3)
                .setMeleeAttackedThisRound(true);
        restrainer = new CocCharacter()
                .setId(72L).setRunId(7L).setName("食尸鬼");
        ArrayNode participants = JsonMapper.builder().build()
                .createArrayNode();
        participants.addObject().put("characterId", 71L);
        participants.addObject().put("characterId", 72L);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(combatLifecycleService.requireActiveCombat(conversation))
                .thenReturn(new TrpgCombat().setConversationId(7L)
                        .setParticipants(participants));
        when(characterMapper.selectList(any()))
                .thenReturn(List.of(target, restrainer));
        when(characterMapper.updateById(any(CocCharacter.class)))
                .thenReturn(1);
    }

    @Test
    void kpCanUpdateOnlyExplicitCombatConditionsByCharacterName() {
        KpCombatStateDTOs.Result result = service.updateCombatStates(
                7L, new KpCombatStateDTOs.Update(List.of(
                        new KpCombatStateDTOs.Change(
                                "林恩", true, true, "食尸鬼"))));

        assertThat(target.getInCover()).isTrue();
        assertThat(target.getCoverActionForfeitPending()).isTrue();
        assertThat(target.getRestrainedByCharacterId()).isEqualTo(72L);
        assertThat(target.getStunnedRemainingRounds()).isEqualTo(3);
        assertThat(target.getMeleeAttackedThisRound()).isTrue();
        assertThat(result.states()).singleElement()
                .satisfies(state -> {
                    assertThat(state.characterName()).isEqualTo("林恩");
                    assertThat(state.inCover()).isTrue();
                    assertThat(state.coverActionForfeitPending()).isTrue();
                    assertThat(state.restrainedByCharacterName())
                            .isEqualTo("食尸鬼");
                });
        verify(characterMapper).updateById(target);
    }

    @Test
    void emptyRestrainerNameClearsRestraintWithoutChangingOtherStates() {
        target.setInCover(true)
                .setCoverActionForfeitPending(true)
                .setRestrainedByCharacterId(72L);

        service.updateCombatStates(7L,
                new KpCombatStateDTOs.Update(List.of(
                        new KpCombatStateDTOs.Change(
                                "林恩", null, null, ""))));

        assertThat(target.getRestrainedByCharacterId()).isNull();
        assertThat(target.getInCover()).isTrue();
        assertThat(target.getCoverActionForfeitPending()).isTrue();
    }

    @Test
    void rejectsRestrainerOutsideTheActiveCombat() {
        assertThatThrownBy(() -> service.updateCombatStates(
                7L, new KpCombatStateDTOs.Update(List.of(
                        new KpCombatStateDTOs.Change(
                                "林恩", null, null, "局外人")))))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("钳制者不在当前战斗中：局外人");
        verify(characterMapper, never()).updateById(
                any(CocCharacter.class));
    }
}

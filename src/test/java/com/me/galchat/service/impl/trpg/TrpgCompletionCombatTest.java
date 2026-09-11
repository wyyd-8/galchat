package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.po.*;
import com.me.galchat.mapper.*;
import com.me.galchat.groupchat.dice.DiceRollMessageCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrpgCompletionCombatTest {
    @Mock GroupContextSummaryMapper summaries;
    @Mock TrpgSummaryIntervalSelector intervals;
    @Mock GroupChatMessageMapper messages;
    @Mock GroupChatTurnMapper turns;
    @Mock CocCharacterMapper characters;
    @Mock CocModuleMapper modules;
    @Mock TrpgAutoSaveMapper saves;
    @Mock DiceRollResultMapper dice;
    @Mock DiceRollMessageCodec codec;
    @Mock TrpgEpilogueService epilogues;
    @Mock TrpgCombatMapper combats;
    @Mock CocModuleLocationMapper locations;
    @InjectMocks TrpgCompletionMaterialsService service;

    @Test
    void capturesSceneNamesAfterRuntimePlansHaveBeenDeleted() {
        when(messages.selectList(any())).thenReturn(List.of(new GroupChatMessage().setSequenceNo(90L)));
        when(combats.selectList(any())).thenReturn(List.of(
                new TrpgCombat().setId(11L).setSourceSceneId(21L).setSummary("战斗结果：\n1. 第一场裁定。"),
                new TrpgCombat().setId(12L).setSourceSceneId(21L).setSummary("战斗结果：\n1. 第二场裁定。"),
                new TrpgCombat().setId(13L).setSummary(null)));
        // Completed combat and scene plans have been deleted by the normal lifecycle.
        when(locations.selectList(any())).thenReturn(List.of(
                new CocModuleLocation().setId(21L).setName("林间营地")));
        var captured = service.capture(new GroupConversation().setId(7L).setTitle("旅程"), 9L);
        var data = JsonMapper.builder().build().valueToTree(captured).path("combats");
        assertThat(data.size()).isEqualTo(3);
        assertThat(data.get(0).path("sceneName").asText()).isEqualTo("林间营地");
        assertThat(data.get(1).path("sceneName").asText()).isEqualTo("林间营地");
        assertThat(data.get(0).path("summary").asText()).isEqualTo("战斗结果：\n1. 第一场裁定。");
        assertThat(data.get(1).path("combatId").asLong()).isEqualTo(12L);
        assertThat(data.get(2).path("sceneName").isNull()).isTrue();
        assertThat(data.get(2).path("summary").isNull()).isTrue();
    }
}

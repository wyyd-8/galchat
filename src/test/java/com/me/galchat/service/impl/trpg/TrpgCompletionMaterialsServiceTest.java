package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.po.*;
import com.me.galchat.domain.vo.*;
import com.me.galchat.groupchat.dice.DiceRollMessageCodec;
import com.me.galchat.mapper.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrpgCompletionMaterialsServiceTest {
    @Test
    void publicRollsExcludeHiddenRoundsNpcsAndDamageAndCountRepeatedReferencesOnce() {
        var mapper = mock(DiceRollResultMapper.class);
        var codec = new DiceRollMessageCodec(JsonMapper.builder().build());
        var service = new TrpgCompletionMaterialsService(mock(GroupContextSummaryMapper.class), new TrpgSummaryIntervalSelector(), mock(GroupChatMessageMapper.class),
                mock(GroupChatTurnMapper.class), mock(CocCharacterMapper.class), mock(CocModuleMapper.class),
                mock(TrpgAutoSaveMapper.class), mapper, codec, mock(TrpgEpilogueService.class));
        var message = new GroupChatMessage().setTurnId(9L).setMessageKind("dice_roll").setContent(codec.encode(10L, List.of(1, 2)));
        when(mapper.selectList(any())).thenReturn(List.of(
                result(1L, 1, 11L, "CRITICAL_SUCCESS", "REGULAR"),
                result(2L, 1, 12L, "SUCCESS", "HARD"),
                result(3L, 2, 11L, "FUMBLE", "REGULAR"),
                result(4L, 2, 99L, "SUCCESS", "REGULAR"),
                result(5L, 3, 11L, "SUCCESS", "REGULAR"),
                result(6L, 2, 11L, null, "REGULAR")));
        var rolls = service.rolls(List.of(message, message), Set.of(11L, 12L), Map.of(9L, 3));
        assertThat(rolls).hasSize(3);
        assertThat(rolls).extracting(roll -> roll.outcome()).containsExactly("CRITICAL_SUCCESS", "SUCCESS", "FUMBLE");
        assertThat(rolls.get(1).target()).isEqualTo(30);
        assertThat(rolls).allSatisfy(roll -> assertThat(roll.turnNo()).isEqualTo(3));
        assertThat(service.rolls(List.of(), Set.of(11L), Map.of())).isEmpty();
    }

    private DiceRollResult result(long id, int round, long cardId, String outcome, String difficulty) {
        return new DiceRollResult().setId(id).setSummaryId(10L).setRoundNo(round).setResolvedAt(LocalDateTime.now())
                .setResultData(new DiceRollResultVO("1d100", List.of(), 1))
                .setResolutionData(new DiceResolutionDataVO()
                        .setRule(Map.of("cardId", cardId, "checkName", "侦查", "targetValue", 60, "difficulty", difficulty))
                        .setOutcome(outcome == null ? Map.of("damage", 4) : Map.of("category", outcome)));
    }
}

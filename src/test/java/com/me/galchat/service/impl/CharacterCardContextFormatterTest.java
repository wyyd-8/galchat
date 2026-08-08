package com.me.galchat.service.impl;

import com.me.galchat.domain.vo.CocDiceCharacterVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CharacterCardContextFormatterTest {

    private final CharacterCardContextFormatter formatter = new CharacterCardContextFormatter();

    @Test
    void formatsStoredInsanityCodeAsReadableCardContext() {
        CocDiceCharacterVO card = new CocDiceCharacterVO(
                71L, "PLAYER", null, "林恩", Map.of("CON", 55),
                10, 10, 54, 60, 55, 0,
                false, false, false, false,
                true, "9:037", 4);

        String text = formatter.format(List.of(card));

        assertThat(text)
                .contains("<investigator-card")
                .contains("林恩")
                .contains("临时疯狂：恐惧症（昆虫恐惧症：害怕昆虫）")
                .contains("剩余4小时");
    }

    @Test
    void invalidLegacyInsanityCodeUsesStableFallback() {
        CocDiceCharacterVO card = new CocDiceCharacterVO(
                71L, "PLAYER", null, "林恩", Map.of(),
                10, 10, 54, 60, 55, 0,
                false, false, false, false,
                true, "legacy", 2);

        assertThat(formatter.format(List.of(card)))
                .contains("临时疯狂（编号：legacy）")
                .contains("剩余2小时");
    }

    @Test
    void npcRosterKeepsExactNamesButOnlyPublishesChangedRuntimeState() {
        CocDiceCharacterVO normal = new CocDiceCharacterVO(
                81L, "NPC", null, "乔瑟夫·特纳",
                Map.of("DEX", 55, "斗殴", 50),
                20, 20, 0, 0, 120, 0,
                false, false, false, false,
                false, null, null);
        CocDiceCharacterVO wounded = new CocDiceCharacterVO(
                82L, "NPC", null, "亨利·沃尔特斯",
                Map.of("DEX", 15, "斗殴", 40),
                8, 17, 0, 0, 105, 0,
                true, false, false, false,
                false, null, null);

        String text = formatter.formatNpcs(List.of(normal, wounded));

        assertThat(text)
                .contains("<npc-roster>")
                .contains("乔瑟夫·特纳", "亨利·沃尔特斯")
                .contains("<npc-state-changes>")
                .contains("亨利·沃尔特斯：HP 8/17；重伤")
                .doesNotContain("乔瑟夫·特纳：HP")
                .doesNotContain("检定值")
                .doesNotContain("DEX=", "斗殴=");
    }
}

package com.me.galchat.domain.po;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CocCharacterPrivacyTest {

    @Test
    void quickNotesAreNeverSerializedToCharacterCardClients() {
        CocCharacter character = new CocCharacter()
                .setId(7L)
                .setName("林恩")
                .setQuickNotes("已被感染");

        String json = JsonMapper.builder().build()
                .writeValueAsString(character);

        assertThat(json).doesNotContain(
                "quickNotes", "已被感染");
    }
}

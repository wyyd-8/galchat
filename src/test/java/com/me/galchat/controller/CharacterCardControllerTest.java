package com.me.galchat.controller;

import com.me.galchat.service.impl.character.CharacterSkillResolver;
import com.me.galchat.service.impl.character.ImportedWeaponAuditQueue;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.mapper.CharacterTemplateMapper;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.CocCharacterSkillMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.CocSkillDefMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.UserInfoMapper;
import com.me.galchat.service.impl.character.CharacterCardServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CharacterCardControllerTest {

    private CocCharacterMapper characterMapper;
    private CocCharacterSkillMapper skillMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        characterMapper = mock(CocCharacterMapper.class);
        skillMapper = mock(CocCharacterSkillMapper.class);
        CharacterCardServiceImpl service = new CharacterCardServiceImpl(
                characterMapper,
                skillMapper,
                mock(CocCharacterWeaponMapper.class),
                mock(CocCharacterProfileMapper.class),
                mock(CocSkillDefMapper.class),
                new CharacterSkillResolver(),
                mock(CharacterTemplateMapper.class),
                mock(UserInfoMapper.class),
                mock(GroupConversationMapper.class),
                mock(ImportedWeaponAuditQueue.class));
        mockMvc = MockMvcBuilders.standaloneSetup(
                new CharacterCardController(service)).build();
    }

    @Test
    void listsOnlyInvestigatorSummariesByRunId() throws Exception {
        when(characterMapper.selectList(any())).thenReturn(List.of(
                character(1L, "PLAYER", null, "林恩"),
                character(2L, "BOT", 9L, "艾琳"),
                character(3L, "NPC", null, "食尸鬼")));
        when(skillMapper.selectList(any())).thenReturn(List.of());

        mockMvc.perform(get("/character-cards/investigators")
                        .param("runId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].cardId").value(1))
                .andExpect(jsonPath("$.data[0].actorType").value("PLAYER"))
                .andExpect(jsonPath("$.data[1].cardId").value(2))
                .andExpect(jsonPath("$.data[1].actorType").value("BOT"));
    }

    @Test
    void rootGetNoLongerQueriesByRunAndParticipantId() throws Exception {
        when(characterMapper.selectOne(any())).thenReturn(
                character(2L, "BOT", 9L, "艾琳"));
        when(skillMapper.selectList(any())).thenReturn(List.of());

        mockMvc.perform(get("/character-cards")
                        .param("runId", "5")
                        .param("participantId", "9"))
                .andExpect(status().isMethodNotAllowed());
    }

    private CocCharacter character(
            Long id, String actorType, Long participantId, String name) {
        return new CocCharacter()
                .setId(id)
                .setRunId(5L)
                .setActorType(actorType)
                .setParticipantId(participantId)
                .setName(name);
    }
}

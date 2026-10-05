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
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.po.UserInfo;
import com.me.galchat.mapper.UserWorldPrefixMapper;
import com.me.galchat.service.impl.character.CharacterCardAccessService;
import com.me.galchat.exception.GlobalExceptionHandler;
import com.me.galchat.utils.CurrentHolder;
import com.me.galchat.support.MybatisPlusTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    private GroupConversationMapper conversationMapper;
    private UserWorldPrefixMapper worldMapper;
    private UserInfoMapper userInfoMapper;

    @BeforeEach
    void setUp() {
        MybatisPlusTestSupport.initialize(CocCharacter.class);
        CurrentHolder.setCurrentId(1);
        userInfoMapper = mock(UserInfoMapper.class);
        when(userInfoMapper.selectById(1L)).thenReturn(new UserInfo().setId(1L).setUsername("owner"));
        worldMapper = mock(UserWorldPrefixMapper.class);
        when(worldMapper.selectById(3L)).thenReturn(new UserWorldPrefix().setId(3L).setUserId(1L));
        conversationMapper = mock(GroupConversationMapper.class);
        when(conversationMapper.selectById(5L)).thenReturn(new GroupConversation().setId(5L).setUserWorldId(3L).setMode("trpg"));
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
                userInfoMapper,
                conversationMapper,
                mock(ImportedWeaponAuditQueue.class));
        mockMvc = MockMvcBuilders.standaloneSetup(
                new CharacterCardController(service, new CharacterCardAccessService(characterMapper, conversationMapper, worldMapper))).setControllerAdvice(new GlobalExceptionHandler()).build();
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @AfterEach
    void clearIdentity() { CurrentHolder.remove(); }

    @ParameterizedTest
    @ValueSource(strings = {"read", "list", "delete", "luck", "create"})
    void rejectsOtherAccountsBeforeReturningOrMutatingCards(String operation) throws Exception {
        CurrentHolder.setCurrentId(2);
        when(characterMapper.selectById(71L)).thenReturn(character(71L, "PLAYER", null, "私有人物卡"));
        mockMvc.perform(request(operation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("无权访问该跑团"));
        org.mockito.Mockito.verify(characterMapper, org.mockito.Mockito.never()).deleteById(71L);
        org.mockito.Mockito.verify(characterMapper, org.mockito.Mockito.never()).insert(any(CocCharacter.class));
        org.mockito.Mockito.verifyNoInteractions(skillMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "list", "delete", "luck", "create"})
    void requiresIdentityEvenWhenCalledWithoutTheTokenInterceptor(String operation) throws Exception {
        CurrentHolder.remove();
        when(characterMapper.selectById(71L)).thenReturn(character(71L, "PLAYER", null, "私有人物卡"));
        mockMvc.perform(request(operation))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("用户未登录"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "delete", "luck"})
    void allowsOwnerToReadDeleteAndRollLuck(String operation) throws Exception {
        when(characterMapper.selectById(71L)).thenReturn(character(71L, "PLAYER", null, "本人人物卡"));
        when(characterMapper.update(org.mockito.ArgumentMatchers.isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        mockMvc.perform(request(operation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
        if (operation.equals("delete")) org.mockito.Mockito.verify(characterMapper).deleteById(71L);
    }

    @Test
    void allowsOwnerToImportIntoTheirRun() throws Exception {
        java.util.concurrent.atomic.AtomicReference<CocCharacter> saved = new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            CocCharacter card = invocation.getArgument(0);
            card.setId(71L);
            saved.set(card);
            return 1;
        }).when(characterMapper).insert(any(CocCharacter.class));
        when(characterMapper.selectById(71L)).thenAnswer(invocation -> saved.get());
        String text = """
                林恩，记者，女，30岁
                出身波士顿，现居阿卡姆
                时代: 现代
                STR 50 CON 50 SIZ 50 DEX 50
                APP 50 INT 50 POW 50 EDU 50
                """;
        String body = new tools.jackson.databind.json.JsonMapper().writeValueAsString(java.util.Map.of("runId", 5, "characterText", text));
        mockMvc.perform(post("/character-cards").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.character.runId").value(5))
                .andExpect(jsonPath("$.data.character.playerName").value("owner"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "list", "delete", "luck", "create"})
    void rejectsCardsWhoseOwningWorldNoLongerExists(String operation) throws Exception {
        when(worldMapper.selectById(3L)).thenReturn(null);
        when(characterMapper.selectById(71L)).thenReturn(character(71L, "PLAYER", null, "孤立人物卡"));
        mockMvc.perform(request(operation))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("无权访问该跑团"));
        org.mockito.Mockito.verifyNoInteractions(skillMapper);
    }

    private MockHttpServletRequestBuilder request(String operation) {
        return switch (operation) {
            case "read" -> get("/character-cards/71");
            case "list" -> get("/character-cards/investigators").param("runId", "5");
            case "delete" -> delete("/character-cards/71");
            case "luck" -> post("/character-cards/71/luck");
            case "create" -> post("/character-cards").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"runId\":5,\"characterText\":\"unparsed private import\"}");
            default -> throw new IllegalArgumentException(operation);
        };
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

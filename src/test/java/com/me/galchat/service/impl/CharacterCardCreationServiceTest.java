package com.me.galchat.service.impl;

import com.me.galchat.domain.dto.CharacterCardGenerationModels;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.CocCharacterCreationDraft;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.CocSkillDef;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.mapper.CharacterTemplateMapper;
import com.me.galchat.mapper.CocCharacterCreationDraftMapper;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.CocCharacterSkillMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.CocModuleMapper;
import com.me.galchat.mapper.CocSkillDefMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.UserWorldPrefixMapper;
import com.me.galchat.utils.CurrentHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CharacterCardCreationServiceTest {

    private CocCharacterCreationDraftMapper draftMapper;
    private CocCharacterMapper characterMapper;
    private CocCharacterSkillMapper skillMapper;
    private CocCharacterWeaponMapper weaponMapper;
    private CocCharacterProfileMapper profileMapper;

    @org.junit.jupiter.api.BeforeAll
    static void initMybatisPlusTableInfo() {
        com.me.galchat.support.MybatisPlusTestSupport.initialize(
                CocCharacterCreationDraft.class);
    }

    @BeforeEach
    void setCurrentUser() {
        CurrentHolder.setCurrentId(7);
    }

    @AfterEach
    void clearCurrentUser() {
        CurrentHolder.remove();
    }

    @Test
    void createAutoDraftBuildsPreviewWithoutCreatingFormalCharacter() {
        MutableGenerationModel model = new MutableGenerationModel();
        CharacterCardCreationService service = service(model,
                new SequenceRandom(4, 4, 4, 50, 3, 6, 2, 4, 4, 5));

        var result = service.createAuto(new CharacterCardGenerationModels.CreateRequest(
                101L, 12L, "create-1"));

        assertThat(result.status()).isEqualTo("PREVIEW_READY");
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.state().preview().getCharacter().getName()).isEqualTo("埃莉诺·克劳福德");
        assertThat(result.state().preview().getProfile().getIdeology()).isEqualTo("科学终将解释一切");
        assertThat(result.state().preview().getSkills())
                .allSatisfy(skill -> assertThat(skill.getValue())
                        .isNotEqualTo(skill.getBaseValue()))
                .extracting(com.me.galchat.domain.po.CocCharacterSkill::getDisplayName)
                .doesNotContain("克苏鲁神话", "母语", "聆听", "锁匠", "射击:手枪");
        verify(characterMapper, never()).insert(any(com.me.galchat.domain.po.CocCharacter.class));
        ArgumentCaptor<CocCharacterCreationDraft> saved =
                ArgumentCaptor.forClass(CocCharacterCreationDraft.class);
        verify(draftMapper).insert(saved.capture());
        assertThat(saved.getValue().getCreationMode()).isEqualTo("AUTO_QUICK_START");
        assertThat(saved.getValue().getCurrentStep()).isEqualTo("PREVIEW");
    }

    @Test
    void rewriteBackgroundReplacesBackgroundAndLoadoutWithoutChangingBuild() {
        MutableGenerationModel model = new MutableGenerationModel();
        CharacterCardCreationService service = service(model,
                new SequenceRandom(4, 4, 4, 50, 3, 6, 2, 4, 4, 5,
                        1, 1, 1, 1, 1, 1));
        var created = service.createAuto(new CharacterCardGenerationModels.CreateRequest(
                101L, 12L, "create-1"));
        CocCharacterCreationDraft persisted = capturedDraft();
        when(draftMapper.selectById(persisted.getId())).thenReturn(persisted);
        model.background = new CharacterCardGenerationModels.BackgroundPlan(
                "新形象", "新信念", "新人物", "新地点", "新物品", "新特质",
                "TRAITS", "新特质", null, List.of("新笔记本"));

        var rewritten = service.rewriteBackground(persisted.getId(),
                new CharacterCardGenerationModels.ActionRequest("rewrite-1", created.version()));

        assertThat(rewritten.state().preview().getCharacter().getIntValue())
                .isEqualTo(created.state().preview().getCharacter().getIntValue());
        assertThat(rewritten.state().preview().getSkills())
                .usingRecursiveComparison()
                .isEqualTo(created.state().preview().getSkills());
        assertThat(rewritten.state().preview().getProfile().getIdeology()).isEqualTo("新信念");
        assertThat(rewritten.state().preview().getWeapons()).isEmpty();
        assertThat(model.availableWeapons)
                .extracting(CharacterCardGenerationModels.AvailableWeapon::code)
                .contains("TASER");
        assertThat(rewritten.version()).isEqualTo(2);
    }

    @Test
    void repeatedBackgroundRewriteRequestReturnsTheSameDraftVersion() {
        MutableGenerationModel model = new MutableGenerationModel();
        CharacterCardCreationService service = service(model,
                new SequenceRandom(4, 4, 4, 50, 3, 6, 2, 4, 4, 5,
                        1, 1, 1, 1, 1, 1));
        var created = service.createAuto(new CharacterCardGenerationModels.CreateRequest(
                101L, 12L, "create-1"));
        CocCharacterCreationDraft persisted = capturedDraft();
        when(draftMapper.selectById(persisted.getId())).thenReturn(persisted);
        var request = new CharacterCardGenerationModels.ActionRequest(
                "rewrite-1", created.version());

        var first = service.rewriteBackground(persisted.getId(), request);
        var replay = service.rewriteBackground(persisted.getId(), request);

        assertThat(first.version()).isEqualTo(2);
        assertThat(replay.version()).isEqualTo(2);
    }

    @Test
    void findsTheActiveDraftByRunAndParticipantForDialogRestoration() {
        CharacterCardCreationService service = service(new MutableGenerationModel(),
                new SequenceRandom(4, 4, 4, 50, 3, 6, 2, 4, 4, 5));
        service.createAuto(new CharacterCardGenerationModels.CreateRequest(
                101L, 12L, "create-1"));
        CocCharacterCreationDraft persisted = capturedDraft();
        when(draftMapper.selectList(any())).thenReturn(List.of(persisted));

        var restored = service.getActive(101L, 12L);

        assertThat(restored).isNotNull();
        assertThat(restored.draftId()).isEqualTo(501L);
        assertThat(restored.state().preview().getCharacter().getName())
                .isEqualTo("埃莉诺·克劳福德");
    }

    @Test
    void restoringAnOlderDraftHidesRowsThatOnlyRepeatSkillDefaults() {
        CharacterCardCreationService service = service(new MutableGenerationModel(),
                new SequenceRandom(4, 4, 4, 50, 3, 6, 2, 4, 4, 5));
        service.createAuto(new CharacterCardGenerationModels.CreateRequest(
                101L, 12L, "create-1"));
        CocCharacterCreationDraft persisted = capturedDraft();
        var oldState = persisted.getState();
        List<com.me.galchat.domain.po.CocCharacterSkill> oldSkills =
                new ArrayList<>(oldState.preview().getSkills());
        oldSkills.add(new com.me.galchat.domain.po.CocCharacterSkill()
                .setDisplayName("聆听").setBaseValue(20).setValue(20));
        var oldPreview = new com.me.galchat.domain.vo.CharacterCardVO(
                oldState.preview().getCharacter(), oldSkills,
                oldState.preview().getWeapons(), oldState.preview().getProfile());
        persisted.setState(new CharacterCardGenerationModels.DraftState(
                oldState.formatVersion(), oldState.buildPlan(), oldState.buildRolls(),
                oldState.backgroundRolls(), oldState.backgroundPlan(), oldPreview));
        when(draftMapper.selectById(persisted.getId())).thenReturn(persisted);

        var restored = service.get(persisted.getId());

        assertThat(restored.state().preview().getSkills())
                .extracting(com.me.galchat.domain.po.CocCharacterSkill::getDisplayName)
                .doesNotContain("聆听");
    }

    @Test
    void completeCreatesFormalCardAndMarksDraftCompleted() {
        CharacterCardCreationService service = service(new MutableGenerationModel(),
                new SequenceRandom(4, 4, 4, 50, 3, 6, 2, 4, 4, 5));
        var created = service.createAuto(new CharacterCardGenerationModels.CreateRequest(
                101L, 12L, "create-1"));
        CocCharacterCreationDraft persisted = capturedDraft();
        when(draftMapper.selectById(persisted.getId())).thenReturn(persisted);
        doAnswer(invocation -> {
            invocation.<com.me.galchat.domain.po.CocCharacter>getArgument(0).setId(900L);
            return 1;
        }).when(characterMapper).insert(any(com.me.galchat.domain.po.CocCharacter.class));

        var card = service.complete(persisted.getId(),
                new CharacterCardGenerationModels.ActionRequest("complete-1", created.version()));

        assertThat(card.getCharacter().getId()).isEqualTo(900L);
        assertThat(card.getCharacter().getCreationMethod()).isEqualTo("AUTO_QUICK_START");
        ArgumentCaptor<com.me.galchat.domain.po.CocCharacterSkill> insertedSkills =
                ArgumentCaptor.forClass(com.me.galchat.domain.po.CocCharacterSkill.class);
        verify(skillMapper, org.mockito.Mockito.atLeastOnce())
                .insert(insertedSkills.capture());
        assertThat(insertedSkills.getAllValues())
                .allSatisfy(skill -> assertThat(skill.getValue())
                        .isNotEqualTo(skill.getBaseValue()))
                .extracting(com.me.galchat.domain.po.CocCharacterSkill::getDisplayName)
                .doesNotContain("克苏鲁神话", "聆听", "锁匠", "射击:手枪");
        assertThat(card.getSkills())
                .extracting(com.me.galchat.domain.po.CocCharacterSkill::getDisplayName)
                .containsExactlyInAnyOrderElementsOf(insertedSkills.getAllValues().stream()
                        .map(com.me.galchat.domain.po.CocCharacterSkill::getDisplayName)
                        .toList());
        verify(profileMapper).insert(any(com.me.galchat.domain.po.CocCharacterProfile.class));
        assertThat(persisted.getStatus()).isEqualTo("COMPLETED");
        assertThat(persisted.getResultCharacterId()).isEqualTo(900L);
        assertThat(persisted.getNextAction()).isNull();
        verify(draftMapper).update(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    private CocCharacterCreationDraft capturedDraft() {
        ArgumentCaptor<CocCharacterCreationDraft> captor =
                ArgumentCaptor.forClass(CocCharacterCreationDraft.class);
        verify(draftMapper).insert(captor.capture());
        return captor.getValue();
    }

    private CharacterCardCreationService service(
            MutableGenerationModel model,
            CharacterCardGenerationRandom random) {
        draftMapper = mock(CocCharacterCreationDraftMapper.class);
        characterMapper = mock(CocCharacterMapper.class);
        skillMapper = mock(CocCharacterSkillMapper.class);
        weaponMapper = mock(CocCharacterWeaponMapper.class);
        profileMapper = mock(CocCharacterProfileMapper.class);
        CharacterTemplateMapper templateMapper = mock(CharacterTemplateMapper.class);
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocSkillDefMapper skillDefMapper = mock(CocSkillDefMapper.class);
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        UserWorldPrefixMapper worldMapper = mock(UserWorldPrefixMapper.class);

        GroupConversation conversation = new GroupConversation()
                .setId(101L).setUserWorldId(10L).setWorldId(20L)
                .setModuleId(30L).setMode("trpg");
        CharacterTemplate template = new CharacterTemplate()
                .setId(12L).setWorldId(20L).setName("伊莎贝尔")
                .setBackground("地方报记者").setPersonality("冷静谨慎")
                .setCocPlayStyle("偏好调查");
        CocModule module = new CocModule().setId(30L).setEra("现代")
                .setName("测试模组");
        when(conversationMapper.selectById(101L)).thenReturn(conversation);
        when(worldMapper.selectById(10L)).thenReturn(new UserWorldPrefix()
                .setId(10L).setUserId(7L).setWorldId(20L));
        when(templateMapper.selectById(12L)).thenReturn(template);
        when(moduleMapper.selectById(30L)).thenReturn(module);
        when(skillDefMapper.selectList(null)).thenReturn(skillDefinitions());
        when(draftMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.<CocCharacterCreationDraft>getArgument(0).setId(501L);
            return 1;
        }).when(draftMapper).insert(any(CocCharacterCreationDraft.class));
        when(draftMapper.updateById(any(CocCharacterCreationDraft.class))).thenReturn(1);
        when(draftMapper.update(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(characterMapper.selectList(any())).thenReturn(List.of());

        return new CharacterCardCreationService(
                draftMapper, conversationMapper, worldMapper, templateMapper,
                moduleMapper, skillDefMapper, characterMapper, skillMapper,
                weaponMapper, profileMapper,
                new AutoCharacterCardAssembler(new CharacterSkillResolver()),
                new CharacterSkillResolver(),
                model, random);
    }

    private List<CocSkillDef> skillDefinitions() {
        LinkedHashMap<String, Integer> bases = new LinkedHashMap<>();
        bases.put("信用评级", 0);
        bases.put("克苏鲁神话", 0);
        bases.put("图书馆使用", 20);
        bases.put("侦查", 25);
        bases.put("心理学", 10);
        bases.put("母语", null);
        bases.put("历史", 5);
        bases.put("艺术和手艺:摄影", 5);
        bases.put("说服", 10);
        bases.put("神秘学", 5);
        bases.put("潜行", 20);
        bases.put("急救", 30);
        bases.put("汽车驾驶", 20);
        bases.put("斗殴", 25);
        bases.put("聆听", 20);
        bases.put("锁匠", 1);
        bases.put("射击:手枪", 20);
        List<CocSkillDef> result = new ArrayList<>();
        long id = 1;
        for (Map.Entry<String, Integer> entry : bases.entrySet()) {
            CocSkillDef definition = new CocSkillDef();
            definition.setId(id++);
            definition.setName(entry.getKey());
            definition.setCategory("测试");
            definition.setBaseValue(entry.getValue());
            definition.setBaseFormula("母语".equals(entry.getKey()) ? "EDU" : null);
            definition.setIsCore(true);
            definition.setAllowSpecialization(false);
            result.add(definition);
        }
        return result;
    }

    private static final class MutableGenerationModel
            implements CharacterCardGenerationModel {
        private CharacterCardGenerationModels.BackgroundPlan background =
                new CharacterCardGenerationModels.BackgroundPlan(
                        "冷静的记者", "科学终将解释一切", "导师安娜",
                        "旧档案室", "导师的信", "敢于冒险",
                        "SIGNIFICANT_PEOPLE", "导师安娜", "TASER",
                        List.of("笔记本", "钢笔"));
        private List<CharacterCardGenerationModels.AvailableWeapon> availableWeapons =
                List.of();

        @Override
        public CharacterCardGenerationModels.BuildPlan generateBuild(
                CharacterTemplate template, CocModule module,
                List<String> availableSkills) {
            return new CharacterCardGenerationModels.BuildPlan(
                    "埃莉诺·克劳福德", 30, "女", "波士顿", "阿卡姆", "记者",
                    List.of("INT", "EDU", "POW", "APP", "DEX", "CON", "SIZ", "STR"),
                    List.of("图书馆使用", "侦查", "心理学", "母语", "历史",
                            "艺术和手艺:摄影", "说服", "神秘学", "信用评级"),
                    List.of("潜行", "急救", "汽车驾驶", "斗殴", "聆听", "锁匠"),
                    List.of("符合角色性格"));
        }

        @Override
        public CharacterCardGenerationModels.BackgroundPlan generateBackground(
                CharacterTemplate template, CocModule module,
                com.me.galchat.domain.vo.CharacterCardVO card,
                CharacterCardGenerationModels.BackgroundRolls rolls,
                List<CharacterCardGenerationModels.AvailableWeapon> weapons) {
            availableWeapons = List.copyOf(weapons);
            return background;
        }
    }

    private static final class SequenceRandom implements CharacterCardGenerationRandom {
        private final Deque<Integer> values = new ArrayDeque<>();

        private SequenceRandom(int... values) {
            for (int value : values) {
                this.values.add(value);
            }
        }

        @Override
        public int roll(int sides) {
            int value = values.removeFirst();
            if (value < 1 || value > sides) {
                throw new AssertionError("骰点超出D" + sides + "范围：" + value);
            }
            return value;
        }
    }
}

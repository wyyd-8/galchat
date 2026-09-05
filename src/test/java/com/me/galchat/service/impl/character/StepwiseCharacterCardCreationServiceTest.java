package com.me.galchat.service.impl.character;

import com.me.galchat.domain.dto.CharacterCardGenerationModels;
import com.me.galchat.domain.dto.StepwiseCharacterCardModels;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.CocCharacterCreationDraft;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.CocSkillDef;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserInfo;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CharacterTemplateMapper;
import com.me.galchat.mapper.CocCharacterCreationDraftMapper;
import com.me.galchat.mapper.CocModuleMapper;
import com.me.galchat.mapper.CocSkillDefMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.UserInfoMapper;
import com.me.galchat.mapper.UserWorldPrefixMapper;
import com.me.galchat.utils.CurrentHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StepwiseCharacterCardCreationServiceTest {

    private CocCharacterCreationDraftMapper draftMapper;
    private StepwiseCharacterCardCreationService service;

    @org.junit.jupiter.api.BeforeAll
    static void initMybatisPlusTableInfo() {
        com.me.galchat.support.MybatisPlusTestSupport.initialize(
                CocCharacterCreationDraft.class);
    }

    @BeforeEach
    void setUp() {
        CurrentHolder.setCurrentId(7);
        draftMapper = mock(CocCharacterCreationDraftMapper.class);
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        UserWorldPrefixMapper worldMapper = mock(UserWorldPrefixMapper.class);
        UserInfoMapper userInfoMapper = mock(UserInfoMapper.class);
        CharacterTemplateMapper templateMapper = mock(CharacterTemplateMapper.class);
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocSkillDefMapper skillDefMapper = mock(CocSkillDefMapper.class);

        when(conversationMapper.selectById(101L)).thenReturn(new GroupConversation()
                .setId(101L).setUserWorldId(10L).setWorldId(20L)
                .setModuleId(30L).setMode("trpg"));
        when(worldMapper.selectById(10L)).thenReturn(new UserWorldPrefix()
                .setId(10L).setUserId(7L).setWorldId(20L));
        when(userInfoMapper.selectById(7L)).thenReturn(new UserInfo()
                .setId(7L).setUsername("玩家七"));
        when(templateMapper.selectById(12L)).thenReturn(new CharacterTemplate()
                .setId(12L).setWorldId(20L).setName("记者角色")
                .setImage("avatar.png").setPersonality("谨慎"));
        when(moduleMapper.selectById(30L)).thenReturn(new CocModule()
                .setId(30L).setEra("现代"));
        when(skillDefMapper.selectList(null)).thenReturn(skillDefinitions());
        when(draftMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.<CocCharacterCreationDraft>getArgument(0).setId(501L);
            return 1;
        }).when(draftMapper).insert(any(CocCharacterCreationDraft.class));
        when(draftMapper.updateById(any(CocCharacterCreationDraft.class))).thenReturn(1);
        when(draftMapper.updateWithExpectedVersion(any(), any())).thenReturn(1);

        service = new StepwiseCharacterCardCreationService(
                draftMapper, conversationMapper, worldMapper, userInfoMapper,
                templateMapper, moduleMapper, skillDefMapper,
                new CharacterSkillResolver(),
                new SequenceRandom(
                        3, 4, 5, 2, 3, 4, 4, 5, 6, 5, 4,
                        3, 3, 3, 2, 2, 4, 4, 4, 5, 5,
                        2, 3, 4, 70, 90, 7,
                        4, 5, 6, 7, 8, 9));
    }

    @AfterEach
    void tearDown() {
        CurrentHolder.remove();
    }

    @Test
    void createsStepDraftWithoutCreatingAFormalCard() {
        var result = service.create(new StepwiseCharacterCardModels.CreateRequest(
                101L, 12L, "哈维·沃尔特斯", "记者", 42,
                "男", "纽约", "波士顿"));

        assertThat(result.creationMode()).isEqualTo("STEP_STANDARD");
        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(result.currentStep()).isEqualTo("ATTRIBUTES");
        assertThat(result.nextAction()).isEqualTo("ROLL_ATTRIBUTES");
        assertThat(result.rulesVersion()).isEqualTo(1);
        assertThat(result.state().stepwise().identity().actorType()).isEqualTo("BOT");
        assertThat(result.state().stepwise().identity().playerName()).isEqualTo("记者角色");
        assertThat(result.state().preview()).isNull();
    }

    @Test
    void rollsAllAttributesLuckAndEducationGrowthAsOneIdempotentBundle() {
        var created = service.create(new StepwiseCharacterCardModels.CreateRequest(
                101L, 12L, "哈维·沃尔特斯", "记者", 42,
                "男", "纽约", "波士顿"));
        CocCharacterCreationDraft persisted = capturedDraft();
        when(draftMapper.selectById(501L)).thenReturn(persisted);
        var request = new CharacterCardGenerationModels.ActionRequest("roll-1", 1);

        var rolled = service.rollAttributes(501L, request);
        var replay = service.rollAttributes(501L, request);

        var attributes = rolled.state().stepwise().attributes();
        assertThat(attributes.raw()).containsEntry("STR", 60)
                .containsEntry("CON", 45).containsEntry("SIZ", 75)
                .containsEntry("DEX", 75).containsEntry("APP", 45)
                .containsEntry("INT", 50).containsEntry("POW", 60)
                .containsEntry("EDU", 80);
        assertThat(attributes.luck()).isEqualTo(45);
        assertThat(attributes.educationGrowths()).hasSize(2);
        assertThat(attributes.finalValues()).containsEntry("APP", 40)
                .containsEntry("EDU", 87);
        assertThat(rolled.nextAction()).isEqualTo("SUBMIT_AGE_ADJUSTMENT");
        assertThat(rolled.version()).isEqualTo(2);
        assertThat(replay.version()).isEqualTo(2);
    }

    @Test
    void requiresAgePhysicalPenaltyToBeAllocatedInMultiplesOfFive() {
        var created = service.create(new StepwiseCharacterCardModels.CreateRequest(
                101L, 12L, "哈维·沃尔特斯", "记者", 42,
                "男", "纽约", "波士顿"));
        CocCharacterCreationDraft persisted = capturedDraft();
        when(draftMapper.selectById(501L)).thenReturn(persisted);
        service.rollAttributes(501L,
                new CharacterCardGenerationModels.ActionRequest("roll-1", created.version()));

        assertThatThrownBy(() -> service.applyAgeAdjustment(501L,
                new StepwiseCharacterCardModels.AgeAdjustmentRequest(
                        1, 4, null, 0, 2)))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("5的倍数");

        var adjusted = service.applyAgeAdjustment(501L,
                new StepwiseCharacterCardModels.AgeAdjustmentRequest(
                        0, 5, null, 0, 2));

        assertThat(adjusted.currentStep()).isEqualTo("OCCUPATION");
        assertThat(adjusted.nextAction()).isEqualTo("CONFIRM_OCCUPATION");
        assertThat(adjusted.state().stepwise().attributes().finalValues())
                .containsEntry("CON", 40);
    }

    @Test
    void createsPlayerIdentityWhenParticipantIsMissing() {
        var result = service.create(new StepwiseCharacterCardModels.CreateRequest(
                101L, null, "阿尔伯特", "古董商", 30,
                "男", "伦敦", "约克"));

        assertThat(result.state().stepwise().identity().actorType()).isEqualTo("PLAYER");
        assertThat(result.state().stepwise().identity().playerName()).isEqualTo("玩家七");
        verify(draftMapper).insert(any(CocCharacterCreationDraft.class));
    }

    @Test
    void confirmsRemainingStepsAndBuildsTheFormalCardPreview() {
        var created = service.create(new StepwiseCharacterCardModels.CreateRequest(
                101L, 12L, "哈维·沃尔特斯", "记者", 42,
                "男", "纽约", "波士顿"));
        CocCharacterCreationDraft persisted = capturedDraft();
        when(draftMapper.selectById(501L)).thenReturn(persisted);
        service.rollAttributes(501L,
                new CharacterCardGenerationModels.ActionRequest("roll-1", 1));
        service.applyAgeAdjustment(501L,
                new StepwiseCharacterCardModels.AgeAdjustmentRequest(
                        0, 5, null, 0, 2));

        var occupation = service.saveOccupation(501L,
                new StepwiseCharacterCardModels.OccupationRequest(
                        "自由记者", true, 3));
        assertThat(occupation.currentStep()).isEqualTo("SKILLS");

        var skills = service.saveSkills(501L,
                new StepwiseCharacterCardModels.SkillsRequest(
                        List.of(new StepwiseCharacterCardModels.SkillAllocation(
                                3L, null, 30)), true, 4));
        assertThat(skills.state().stepwise().skills().spent()).isEqualTo(30);
        assertThat(skills.currentStep()).isEqualTo("BACKGROUND");

        var prompt = service.rollBackground(501L, "IDEOLOGY",
                new StepwiseCharacterCardModels.BackgroundRollRequest("bg-1", 5));
        assertThat(prompt.state().stepwise().background().prompts().get("IDEOLOGY")
                .prompts()).containsExactly("相信命运");

        var background = service.saveBackground(501L,
                new StepwiseCharacterCardModels.BackgroundRequest(
                        java.util.Map.of(
                                "APPEARANCE", "衣着整洁，神情专注",
                                "IDEOLOGY", "相信命运会通过偶然事件给出提示",
                                "TRAITS", "习惯先核对事实再行动"),
                        "IDEOLOGY", true, 6));
        assertThat(background.currentStep()).isEqualTo("EQUIPMENT");

        var equipment = service.saveEquipment(501L,
                new StepwiseCharacterCardModels.EquipmentRequest(
                        "现代", "笔记本\n钢笔", "一间租住公寓",
                        null, null, List.of(), true, 7));

        assertThat(equipment.status()).isEqualTo("PREVIEW_READY");
        assertThat(equipment.nextAction()).isEqualTo("COMPLETE");
        assertThat(equipment.state().preview().getCharacter().getCreationMethod())
                .isEqualTo("STEP");
        assertThat(equipment.state().preview().getCharacter().getOccupation())
                .isEqualTo("自由记者");
        assertThat(equipment.state().preview().getSkills())
                .singleElement()
                .satisfies(skill -> {
                    assertThat(skill.getDisplayName()).isEqualTo("侦查");
                    assertThat(skill.getBaseValue()).isEqualTo(25);
                    assertThat(skill.getValue()).isEqualTo(55);
                });
        assertThat(equipment.state().preview().getProfile().getKeyConnectionText())
                .isEqualTo("相信命运会通过偶然事件给出提示");

        var renamed = service.updateIdentity(501L,
                new StepwiseCharacterCardModels.IdentityUpdateRequest(
                        "哈维·沃尔特斯二世", null, null, null,
                        null, null, 8));
        assertThat(renamed.state().preview().getCharacter().getName())
                .isEqualTo("哈维·沃尔特斯二世");
    }

    @Test
    void exposesRulesAndAbandonsTheDraftWithoutDeletingIt() {
        var rules = service.getRules();
        assertThat(rules.attributes())
                .extracting(StepwiseCharacterCardModels.AttributeRule::code)
                .containsExactly("STR", "CON", "SIZ", "DEX", "APP", "INT", "POW", "EDU");
        assertThat(rules.skills())
                .extracting(StepwiseCharacterCardModels.SkillRule::name)
                .contains("侦查", "闪避", "母语");

        service.create(new StepwiseCharacterCardModels.CreateRequest(
                101L, 12L, "哈维·沃尔特斯", "记者", 42,
                "男", "纽约", "波士顿"));
        CocCharacterCreationDraft persisted = capturedDraft();
        when(draftMapper.selectById(501L)).thenReturn(persisted);

        var abandoned = service.abandon(501L, 1);

        assertThat(abandoned.status()).isEqualTo("ABANDONED");
        assertThat(abandoned.nextAction()).isNull();
        verify(draftMapper, never()).deleteById(501L);
    }

    @Test
    void exposesCanonicalAutomaticWeaponChoicesToStepwiseClients() {
        JsonNode payload = new ObjectMapper().valueToTree(service.getRules());

        assertThat(payload.path("weapons").isArray()).isTrue();
        assertThat(payload.path("weapons")).anySatisfy(weapon -> {
            assertThat(weapon.path("code").asText()).isEqualTo("GLOCK_17");
            assertThat(weapon.path("name").asText()).isEqualTo("9mm 格洛克17");
            assertThat(weapon.path("skillName").asText()).isEqualTo("射击:手枪");
            assertThat(weapon.path("damage").asText()).isEqualTo("1D10");
            assertThat(weapon.path("ammoCapacity").asInt()).isEqualTo(17);
        });
        assertThat(payload.path("weapons")).noneSatisfy(weapon ->
                assertThat(weapon.path("code").asText()).isEqualTo("AK_47"));
    }

    @Test
    void rejectsAnEquipmentWeaponOutsideTheAutomaticStartingCatalog() {
        advanceDraftToEquipment();

        assertThatThrownBy(() -> service.saveEquipment(501L,
                new StepwiseCharacterCardModels.EquipmentRequest(
                        "现代", null, null, null, null,
                        List.of(new StepwiseCharacterCardModels.WeaponInput("AK_47")),
                        true, 6)))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("可选武器");
    }

    @Test
    void buildsWeaponDetailsFromTheCanonicalCatalogCode() {
        advanceDraftToEquipment();

        var saved = service.saveEquipment(501L,
                new StepwiseCharacterCardModels.EquipmentRequest(
                        "现代", null, null, null, null,
                        List.of(new StepwiseCharacterCardModels.WeaponInput("GLOCK_17")),
                        true, 6));

        assertThat(saved.state().stepwise().equipment().weapons())
                .singleElement()
                .satisfies(weapon -> {
                    assertThat(weapon.getName()).isEqualTo("9mm 格洛克17");
                    assertThat(weapon.getSkillName()).isEqualTo("射击:手枪");
                    assertThat(weapon.getDamage()).isEqualTo("1D10");
                    assertThat(weapon.getRange()).isEqualTo("15m");
                    assertThat(weapon.getAmmoCapacity()).isEqualTo(17);
                    assertThat(weapon.getRemainingAmmo()).isEqualTo(17);
                    assertThat(weapon.getMalfunction()).isEqualTo("98");
                    assertThat(weapon.getRiskTags()).containsExactly("高噪声");
                    assertThat(weapon.getNotes()).isNull();
                });
    }

    @Test
    void rejectsMoreThanThreeStepwiseStartingWeapons() {
        advanceDraftToEquipment();

        assertThatThrownBy(() -> service.saveEquipment(501L,
                new StepwiseCharacterCardModels.EquipmentRequest(
                        "现代", null, null, null, null,
                        List.of(
                                new StepwiseCharacterCardModels.WeaponInput("GLOCK_17"),
                                new StepwiseCharacterCardModels.WeaponInput("SMALL_KNIFE"),
                                new StepwiseCharacterCardModels.WeaponInput("STUN_GUN"),
                                new StepwiseCharacterCardModels.WeaponInput("CHAINSAW")),
                        true, 6)))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("至多选择3件武器");
    }

    @Test
    void rejectsAnUpdateThatLostTheOptimisticVersionRace() {
        service.create(new StepwiseCharacterCardModels.CreateRequest(
                101L, 12L, "哈维·沃尔特斯", "记者", 42,
                "男", "纽约", "波士顿"));
        CocCharacterCreationDraft persisted = capturedDraft();
        when(draftMapper.selectById(501L)).thenReturn(persisted);
        when(draftMapper.updateById(any(CocCharacterCreationDraft.class))).thenReturn(1);
        when(draftMapper.updateWithExpectedVersion(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.rollAttributes(501L,
                new CharacterCardGenerationModels.ActionRequest("roll-race", 1)))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("版本已变化");
    }

    private CocCharacterCreationDraft capturedDraft() {
        ArgumentCaptor<CocCharacterCreationDraft> captor =
                ArgumentCaptor.forClass(CocCharacterCreationDraft.class);
        verify(draftMapper).insert(captor.capture());
        return captor.getValue();
    }

    private void advanceDraftToEquipment() {
        service.create(new StepwiseCharacterCardModels.CreateRequest(
                101L, 12L, "哈维·沃尔特斯", "记者", 42,
                "男", "纽约", "波士顿"));
        CocCharacterCreationDraft persisted = capturedDraft();
        when(draftMapper.selectById(501L)).thenReturn(persisted);
        service.rollAttributes(501L,
                new CharacterCardGenerationModels.ActionRequest("roll-1", 1));
        service.applyAgeAdjustment(501L,
                new StepwiseCharacterCardModels.AgeAdjustmentRequest(
                        0, 5, null, 0, 2));
        service.saveOccupation(501L,
                new StepwiseCharacterCardModels.OccupationRequest(
                        "自由记者", true, 3));
        service.saveSkills(501L,
                new StepwiseCharacterCardModels.SkillsRequest(List.of(), true, 4));
        service.saveBackground(501L,
                new StepwiseCharacterCardModels.BackgroundRequest(
                        java.util.Map.of(
                                "APPEARANCE", "衣着整洁",
                                "IDEOLOGY", "相信命运",
                                "TRAITS", "谨慎"),
                        "IDEOLOGY", true, 5));
    }

    private List<CocSkillDef> skillDefinitions() {
        List<CocSkillDef> definitions = new ArrayList<>();
        definitions.add(skill(1L, "信用评级", 0, null, false));
        definitions.add(skill(2L, "克苏鲁神话", 0, null, false));
        definitions.add(skill(3L, "侦查", 25, null, false));
        definitions.add(skill(4L, "闪避", null, "DEX/2", false));
        definitions.add(skill(5L, "母语", null, "EDU", false));
        definitions.add(skill(6L, "艺术和手艺", 5, null, true));
        definitions.add(skill(7L, "射击:手枪", 20, null, false));
        return definitions;
    }

    private CocSkillDef skill(
            Long id, String name, Integer base, String formula,
            boolean specialization) {
        CocSkillDef definition = new CocSkillDef();
        definition.setId(id);
        definition.setName(name);
        definition.setCategory("测试");
        definition.setBaseValue(base);
        definition.setBaseFormula(formula);
        definition.setAllowSpecialization(specialization);
        definition.setIsCore(true);
        return definition;
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

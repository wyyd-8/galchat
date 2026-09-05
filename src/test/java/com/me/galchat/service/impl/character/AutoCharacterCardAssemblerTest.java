package com.me.galchat.service.impl.character;

import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.CocSkillDef;
import com.me.galchat.domain.dto.CharacterCardGenerationModels;
import com.me.galchat.domain.vo.CharacterCardVO;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutoCharacterCardAssemblerTest {

    @Test
    void quickStartRanksAttributesAndSkillsUsingLiteralRuleValues() {
        AutoCharacterCardAssembler assembler = assembler();
        var plan = new CharacterCardGenerationModels.BuildPlan(
                "埃莉诺·克劳福德", 30, "女", "波士顿", "阿卡姆", "记者",
                List.of("INT", "EDU", "POW", "APP", "DEX", "CON", "SIZ", "STR"),
                List.of("图书馆使用", "侦查", "心理学", "母语", "历史",
                        "艺术和手艺:摄影", "说服", "神秘学", "信用评级"),
                List.of("潜行", "急救", "汽车驾驶", "斗殴", "聆听", "锁匠"),
                List.of("记者职业符合追查真相的性格"));

        CharacterCardGenerationModels.DraftState state = assembler.build(
                template(), module("1920s"), plan, skillDefinitions(),
                new CharacterCardGenerationModels.BuildRolls(55, List.of(50), List.of()));

        CharacterCardVO card = state.preview();
        assertThat(card.getCharacter().getName()).isEqualTo("埃莉诺·克劳福德");
        assertThat(card.getCharacter().getPlayerName()).isEqualTo("伊莎贝尔");
        assertThat(card.getCharacter().getIntValue()).isEqualTo(80);
        assertThat(card.getCharacter().getEdu()).isEqualTo(70);
        assertThat(card.getCharacter().getStr()).isEqualTo(40);
        assertThat(skillValue(card, "图书馆使用")).isEqualTo(70);
        assertThat(skillValue(card, "侦查")).isEqualTo(60);
        assertThat(skillValue(card, "潜行")).isEqualTo(40);
        assertThat(card.getSkills())
                .extracting(com.me.galchat.domain.po.CocCharacterSkill::getDisplayName)
                .doesNotContain("锁匠");
        assertThat(skillValue(card, "信用评级")).isEqualTo(40);
        assertThat(card.getCharacter().getLuckCurrent()).isEqualTo(55);
    }

    @Test
    void quickStartGivesCreditRatingTenWhenItIsMissingFromTheRanking() {
        AutoCharacterCardAssembler assembler = assembler();
        CharacterCardGenerationModels.BuildPlan plan = new CharacterCardGenerationModels.BuildPlan(
                "埃莉诺·克劳福德", 30, "女", "波士顿", "阿卡姆", "记者",
                basePlan().attributeOrder(),
                List.of("图书馆使用", "侦查", "心理学", "母语", "历史",
                        "艺术和手艺:摄影", "说服", "神秘学", "聆听"),
                List.of("信用评级", "潜行", "急救", "汽车驾驶", "斗殴", "锁匠", "射击:手枪"),
                List.of());

        CharacterCardGenerationModels.DraftState state = assembler.build(
                template(), module("1920s"), plan, skillDefinitions(),
                new CharacterCardGenerationModels.BuildRolls(55, List.of(50), List.of()));

        assertThat(skillValue(state.preview(), "信用评级")).isEqualTo(10);
    }

    @Test
    void quickStartAcceptsAFullWidthSpecializationSeparatorFromTheModel() {
        AutoCharacterCardAssembler assembler = assembler();
        CharacterCardGenerationModels.BuildPlan plan = new CharacterCardGenerationModels.BuildPlan(
                "埃莉诺·克劳福德", 30, "女", "波士顿", "阿卡姆", "猎人",
                basePlan().attributeOrder(),
                List.of("射击：步枪/霰弹枪", "侦查", "心理学", "母语", "历史",
                        "艺术和手艺:摄影", "说服", "神秘学", "信用评级"),
                basePlan().interestSkillOrder(), List.of());

        CharacterCardGenerationModels.DraftState state = assembler.build(
                template(), module("1920s"), plan, skillDefinitions(),
                new CharacterCardGenerationModels.BuildRolls(55, List.of(50), List.of()));

        assertThat(skillValue(state.preview(), "射击:步枪/霰弹枪")).isEqualTo(70);
        assertThat(state.preview().getSkills())
                .extracting(com.me.galchat.domain.po.CocCharacterSkill::getDisplayName)
                .doesNotContain("射击：步枪/霰弹枪");
    }

    @Test
    void quickStartCompletesAUniqueSpecializationNameFromTheModel() {
        AutoCharacterCardAssembler assembler = assembler();
        CharacterCardGenerationModels.BuildPlan plan = new CharacterCardGenerationModels.BuildPlan(
                "埃莉诺·克劳福德", 30, "女", "波士顿", "阿卡姆", "摄影记者",
                basePlan().attributeOrder(),
                List.of("图书馆使用", "侦查", "心理学", "母语", "历史",
                        "摄影", "说服", "神秘学", "信用评级"),
                basePlan().interestSkillOrder(), List.of());

        CharacterCardGenerationModels.DraftState state = assembler.build(
                template(), module("1920s"), plan, skillDefinitions(),
                new CharacterCardGenerationModels.BuildRolls(55, List.of(50), List.of()));

        assertThat(skillValue(state.preview(), "艺术和手艺:摄影")).isEqualTo(50);
        assertThat(state.preview().getSkills())
                .extracting(com.me.galchat.domain.po.CocCharacterSkill::getDisplayName)
                .doesNotContain("摄影");
    }

    @Test
    void quickStartRejectsAnAmbiguousSpecializationShortName() {
        AutoCharacterCardAssembler assembler = assembler();
        CharacterCardGenerationModels.BuildPlan plan = new CharacterCardGenerationModels.BuildPlan(
                "埃莉诺·克劳福德", 30, "女", "波士顿", "阿卡姆", "摄影记者",
                basePlan().attributeOrder(),
                List.of("图书馆使用", "侦查", "心理学", "母语", "历史",
                        "摄影", "说服", "神秘学", "信用评级"),
                basePlan().interestSkillOrder(), List.of());
        List<CocSkillDef> definitions = new ArrayList<>(skillDefinitions());
        CocSkillDef ambiguousDefinition = new CocSkillDef();
        ambiguousDefinition.setId(99L);
        ambiguousDefinition.setName("科学:摄影");
        ambiguousDefinition.setCategory("科学");
        ambiguousDefinition.setBaseValue(1);
        ambiguousDefinition.setIsCore(true);
        definitions.add(ambiguousDefinition);

        assertThatThrownBy(() -> assembler.build(
                template(), module("1920s"), plan, definitions,
                new CharacterCardGenerationModels.BuildRolls(55, List.of(50), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("未知或不可分配技能：摄影");
    }

    @Test
    void backgroundMapsWhitelistedWeaponAndCapsEquipmentAtFive() {
        AutoCharacterCardAssembler assembler = assembler();
        CharacterCardGenerationModels.DraftState built = assembler.build(
                template(), module("现代"), basePlan(), skillDefinitions(),
                new CharacterCardGenerationModels.BuildRolls(60, List.of(40), List.of()));
        var background = new CharacterCardGenerationModels.BackgroundPlan(
                "冷静的记者", "科学终将解释一切", "导师安娜",
                "旧档案室", "导师的信", "敢于冒险",
                "SIGNIFICANT_PEOPLE", "导师安娜", "TASER",
                List.of("笔记本", "钢笔", "相机", "手电筒", "火柴"));

        CharacterCardGenerationModels.DraftState completed = assembler.applyBackground(
                built, background, backgroundRolls(), effectiveSkills(built));

        assertThat(completed.preview().getWeapons()).singleElement().satisfies(weapon -> {
            assertThat(weapon.getName()).isEqualTo("泰瑟枪");
            assertThat(weapon.getSkillName()).isEqualTo("射击:手枪");
            assertThat(weapon.getDamage()).isEqualTo("1D3+眩晕");
            assertThat(weapon.getRemainingAmmo()).isEqualTo(3);
            assertThat(weapon.getCanImpale()).isFalse();
        });
        assertThat(completed.preview().getProfile().getEquipmentText())
                .isEqualTo("笔记本\n钢笔\n相机\n手电筒\n火柴");
        assertThat(completed.preview().getProfile().getKeyConnectionText())
                .isEqualTo("导师安娜");
    }

    @Test
    void backgroundRejectsControlledWeaponCodeOutsideAutomaticCandidates() {
        AutoCharacterCardAssembler assembler = assembler();
        CharacterCardGenerationModels.DraftState built = assembler.build(
                template(), module("现代"), basePlan(), skillDefinitions(),
                new CharacterCardGenerationModels.BuildRolls(60, List.of(40), List.of()));
        var background = new CharacterCardGenerationModels.BackgroundPlan(
                "形象", "信念", "重要之人", "地点", "物品", "特质",
                "TRAITS", "特质", "AK_47", List.of());

        assertThatThrownBy(() -> assembler.applyBackground(
                built, background, backgroundRolls(), effectiveSkills(built)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("受管制武器不能自动成为初始武器");
    }

    @Test
    void backgroundMarksCataloguedAbnormalWeaponForLaterKpRules() {
        AutoCharacterCardAssembler assembler = assembler();
        CharacterCardGenerationModels.DraftState built = assembler.build(
                template(), module("现代"), basePlan(), skillDefinitions(),
                new CharacterCardGenerationModels.BuildRolls(60, List.of(40), List.of()));
        var background = new CharacterCardGenerationModels.BackgroundPlan(
                "形象", "信念", "重要之人", "地点", "物品", "特质",
                "TRAITS", "特质", "CHAINSAW", List.of());

        CharacterCardGenerationModels.DraftState completed = assembler.applyBackground(
                built, background, backgroundRolls(), effectiveSkills(built));

        assertThat(completed.preview().getWeapons()).singleElement().satisfies(weapon -> {
            assertThat(weapon.getName()).isEqualTo("链锯");
            assertThat(weapon.getAbnormal()).isTrue();
            assertThat(weapon.getRiskTags())
                    .containsExactly("显眼", "高噪声", "笨重", "破坏现场");
        });
    }

    @Test
    void backgroundRejectsMoreThanFiveEquipmentItems() {
        AutoCharacterCardAssembler assembler = assembler();
        CharacterCardGenerationModels.DraftState built = assembler.build(
                template(), module(null), basePlan(), skillDefinitions(),
                new CharacterCardGenerationModels.BuildRolls(60, List.of(40), List.of()));
        var background = new CharacterCardGenerationModels.BackgroundPlan(
                "形象", "信念", "重要之人", "地点", "物品", "特质",
                "TRAITS", "特质", null,
                List.of("1", "2", "3", "4", "5", "6"));

        assertThatThrownBy(() -> assembler.applyBackground(
                built, background, backgroundRolls(), effectiveSkills(built)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("装备不能超过5件");
    }

    @Test
    void quickStartNeverAllowsPointsInCthulhuMythos() {
        AutoCharacterCardAssembler assembler = assembler();
        CharacterCardGenerationModels.BuildPlan plan = new CharacterCardGenerationModels.BuildPlan(
                "埃莉诺·克劳福德", 30, "女", "波士顿", "阿卡姆", "记者",
                basePlan().attributeOrder(), basePlan().occupationSkillOrder(),
                List.of("克苏鲁神话", "潜行", "急救", "汽车驾驶", "斗殴"), List.of());

        assertThatThrownBy(() -> assembler.build(
                template(), module("现代"), plan, skillDefinitions(),
                new CharacterCardGenerationModels.BuildRolls(60, List.of(40), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("克苏鲁神话");
    }

    @Test
    void quickStartRequiresANewInvestigatorName() {
        AutoCharacterCardAssembler assembler = assembler();
        CharacterCardGenerationModels.BuildPlan plan = new CharacterCardGenerationModels.BuildPlan(
                " ", 30, "女", "波士顿", "阿卡姆", "记者",
                basePlan().attributeOrder(), basePlan().occupationSkillOrder(),
                basePlan().interestSkillOrder(), List.of());

        assertThatThrownBy(() -> assembler.build(
                template(), module("现代"), plan, skillDefinitions(),
                new CharacterCardGenerationModels.BuildRolls(60, List.of(40), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("姓名");
    }

    @Test
    void backgroundRequiresAllSixEntriesAndAMatchingKeyConnection() {
        AutoCharacterCardAssembler assembler = assembler();
        CharacterCardGenerationModels.DraftState built = assembler.build(
                template(), module("现代"), basePlan(), skillDefinitions(),
                new CharacterCardGenerationModels.BuildRolls(60, List.of(40), List.of()));
        var background = new CharacterCardGenerationModels.BackgroundPlan(
                "形象", "信念", "重要之人", "地点", null, "特质",
                "SIGNIFICANT_PEOPLE", "并非上述重要之人", null, List.of());

        assertThatThrownBy(() -> assembler.applyBackground(
                built, background, backgroundRolls(), effectiveSkills(built)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("背景");
    }

    private int skillValue(CharacterCardVO card, String name) {
        return card.getSkills().stream()
                .filter(skill -> name.equals(skill.getDisplayName()))
                .findFirst().orElseThrow().getValue();
    }

    private AutoCharacterCardAssembler assembler() {
        return new AutoCharacterCardAssembler(new CharacterSkillResolver());
    }

    private List<com.me.galchat.domain.po.CocCharacterSkill> effectiveSkills(
            CharacterCardGenerationModels.DraftState state) {
        return new CharacterSkillResolver().resolveEffectiveSkills(
                state.preview().getCharacter(), state.preview().getSkills(),
                skillDefinitions());
    }

    private CharacterCardGenerationModels.BuildPlan basePlan() {
        return new CharacterCardGenerationModels.BuildPlan(
                "埃莉诺·克劳福德", 30, "女", "波士顿", "阿卡姆", "记者",
                List.of("INT", "EDU", "POW", "APP", "DEX", "CON", "SIZ", "STR"),
                List.of("图书馆使用", "侦查", "心理学", "母语", "历史",
                        "艺术和手艺:摄影", "说服", "神秘学", "信用评级"),
                List.of("潜行", "急救", "汽车驾驶", "斗殴", "聆听", "锁匠"),
                List.of());
    }

    private CharacterCardGenerationModels.BackgroundRolls backgroundRolls() {
        return new CharacterCardGenerationModels.BackgroundRolls(
                3, 6, 2, 4, 4, 5,
                Map.of("ideology", "科学终将解释一切"));
    }

    private CharacterTemplate template() {
        return new CharacterTemplate().setId(12L).setName("伊莎贝尔")
                .setImage("https://example.com/isabel.png")
                .setBackground("地方报记者")
                .setPersonality("冷静、谨慎、追求真相")
                .setCocPlayStyle("偏好调查与交涉");
    }

    private CocModule module(String era) {
        return new CocModule().setId(4L).setName("测试模组").setEra(era)
                .setIntroduction("调查失踪案");
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
        bases.put("射击:步枪/霰弹枪", 25);
        bases.put("格斗:链锯", 10);
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
}

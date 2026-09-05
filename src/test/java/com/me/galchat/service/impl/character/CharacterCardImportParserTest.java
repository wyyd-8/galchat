package com.me.galchat.service.impl.character;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CharacterCardImportParserTest {

    private static final String CARD = """
            奥德赛，篮球运动员，男，24岁
            出身运动员，现居洛杉矶
            时代: 现代   玩家: player
            STR 70  CON 60  SIZ 70  DEX 40
            APP 50  INT 60  POW 60  EDU 50
            DB:+9D6  Build:99  MOV:99  Luck:65
            HP:99/99   San:99/99   MP:99/99
            —————————战斗—————————
            斗殴 80% (40/16), 伤害1D3+DB
            弓箭 80% (40/16), 伤害1D6+0.5DB
            闪避 20% (10/4)
            —————————技能—————————
            信用评级 40% (20/8)
            克苏鲁神话 0% (0/0)
            格斗:斗殴 80% (40/16)
            射击:弓 80% (40/16)
            急救 50% (25/10)
            ————————背景故事————————
            形象描述：形象
            思想与信念：信念
            重要之人：人
            意义非凡之地：地
            宝贵之物：物
            特质：（空）
            伤口和疤痕：伤痕
            恐惧症和狂躁症：无
            （没有填写更多背景）
            ————————装备和道具———————
            弓
            急救包
            水
            —————————资产—————————
            消费水平：小康
            现金：1000$
            有车
            ————————————————————
            """;

    @Test
    void parsesCompoundCardAndRecalculatesUntrustedValues() {
        CharacterCardImportParser.ParsedCharacterCard parsed = CharacterCardImportParser.parse(CARD);

        assertThat(parsed.character().getName()).isEqualTo("奥德赛");
        assertThat(parsed.character().getEra()).isEqualTo("现代");
        assertThat(parsed.character().getDamageBonus()).isEqualTo("+1D4");
        assertThat(parsed.character().getBuild()).isEqualTo(1);
        assertThat(parsed.character().getMov()).isEqualTo(8);
        assertThat(parsed.character().getHpMax()).isEqualTo(13);
        assertThat(parsed.character().getSanCurrent()).isEqualTo(60);
        assertThat(parsed.character().getSanMax()).isEqualTo(99);
        assertThat(parsed.character().getMpMax()).isEqualTo(12);
        assertThat(parsed.character().getLuckCurrent()).isNull();
        assertThat(parsed.skills()).extracting("displayName")
                .contains("闪避", "格斗:斗殴", "射击:弓", "急救")
                .doesNotContain("斗殴", "弓箭");
        assertThat(parsed.weapons()).hasSize(2);
        assertThat(parsed.weapons())
                .allSatisfy(weapon -> assertThat(weapon.getSkillName()).isNull());
        assertThat(parsed.weapons().getFirst().getDamage()).isEqualTo("1D3+DB");
        assertThat(parsed.weapons().getFirst().getCanImpale()).isFalse();
        assertThat(parsed.weapons().get(1).getCanImpale()).isTrue();
        assertThat(parsed.profile().getAppearance()).isEqualTo("形象");
        assertThat(parsed.profile().getEquipmentText()).isEqualTo("弓\n急救包\n水");
        assertThat(parsed.profile().getSpendingLevel()).isEqualTo("小康");
        assertThat(parsed.profile().getCash()).isEqualTo("1000$");
        assertThat(parsed.profile().getAssetsText()).isEqualTo("有车");
    }

    @Test
    void appliesAgePenaltyToMovement() {
        CharacterCardRules.DerivedValues values = CharacterCardRules.derive(20, 70, 80, 55, 45, 42);

        assertThat(values.mov()).isEqualTo(6);
        assertThat(values.damageBonus()).isEqualTo("0");
        assertThat(values.hp()).isEqualTo(15);
        assertThat(values.san()).isEqualTo(45);
        assertThat(values.mp()).isEqualTo(9);
    }

    @Test
    void extendsDamageBonusTableBeyondHumanRange() {
        assertThat(CharacterCardRules.derive(445, 50, 80, 50, 50, 20).damageBonus()).isEqualTo("+6D6");
        assertThat(CharacterCardRules.derive(445, 50, 80, 50, 50, 20).build()).isEqualTo(7);
    }

    @Test
    void rejectsAttributeTotalOver460() {
        String invalid = CARD.replace("APP 50", "APP 60");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CharacterCardImportParser.parse(invalid))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("属性总和不能超过460")
                .hasMessageContaining("470");
    }

    @Test
    void acceptsCreationAttributeBoundsTenAndNinety() {
        CharacterCardImportParser.ParsedCharacterCard parsed =
                CharacterCardImportParser.parse(cardWithAttributes(
                        "STR 10 CON 90 SIZ 50 DEX 50",
                        "APP 50 INT 50 POW 50 EDU 50"));

        assertThat(parsed.character().getStr()).isEqualTo(10);
        assertThat(parsed.character().getCon()).isEqualTo(90);
    }

    @Test
    void rejectsCreationAttributesOutsideTenAndNinety() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> CharacterCardImportParser.parse(
                                cardWithAttributes(
                                        "STR 9 CON 50 SIZ 50 DEX 50",
                                        "APP 50 INT 50 POW 50 EDU 50")))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessage("STR属性值必须在10到90之间");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> CharacterCardImportParser.parse(
                                cardWithAttributes(
                                        "STR 50 CON 50 SIZ 50 DEX 50",
                                        "APP 50 INT 50 POW 50 EDU 91")))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessage("EDU属性值必须在10到90之间");
    }

    private String cardWithAttributes(String firstLine, String secondLine) {
        return """
                测试角色，调查员，女，30岁
                出身上海，现居上海
                时代: 现代
                %s
                %s
                """.formatted(firstLine, secondLine);
    }
}

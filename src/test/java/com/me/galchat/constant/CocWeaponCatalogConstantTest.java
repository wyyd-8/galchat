package com.me.galchat.constant;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CocWeaponCatalogConstantTest {

    @Test
    void unknownEraOffersOnlyWeaponsSharedByBothSupportedEras() {
        Set<String> codes = CocWeaponCatalogConstant.availableForEra("维多利亚时代")
                .stream()
                .map(CocWeaponCatalogConstant.WeaponDefinition::code)
                .collect(Collectors.toSet());

        assertThat(codes)
                .hasSize(27)
                .contains("BOW", "SMALL_KNIFE", "WOOD_AXE")
                .doesNotContain("WHIP", "CHAINSAW", "TEAR_GAS_SPRAY");
    }

    @Test
    void catalogExcludesTorchWireAndEveryThrowingWeapon() {
        assertThat(CocWeaponCatalogConstant.weapons().values())
                .hasSize(41)
                .noneMatch(weapon -> "投掷".equals(weapon.requiredSkillName()))
                .noneMatch(weapon -> Set.of("燃烧的火把", "220V通电导线")
                        .contains(weapon.name()));
    }

    @Test
    void modernEraIncludesModernAndSharedWeaponsButNotWhip() {
        Set<String> codes = CocWeaponCatalogConstant.availableForEra("现代")
                .stream()
                .map(CocWeaponCatalogConstant.WeaponDefinition::code)
                .collect(Collectors.toSet());

        assertThat(codes)
                .hasSize(37)
                .contains("CHAINSAW", "STUN_GUN", "TASER", "SMALL_KNIFE")
                .doesNotContain("WHIP");
    }

    @Test
    void ambiguousEraDoesNotEnableEraSpecificWeapons() {
        Set<String> codes = CocWeaponCatalogConstant.availableForEra("1920年代或现代")
                .stream()
                .map(CocWeaponCatalogConstant.WeaponDefinition::code)
                .collect(Collectors.toSet());

        assertThat(codes).doesNotContain("WHIP", "CHAINSAW", "TASER");
    }

    @Test
    void catalogSeparatesQueryableFirearmsFromAutomaticStartingWeapons() {
        Set<String> queryable = CocWeaponCatalogConstant.availableForEra("现代")
                .stream()
                .map(CocWeaponCatalogConstant.WeaponDefinition::code)
                .collect(Collectors.toSet());
        Set<String> automatic = CocWeaponCatalogConstant
                .autoSelectableForEra("现代").stream()
                .map(CocWeaponCatalogConstant.WeaponDefinition::code)
                .collect(Collectors.toSet());

        assertThat(queryable)
                .contains("GLOCK_17", "SHOTGUN_12_PUMP", "AK_47", "M4", "MP5");
        assertThat(automatic)
                .contains("GLOCK_17", "SHOTGUN_12_PUMP")
                .doesNotContain("AK_47", "M4", "MP5");
        assertThat(CocWeaponCatalogConstant.require("SHOTGUN_12_DOUBLE"))
                .satisfies(weapon -> {
                    assertThat(weapon.kind()).isEqualTo(
                            CocWeaponCatalogConstant.WeaponKind.FIREARM);
                    assertThat(weapon.damage())
                            .isEqualTo("近4D6；中2D6；远1D6");
                    assertThat(weapon.range())
                            .isEqualTo("近≤10m；中≤20m；远≤50m");
                    assertThat(weapon.acquisitionLevel()).isEqualTo(
                            CocWeaponCatalogConstant.AcquisitionLevel.COMMON);
                    assertThat(weapon.canImpale()).isTrue();
                });
        assertThat(CocWeaponCatalogConstant.require("TASER").canImpale())
                .isFalse();
    }

    @Test
    void edgedAndPointedMeleeWeaponsCarryAuthoritativeImpaleFlag() {
        assertThat(CocWeaponCatalogConstant.require("SMALL_KNIFE").canImpale())
                .isTrue();
        assertThat(CocWeaponCatalogConstant.require("LARGE_SWORD").canImpale())
                .isTrue();
        assertThat(CocWeaponCatalogConstant.require("WOOD_AXE").canImpale())
                .isTrue();
        assertThat(CocWeaponCatalogConstant.require("LANCE").canImpale())
                .isTrue();
        assertThat(CocWeaponCatalogConstant.require("LARGE_CLUB").canImpale())
                .isFalse();
        assertThat(CocWeaponCatalogConstant.require("WHIP").canImpale())
                .isFalse();
    }

    @Test
    void broadWeaponTypesHaveStableCommonDefaults() {
        assertThat(CocWeaponCatalogConstant.genericTypeDefaults("现代"))
                .containsEntry("手枪", "PISTOL_38_9MM")
                .containsEntry("左轮手枪", "REVOLVER_38_9MM")
                .containsEntry("步枪", "RIFLE_22_BOLT")
                .containsEntry("霰弹枪", "SHOTGUN_12_DOUBLE")
                .containsEntry("冲锋枪", "MP5")
                .containsEntry("突击步枪", "AK_47")
                .containsEntry("弓", "BOW")
                .containsEntry("弩", "CROSSBOW")
                .containsEntry("刀", "MEDIUM_KNIFE")
                .containsEntry("棍棒", "LARGE_CLUB")
                .containsEntry("斧", "HAND_AXE")
                .containsEntry("剑", "MEDIUM_SWORD");
        assertThat(CocWeaponCatalogConstant.genericTypeDefaults("1920s"))
                .containsEntry("冲锋枪", "THOMPSON_SMG");
    }

    @Test
    void contextRelevantWeaponRisksAreClassifiedByTheirActualImpact() {
        assertThat(CocWeaponCatalogConstant.require("PISTOL_22_AUTO"))
                .satisfies(weapon -> {
                    assertThat(weapon.abnormal()).isFalse();
                    assertThat(weapon.riskTags()).containsExactly("高噪声");
                });
        assertThat(CocWeaponCatalogConstant.require("RIFLE_22_BOLT"))
                .satisfies(weapon -> {
                    assertThat(weapon.abnormal()).isTrue();
                    assertThat(weapon.riskTags())
                            .containsExactly("显眼", "高噪声", "笨重");
                });
        assertThat(CocWeaponCatalogConstant.require("AK_47"))
                .satisfies(weapon -> {
                    assertThat(weapon.abnormal()).isTrue();
                    assertThat(weapon.riskTags()).containsExactly(
                            "显眼", "高噪声", "笨重", "严格管制");
                });
        assertThat(CocWeaponCatalogConstant.require("SHOTGUN_12_SAWED_OFF"))
                .satisfies(weapon -> {
                    assertThat(weapon.abnormal()).isTrue();
                    assertThat(weapon.riskTags()).containsExactly(
                            "显眼", "高噪声", "严格管制");
                });
        assertThat(CocWeaponCatalogConstant.require("BOW"))
                .satisfies(weapon -> {
                    assertThat(weapon.abnormal()).isTrue();
                    assertThat(weapon.riskTags())
                            .containsExactly("显眼", "笨重");
                });
        assertThat(CocWeaponCatalogConstant.require("LANCE"))
                .satisfies(weapon -> {
                    assertThat(weapon.abnormal()).isTrue();
                    assertThat(weapon.riskTags())
                            .containsExactly("显眼", "笨重");
                });
        assertThat(CocWeaponCatalogConstant.require("WHIP"))
                .satisfies(weapon -> {
                    assertThat(weapon.abnormal()).isFalse();
                    assertThat(weapon.riskTags()).containsExactly("显眼");
                });
        assertThat(CocWeaponCatalogConstant.require("SMALL_KNIFE"))
                .satisfies(weapon -> {
                    assertThat(weapon.abnormal()).isFalse();
                    assertThat(weapon.riskTags()).isEmpty();
                });
    }
}

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
                });
    }
}

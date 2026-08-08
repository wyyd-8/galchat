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
                .hasSize(17)
                .contains("BOW", "SMALL_KNIFE", "WOOD_AXE")
                .doesNotContain("WHIP", "CHAINSAW", "TEAR_GAS_SPRAY");
    }

    @Test
    void catalogExcludesTorchWireAndEveryThrowingWeapon() {
        assertThat(CocWeaponCatalogConstant.weapons().values())
                .hasSize(22)
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
                .hasSize(21)
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
}

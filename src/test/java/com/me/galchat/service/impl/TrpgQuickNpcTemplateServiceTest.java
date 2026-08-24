package com.me.galchat.service.impl;

import com.me.galchat.domain.dto.KpQuickNpcDTOs;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.CocSkillDef;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterSkillMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.CocSkillDefMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgQuickNpcTemplateServiceTest {

    @ParameterizedTest
    @MethodSource("templates")
    void materializesEveryStrengthAndWeaponCombination(
            String strength,
            String weapon,
            int expectedAttribute,
            int expectedCombatSkill,
            String expectedDamageBonus,
            int expectedBuild,
            int expectedMov,
            int expectedHp,
            String expectedWeaponName,
            String expectedSkillName,
            String expectedDamage,
            Integer expectedAmmo,
            boolean expectedImpale,
            boolean expectedSkillOverride) {
        Fixture fixture = fixture();

        List<CocCharacter> created = fixture.service().materialize(
                7L,
                List.of(new KpQuickNpcDTOs.Spec(
                        " 临时守卫 ", strength, weapon)));

        assertThat(created).hasSize(1);
        CocCharacter character = created.getFirst();
        assertThat(character.getName()).isEqualTo("临时守卫");
        assertThat(character)
                .extracting(
                        CocCharacter::getStr,
                        CocCharacter::getCon,
                        CocCharacter::getDex)
                .containsExactly(
                        expectedAttribute,
                        expectedAttribute,
                        expectedAttribute);
        assertThat(character)
                .extracting(
                        CocCharacter::getSiz,
                        CocCharacter::getApp,
                        CocCharacter::getIntValue,
                        CocCharacter::getPow,
                        CocCharacter::getEdu)
                .containsExactly(65, 50, 50, 50, 50);
        assertThat(character)
                .extracting(
                        CocCharacter::getDamageBonus,
                        CocCharacter::getBuild,
                        CocCharacter::getMov,
                        CocCharacter::getHpCurrent,
                        CocCharacter::getHpMax,
                        CocCharacter::getSanCurrent,
                        CocCharacter::getSanMax,
                        CocCharacter::getMpCurrent,
                        CocCharacter::getMpMax)
                .containsExactly(
                        expectedDamageBonus, expectedBuild, expectedMov,
                        expectedHp, expectedHp, 50, 99, 10, 10);
        assertThat(character)
                .extracting(
                        CocCharacter::getRunId,
                        CocCharacter::getActorType,
                        CocCharacter::getParticipantId,
                        CocCharacter::getCreationMethod,
                        CocCharacter::getAge,
                        CocCharacter::getLuckCurrent,
                        CocCharacter::getArmor)
                .containsExactly(
                        7L, "NPC", null, "QUICK_NPC_TEMPLATE",
                        null, null, 0);
        assertThat(character)
                .extracting(
                        CocCharacter::getMajorWound,
                        CocCharacter::getUnconscious,
                        CocCharacter::getDying,
                        CocCharacter::getDead,
                        CocCharacter::getTemporaryInsanity,
                        CocCharacter::getInCover,
                        CocCharacter::getCoverActionForfeitPending,
                        CocCharacter::getMeleeAttackedThisRound)
                .containsOnly(false);
        assertThat(character.getStunnedRemainingRounds()).isZero();
        assertThat(character.getCreatedAt()).isNotNull();
        assertThat(character.getUpdatedAt()).isNotNull();

        if (expectedSkillOverride) {
            ArgumentCaptor<CocCharacterSkill> skill =
                    ArgumentCaptor.forClass(CocCharacterSkill.class);
            verify(fixture.skillMapper()).insert(skill.capture());
            assertThat(skill.getValue())
                    .extracting(
                            CocCharacterSkill::getCharacterId,
                            CocCharacterSkill::getDisplayName,
                            CocCharacterSkill::getValue,
                            CocCharacterSkill::getIsCustom)
                    .containsExactly(
                            101L, expectedSkillName,
                            expectedCombatSkill, false);
        } else {
            verify(fixture.skillMapper(), never())
                    .insert(any(CocCharacterSkill.class));
        }

        if (expectedWeaponName == null) {
            verify(fixture.weaponMapper(), never())
                    .insert(any(CocCharacterWeapon.class));
        } else {
            ArgumentCaptor<CocCharacterWeapon> equipped =
                    ArgumentCaptor.forClass(CocCharacterWeapon.class);
            verify(fixture.weaponMapper()).insert(equipped.capture());
            assertThat(equipped.getValue())
                    .extracting(
                            CocCharacterWeapon::getCharacterId,
                            CocCharacterWeapon::getName,
                            CocCharacterWeapon::getSkillName,
                            CocCharacterWeapon::getDamage,
                            CocCharacterWeapon::getAmmoCapacity,
                            CocCharacterWeapon::getRemainingAmmo,
                            CocCharacterWeapon::getCanImpale,
                            CocCharacterWeapon::getIsBroken)
                    .containsExactly(
                            101L, expectedWeaponName, expectedSkillName,
                            expectedDamage, expectedAmmo, expectedAmmo,
                            expectedImpale, false);
        }
    }

    @Test
    void validatesSeveralQuickNpcsAsOneBatch() {
        Fixture fixture = fixture();

        assertThat(fixture.service().validateForRequest(
                7L,
                List.of(
                        new KpQuickNpcDTOs.Spec(
                                " 守卫甲 ", "medium", "pistol"),
                        new KpQuickNpcDTOs.Spec(
                                "守卫乙", "STRONG", "MEDIUM_KNIFE")),
                Set.of("林恩")))
                .containsExactly(
                        new KpQuickNpcDTOs.Spec(
                                "守卫甲", "MEDIUM", "PISTOL"),
                        new KpQuickNpcDTOs.Spec(
                                "守卫乙", "STRONG", "MEDIUM_KNIFE"));
    }

    @Test
    void rejectsAQuickNpcNameAlreadyUsedByAnyCardInTheRun() {
        Fixture fixture = fixture();
        when(fixture.characterMapper().selectList(any()))
                .thenReturn(List.of(new CocCharacter()
                        .setRunId(7L).setName("仓库守卫")));

        assertThatThrownBy(() -> fixture.service().validateForRequest(
                7L,
                List.of(new KpQuickNpcDTOs.Spec(
                        "仓库守卫", "WEAK", "UNARMED")),
                Set.of()))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("人物卡名称已存在")
                .hasMessageContaining("仓库守卫");
    }

    @Test
    void rejectsDuplicateNamesAcrossExistingAndQuickParticipants() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service().validateForRequest(
                7L,
                List.of(new KpQuickNpcDTOs.Spec(
                        "林恩", "WEAK", "UNARMED")),
                Set.of("林恩")))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("参战人物不能重复")
                .hasMessageContaining("林恩");
    }

    private Fixture fixture() {
        CocCharacterMapper characterMapper =
                mock(CocCharacterMapper.class);
        CocCharacterSkillMapper skillMapper =
                mock(CocCharacterSkillMapper.class);
        CocCharacterWeaponMapper weaponMapper =
                mock(CocCharacterWeaponMapper.class);
        CocSkillDefMapper skillDefMapper = mock(CocSkillDefMapper.class);
        when(characterMapper.selectList(any())).thenReturn(List.of());
        when(skillDefMapper.selectList(any())).thenReturn(List.of(
                skill(1L, "斗殴", "格斗", 25),
                skill(2L, "射击:手枪", "射击", 20),
                skill(3L, "射击:步枪/霰弹枪", "射击", 25)));
        AtomicLong characterIds = new AtomicLong(101L);
        doAnswer(invocation -> {
            invocation.<CocCharacter>getArgument(0)
                    .setId(characterIds.getAndIncrement());
            return 1;
        }).when(characterMapper).insert(any(CocCharacter.class));
        when(skillMapper.insert(any(CocCharacterSkill.class)))
                .thenReturn(1);
        when(weaponMapper.insert(any(CocCharacterWeapon.class)))
                .thenReturn(1);
        return new Fixture(
                new TrpgQuickNpcTemplateService(
                        characterMapper, skillMapper,
                        weaponMapper, skillDefMapper),
                characterMapper, skillMapper, weaponMapper);
    }

    private CocSkillDef skill(
            Long id, String name, String category, int base) {
        CocSkillDef definition = new CocSkillDef();
        definition.setId(id);
        definition.setName(name);
        definition.setCategory(category);
        definition.setBaseValue(base);
        definition.setIsCore(true);
        return definition;
    }

    private static Stream<Arguments> templates() {
        return Stream.of("WEAK", "MEDIUM", "STRONG")
                .flatMap(strength -> Stream.of(
                        Arguments.of(strength, "UNARMED"),
                        Arguments.of(strength, "LARGE_CLUB"),
                        Arguments.of(strength, "MEDIUM_KNIFE"),
                        Arguments.of(strength, "PISTOL"),
                        Arguments.of(strength, "SMALL_RIFLE"),
                        Arguments.of(strength, "HUNTING_RIFLE")))
                .map(arguments -> expand(
                        (String) arguments.get()[0],
                        (String) arguments.get()[1]));
    }

    private static Arguments expand(String strength, String weapon) {
        int attribute = switch (strength) {
            case "WEAK" -> 50;
            case "MEDIUM" -> 60;
            case "STRONG" -> 70;
            default -> throw new IllegalArgumentException(strength);
        };
        int skill = switch (strength) {
            case "WEAK" -> 25;
            case "MEDIUM" -> 40;
            case "STRONG" -> 70;
            default -> throw new IllegalArgumentException(strength);
        };
        String damageBonus = "WEAK".equals(strength) ? "0" : "+1D4";
        int build = "WEAK".equals(strength) ? 0 : 1;
        int mov = "STRONG".equals(strength) ? 9 : 7;
        int hp = switch (strength) {
            case "WEAK" -> 11;
            case "MEDIUM" -> 12;
            case "STRONG" -> 13;
            default -> throw new IllegalArgumentException(strength);
        };
        String weaponName = switch (weapon) {
            case "UNARMED" -> null;
            case "LARGE_CLUB" -> "大型棍棒（棒球棒、板球棒、拨火棍）";
            case "MEDIUM_KNIFE" -> "中型刀具（切肉刀等）";
            case "PISTOL" -> ".38/9mm自动手枪";
            case "SMALL_RIFLE" -> ".22栓动步枪";
            case "HUNTING_RIFLE" -> ".30杠杆步枪";
            default -> throw new IllegalArgumentException(weapon);
        };
        String skillName = switch (weapon) {
            case "UNARMED", "LARGE_CLUB", "MEDIUM_KNIFE" -> "斗殴";
            case "PISTOL" -> "射击:手枪";
            case "SMALL_RIFLE", "HUNTING_RIFLE" -> "射击:步枪/霰弹枪";
            default -> throw new IllegalArgumentException(weapon);
        };
        String damage = switch (weapon) {
            case "UNARMED" -> null;
            case "LARGE_CLUB" -> "1D8+DB";
            case "MEDIUM_KNIFE" -> "1D4+2+DB";
            case "PISTOL" -> "1D10";
            case "SMALL_RIFLE" -> "1D6+1";
            case "HUNTING_RIFLE" -> "2D6";
            default -> throw new IllegalArgumentException(weapon);
        };
        Integer ammo = switch (weapon) {
            case "PISTOL" -> 8;
            case "SMALL_RIFLE", "HUNTING_RIFLE" -> 6;
            default -> null;
        };
        boolean impale = Set.of(
                "MEDIUM_KNIFE", "PISTOL",
                "SMALL_RIFLE", "HUNTING_RIFLE").contains(weapon);
        int base = switch (skillName) {
            case "射击:手枪" -> 20;
            case "斗殴", "射击:步枪/霰弹枪" -> 25;
            default -> throw new IllegalArgumentException(skillName);
        };
        return Arguments.of(
                strength, weapon, attribute, skill,
                damageBonus, build, mov, hp,
                weaponName, skillName, damage, ammo,
                impale, skill != base);
    }

    private record Fixture(
            TrpgQuickNpcTemplateService service,
            CocCharacterMapper characterMapper,
            CocCharacterSkillMapper skillMapper,
            CocCharacterWeaponMapper weaponMapper) {
    }
}

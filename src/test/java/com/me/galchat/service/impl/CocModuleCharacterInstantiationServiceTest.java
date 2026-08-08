package com.me.galchat.service.impl;

import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.CocModuleCharacter;
import com.me.galchat.domain.po.CocSkillDef;
import com.me.galchat.domain.vo.CharacterCardVO;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.CocCharacterSkillMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.CocModuleCharacterMapper;
import com.me.galchat.mapper.CocSkillDefMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CocModuleCharacterInstantiationServiceTest {

    @Test
    void reportsUnreadableTemplateDataAsAUserRequestError() {
        Fixture fixture = fixture();
        when(fixture.moduleCharacterMapper().selectList(any()))
                .thenThrow(new IllegalArgumentException("invalid jsonb"));

        assertThatThrownBy(() -> fixture.service().instantiate(3L, 7L))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessage("模组人物卡数据无法置入")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void copiesCompleteCardGraphIntoConversationRun() {
        Fixture fixture = fixture();
        when(fixture.moduleCharacterMapper().selectList(any()))
                .thenReturn(List.of(template(31L, " 沃尔特 ",
                        List.of(new CocCharacterSkill()
                                .setId(101L)
                                .setCharacterId(201L)
                                .setDisplayName("斗殴")
                                .setSpecialization("")
                                .setBaseValue(25)
                                .setValue(50),
                                new CocCharacterSkill()
                                        .setId(104L)
                                        .setCharacterId(201L)
                                        .setDisplayName("聆听")
                                        .setBaseValue(20)
                                        .setValue(20)),
                        List.of(new CocCharacterWeapon()
                                .setId(102L)
                                .setCharacterId(201L)
                                .setName("浮空匕首")
                                .setDamage("1D4+2")),
                        new CocCharacterProfile()
                                .setId(103L)
                                .setCharacterId(201L)
                                .setNotes("血肉防护术"))));
        when(fixture.characterMapper().selectList(any()))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            ((CocCharacter) invocation.getArgument(0)).setId(901L);
            return 1;
        }).when(fixture.characterMapper())
                .insert(any(CocCharacter.class));

        int copied = fixture.service().instantiate(3L, 7L);

        assertThat(copied).isEqualTo(1);
        var characterCaptor =
                org.mockito.ArgumentCaptor.forClass(CocCharacter.class);
        verify(fixture.characterMapper()).insert(characterCaptor.capture());
        assertThat(characterCaptor.getValue())
                .extracting(
                        CocCharacter::getRunId,
                        CocCharacter::getActorType,
                        CocCharacter::getParticipantId,
                        CocCharacter::getCreationMethod,
                        CocCharacter::getName)
                .containsExactly(7L, "NPC", null, "MODULE", "沃尔特");
        assertThat(characterCaptor.getValue().getCreatedAt()).isNotNull();
        assertThat(characterCaptor.getValue().getUpdatedAt()).isNotNull();

        var skillCaptor =
                org.mockito.ArgumentCaptor.forClass(CocCharacterSkill.class);
        verify(fixture.skillMapper()).insert(skillCaptor.capture());
        assertThat(skillCaptor.getValue().getId()).isNull();
        assertThat(skillCaptor.getValue().getCharacterId()).isEqualTo(901L);
        assertThat(skillCaptor.getValue().getDisplayName()).isEqualTo("斗殴");

        var weaponCaptor =
                org.mockito.ArgumentCaptor.forClass(CocCharacterWeapon.class);
        verify(fixture.weaponMapper()).insert(weaponCaptor.capture());
        assertThat(weaponCaptor.getValue().getId()).isNull();
        assertThat(weaponCaptor.getValue().getCharacterId()).isEqualTo(901L);
        assertThat(weaponCaptor.getValue().getName()).isEqualTo("浮空匕首");

        var profileCaptor =
                org.mockito.ArgumentCaptor.forClass(CocCharacterProfile.class);
        verify(fixture.profileMapper()).insert(profileCaptor.capture());
        assertThat(profileCaptor.getValue().getId()).isNull();
        assertThat(profileCaptor.getValue().getCharacterId()).isEqualTo(901L);
        assertThat(profileCaptor.getValue().getNotes())
                .isEqualTo("血肉防护术");
    }

    @Test
    void skipsExistingAndLaterTemplateCardsWithTheSameTrimmedName() {
        Fixture fixture = fixture();
        when(fixture.characterMapper().selectList(any())).thenReturn(List.of(
                new CocCharacter().setName("守卫")));
        when(fixture.moduleCharacterMapper().selectList(any()))
                .thenReturn(List.of(
                        template(31L, " 守卫 ", List.of(), List.of(), null),
                        template(32L, " 祭司 ", List.of(), List.of(), null),
                        template(33L, "祭司", List.of(), List.of(), null)));
        doAnswer(invocation -> {
            ((CocCharacter) invocation.getArgument(0)).setId(902L);
            return 1;
        }).when(fixture.characterMapper())
                .insert(any(CocCharacter.class));

        int copied = fixture.service().instantiate(3L, 7L);

        assertThat(copied).isEqualTo(1);
        var characterCaptor =
                org.mockito.ArgumentCaptor.forClass(CocCharacter.class);
        verify(fixture.characterMapper(), times(1))
                .insert(characterCaptor.capture());
        assertThat(characterCaptor.getValue().getName()).isEqualTo("祭司");
    }

    private Fixture fixture() {
        CocModuleCharacterMapper moduleCharacterMapper =
                mock(CocModuleCharacterMapper.class);
        CocCharacterMapper characterMapper =
                mock(CocCharacterMapper.class);
        CocCharacterSkillMapper skillMapper =
                mock(CocCharacterSkillMapper.class);
        CocCharacterWeaponMapper weaponMapper =
                mock(CocCharacterWeaponMapper.class);
        CocCharacterProfileMapper profileMapper =
                mock(CocCharacterProfileMapper.class);
        CocSkillDefMapper skillDefMapper = mock(CocSkillDefMapper.class);
        CocSkillDef brawl = new CocSkillDef();
        brawl.setId(1L);
        brawl.setName("斗殴");
        brawl.setBaseValue(25);
        CocSkillDef listen = new CocSkillDef();
        listen.setId(2L);
        listen.setName("聆听");
        listen.setBaseValue(20);
        when(skillDefMapper.selectList(null)).thenReturn(List.of(brawl, listen));
        return new Fixture(
                new CocModuleCharacterInstantiationService(
                        moduleCharacterMapper, characterMapper,
                        skillMapper, weaponMapper, profileMapper,
                        skillDefMapper, new CharacterSkillResolver(),
                        JsonMapper.builder().build()),
                moduleCharacterMapper, characterMapper,
                skillMapper, weaponMapper, profileMapper);
    }

    private CocModuleCharacter template(
            Long id,
            String name,
            List<CocCharacterSkill> skills,
            List<CocCharacterWeapon> weapons,
            CocCharacterProfile profile) {
        CocCharacter character = new CocCharacter()
                .setId(201L)
                .setRunId(5L)
                .setActorType("PLAYER")
                .setParticipantId(99L)
                .setCreationMethod("IMPORT")
                .setName(name)
                .setStr(90).setCon(115).setSiz(55).setDex(35)
                .setApp(5).setIntValue(80).setPow(90).setEdu(80)
                .setDamageBonus("+1D4").setBuild(1).setMov(8)
                .setHpCurrent(9).setHpMax(9)
                .setSanCurrent(0).setSanMax(0)
                .setMpCurrent(18).setMpMax(18)
                .setArmor(0)
                .setCreatedAt(LocalDateTime.of(2020, 1, 1, 0, 0))
                .setUpdatedAt(LocalDateTime.of(2020, 1, 1, 0, 0));
        return new CocModuleCharacter()
                .setId(id)
                .setModuleId(3L)
                .setSortOrder(id.intValue())
                .setCardData(JsonMapper.builder().build().valueToTree(
                        new CharacterCardVO(
                                character, skills, weapons, profile)));
    }

    private record Fixture(
            CocModuleCharacterInstantiationService service,
            CocModuleCharacterMapper moduleCharacterMapper,
            CocCharacterMapper characterMapper,
            CocCharacterSkillMapper skillMapper,
            CocCharacterWeaponMapper weaponMapper,
            CocCharacterProfileMapper profileMapper) {
    }
}

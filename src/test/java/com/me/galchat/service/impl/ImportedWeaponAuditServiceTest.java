package com.me.galchat.service.impl;

import com.me.galchat.constant.CocWeaponCatalogConstant;
import com.me.galchat.domain.dto.ImportedWeaponAuditModels;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportedWeaponAuditServiceTest {

    @Test
    void hydratesCatalogFieldsWhilePreservingImportedName() {
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        CocCharacterWeaponMapper weaponMapper =
                mock(CocCharacterWeaponMapper.class);
        ImportedWeaponAuditModel model = mock(ImportedWeaponAuditModel.class);
        CocCharacter character = new CocCharacter().setId(71L)
                .setCreationMethod("IMPORT").setName("林恩").setEra("现代");
        CocCharacterWeapon pistol = new CocCharacterWeapon().setId(81L)
                .setCharacterId(71L).setName("袖珍手枪")
                .setDamage("4D10").setRemainingAmmo(1)
                .setAbnormal(true).setRiskTags(List.of("旧标签"));
        when(characterMapper.selectById(71L)).thenReturn(character);
        when(weaponMapper.selectList(any())).thenReturn(List.of(pistol));
        when(model.review(eq(character), eq(List.of(pistol)), any(), any()))
                .thenReturn(new ImportedWeaponAuditModels.Response(List.of(
                        new ImportedWeaponAuditModels.Review(
                                81L, "PISTOL_22_AUTO"))));
        ImportedWeaponAuditService service = new ImportedWeaponAuditService(
                characterMapper, weaponMapper, model);

        service.audit(71L);

        assertThat(pistol.getName()).isEqualTo("袖珍手枪");
        assertThat(pistol.getSkillName()).isEqualTo("射击:手枪");
        assertThat(pistol.getDamage()).isEqualTo("1D6");
        assertThat(pistol.getRange()).isEqualTo("10m");
        assertThat(pistol.getAttacksPerRound()).isEqualTo("1（3）");
        assertThat(pistol.getAmmoCapacity()).isEqualTo(6);
        assertThat(pistol.getRemainingAmmo()).isEqualTo(6);
        assertThat(pistol.getMalfunction()).isEqualTo("100");
        assertThat(pistol.getCanImpale()).isTrue();
        assertThat(pistol.getIsBroken()).isFalse();
        assertThat(pistol.getAbnormal()).isFalse();
        assertThat(pistol.getRiskTags()).containsExactly("高噪声");

        ArgumentCaptor<CocCharacterWeapon> captor =
                ArgumentCaptor.forClass(CocCharacterWeapon.class);
        verify(weaponMapper).updateById(captor.capture());
        CocCharacterWeapon update = captor.getValue();
        assertThat(update.getId()).isEqualTo(81L);
        assertThat(update.getName()).isNull();
        assertThat(update.getSkillName()).isEqualTo("射击:手枪");
        assertThat(update.getDamage()).isEqualTo("1D6");
        assertThat(update.getRange()).isEqualTo("10m");
        assertThat(update.getAttacksPerRound()).isEqualTo("1（3）");
        assertThat(update.getAmmoCapacity()).isEqualTo(6);
        assertThat(update.getRemainingAmmo()).isEqualTo(6);
        assertThat(update.getMalfunction()).isEqualTo("100");
        assertThat(update.getCanImpale()).isTrue();
        assertThat(update.getIsBroken()).isFalse();
        assertThat(update.getAbnormal()).isFalse();
        assertThat(update.getRiskTags()).containsExactly("高噪声");
    }

    @Test
    void usesLargeClubFieldsWhenAWeaponCannotBeMatched() {
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        CocCharacterWeaponMapper weaponMapper =
                mock(CocCharacterWeaponMapper.class);
        ImportedWeaponAuditModel model = mock(ImportedWeaponAuditModel.class);
        CocCharacter character = new CocCharacter().setId(71L)
                .setCreationMethod("IMPORT").setEra("现代");
        CocCharacterWeapon pistol = new CocCharacterWeapon().setId(81L)
                .setCharacterId(71L).setName("手枪");
        CocCharacterWeapon unknown = new CocCharacterWeapon().setId(82L)
                .setCharacterId(71L).setName("神秘武器");
        when(characterMapper.selectById(71L)).thenReturn(character);
        when(weaponMapper.selectList(any())).thenReturn(List.of(pistol, unknown));
        when(model.review(eq(character), eq(List.of(pistol, unknown)),
                any(), any())).thenReturn(
                new ImportedWeaponAuditModels.Response(List.of(
                        new ImportedWeaponAuditModels.Review(81L, "GLOCK_17"),
                        new ImportedWeaponAuditModels.Review(82L, null),
                        new ImportedWeaponAuditModels.Review(999L, "NOT_A_CODE"))));
        ImportedWeaponAuditService service = new ImportedWeaponAuditService(
                characterMapper, weaponMapper, model);

        service.audit(71L);

        assertThat(pistol.getName()).isEqualTo("手枪");
        assertThat(pistol.getDamage()).isEqualTo(
                CocWeaponCatalogConstant.require("PISTOL_38_9MM").damage());
        CocWeaponCatalogConstant.WeaponDefinition largeClub =
                CocWeaponCatalogConstant.require("LARGE_CLUB");
        assertThat(unknown.getName()).isEqualTo("神秘武器");
        assertThat(unknown.getSkillName()).isEqualTo(
                largeClub.requiredSkillName());
        assertThat(unknown.getDamage()).isEqualTo(largeClub.damage());
        assertThat(unknown.getRange()).isEqualTo(largeClub.range());
        assertThat(unknown.getAttacksPerRound()).isEqualTo(
                largeClub.attacksPerRound());
        assertThat(unknown.getAmmoCapacity()).isNull();
        assertThat(unknown.getRemainingAmmo()).isNull();
        assertThat(unknown.getMalfunction()).isNull();
        assertThat(unknown.getCanImpale()).isEqualTo(largeClub.canImpale());
        assertThat(unknown.getIsBroken()).isFalse();
        assertThat(unknown.getAbnormal()).isFalse();
        assertThat(unknown.getRiskTags()).containsExactly("未识别武器");
        verify(weaponMapper, org.mockito.Mockito.times(2))
                .updateById(any(CocCharacterWeapon.class));
    }

    @Test
    void suppliesTheFullCatalogAndEraSpecificBroadDefaultsToTheModel() {
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        CocCharacterWeaponMapper weaponMapper =
                mock(CocCharacterWeaponMapper.class);
        ImportedWeaponAuditModel model = mock(ImportedWeaponAuditModel.class);
        CocCharacter character = new CocCharacter().setId(71L)
                .setCreationMethod("IMPORT").setEra("1920s");
        CocCharacterWeapon weapon = new CocCharacterWeapon().setId(81L)
                .setCharacterId(71L).setName("冲锋枪");
        when(characterMapper.selectById(71L)).thenReturn(character);
        when(weaponMapper.selectList(any())).thenReturn(List.of(weapon));
        when(model.review(eq(character), eq(List.of(weapon)), any(), any()))
                .thenReturn(new ImportedWeaponAuditModels.Response(List.of()));
        ImportedWeaponAuditService service = new ImportedWeaponAuditService(
                characterMapper, weaponMapper, model);

        service.audit(71L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CocWeaponCatalogConstant.WeaponDefinition>>
                catalogCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> defaultsCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(model).review(eq(character), eq(List.of(weapon)),
                catalogCaptor.capture(), defaultsCaptor.capture());
        assertThat(catalogCaptor.getValue())
                .hasSize(CocWeaponCatalogConstant.weapons().size());
        assertThat(defaultsCaptor.getValue())
                .containsEntry("冲锋枪", "THOMPSON_SMG")
                .containsEntry("手枪", "PISTOL_38_9MM")
                .containsEntry("刀", "MEDIUM_KNIFE");
    }

    @Test
    void ignoresCardsThatWereNotCreatedByManualImport() {
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        CocCharacterWeaponMapper weaponMapper =
                mock(CocCharacterWeaponMapper.class);
        ImportedWeaponAuditModel model = mock(ImportedWeaponAuditModel.class);
        when(characterMapper.selectById(71L)).thenReturn(
                new CocCharacter().setId(71L).setCreationMethod("AUTO"));

        new ImportedWeaponAuditService(characterMapper, weaponMapper, model)
                .audit(71L);

        verify(weaponMapper, never()).selectList(any());
        verify(model, never()).review(any(), any(), any(), any());
    }
}

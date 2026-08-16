package com.me.galchat.service.impl;

import com.me.galchat.domain.dto.ImportedWeaponAuditModels;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportedWeaponAuditServiceTest {

    @Test
    void appliesOnlyReviewsForWeaponsOwnedByTheImportedCard() {
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        CocCharacterWeaponMapper weaponMapper =
                mock(CocCharacterWeaponMapper.class);
        ImportedWeaponAuditModel model = mock(ImportedWeaponAuditModel.class);
        CocCharacter character = new CocCharacter().setId(71L)
                .setCreationMethod("IMPORT").setName("林恩").setEra("现代");
        CocCharacterWeapon pistol = new CocCharacterWeapon().setId(81L)
                .setCharacterId(71L).setName("袖珍手枪")
                .setDamage("4D10").setRemainingAmmo(6)
                .setAbnormal(false).setRiskTags(List.of());
        CocCharacterWeapon knife = new CocCharacterWeapon().setId(82L)
                .setCharacterId(71L).setName("折叠刀")
                .setDamage("1D4").setAbnormal(true)
                .setRiskTags(List.of("旧标签"));
        when(characterMapper.selectById(71L)).thenReturn(character);
        when(weaponMapper.selectList(any())).thenReturn(List.of(pistol, knife));
        when(model.review(character, List.of(pistol, knife))).thenReturn(
                new ImportedWeaponAuditModels.Response(List.of(
                        new ImportedWeaponAuditModels.Review(
                                81L, true,
                                List.of(" 伤害异常 ", "", "高噪声", "伤害异常")),
                        new ImportedWeaponAuditModels.Review(
                                82L, false, List.of("不应保留")),
                        new ImportedWeaponAuditModels.Review(
                                999L, true, List.of("越权武器")))));
        ImportedWeaponAuditService service = new ImportedWeaponAuditService(
                characterMapper, weaponMapper, model);

        service.audit(71L);

        assertThat(pistol.getAbnormal()).isTrue();
        assertThat(pistol.getRiskTags())
                .containsExactly("伤害异常", "高噪声");
        assertThat(knife.getAbnormal()).isFalse();
        assertThat(knife.getRiskTags()).isEmpty();
        var captor = org.mockito.ArgumentCaptor.forClass(
                CocCharacterWeapon.class);
        verify(weaponMapper, org.mockito.Mockito.times(2))
                .updateById(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(CocCharacterWeapon::getId)
                .containsExactly(81L, 82L);
        assertThat(captor.getAllValues())
                .allSatisfy(update -> {
                    assertThat(update.getName()).isNull();
                    assertThat(update.getDamage()).isNull();
                    assertThat(update.getRemainingAmmo()).isNull();
                });
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
        verify(model, never()).review(any(), any());
    }
}

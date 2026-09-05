package com.me.galchat.service.impl.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.KpEquipmentDTOs;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.TrpgWeaponStash;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.TrpgWeaponStashMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrpgEquipmentServiceTest {

    @Mock
    private CocCharacterMapper characterMapper;
    @Mock
    private CocCharacterProfileMapper profileMapper;
    @Mock
    private CocCharacterWeaponMapper weaponMapper;
    @Mock
    private TrpgWeaponStashMapper stashMapper;
    @Mock
    private GroupConversationMapper conversationMapper;
    @Mock
    private TrpgSceneParticipantService participantService;

    private TrpgEquipmentService service;

    @BeforeEach
    void setUp() {
        service = new TrpgEquipmentService(
                characterMapper, profileMapper, weaponMapper,
                stashMapper, conversationMapper, participantService);
    }

    @Test
    void stashWeaponPreservesItsStateAndRecordsBackendScenePath() {
        GroupConversation conversation = conversation();
        CocCharacter owner = character(401L, "林恩");
        CocCharacterWeapon weapon = weapon(901L, 401L)
                .setRemainingAmmo(3)
                .setIsBroken(true)
                .setNotes("枪柄刻着L.E.");
        when(conversationMapper.selectById(51L))
                .thenReturn(conversation);
        when(participantService.state(conversation)).thenReturn(
                new TrpgSceneParticipantService.SceneState(
                        101L, "圣玛丽医院 - 阁楼",
                        List.of("林恩"), List.of(401L), List.of()));
        when(characterMapper.selectList(any()))
                .thenReturn(List.of(owner));
        when(weaponMapper.selectByCharacterIdAndNameForUpdate(
                401L, ".38/9mm自动手枪"))
                .thenReturn(List.of(weapon));
        when(stashMapper.insert(any(TrpgWeaponStash.class))).thenReturn(1);
        when(weaponMapper.deleteById(901L)).thenReturn(1);

        KpEquipmentDTOs.StashResult result = service.stashWeapon(
                51L, "林恩", ".38/9mm自动手枪",
                KpEquipmentDTOs.StashReason.DISARMED);

        ArgumentCaptor<TrpgWeaponStash> stash =
                ArgumentCaptor.forClass(TrpgWeaponStash.class);
        verify(stashMapper).insert(stash.capture());
        assertThat(stash.getValue())
                .extracting(TrpgWeaponStash::getWeaponId,
                        TrpgWeaponStash::getRunId,
                        TrpgWeaponStash::getSourceCharacterName,
                        TrpgWeaponStash::getLocationName,
                        TrpgWeaponStash::getStashReason)
                .containsExactly(901L, 51L, "林恩",
                        "圣玛丽医院 - 阁楼", "DISARMED");
        assertThat(stash.getValue().getWeaponSnapshot())
                .extracting(TrpgWeaponStash.WeaponSnapshot::getName,
                        TrpgWeaponStash.WeaponSnapshot::getRemainingAmmo,
                        TrpgWeaponStash.WeaponSnapshot::getIsBroken,
                        TrpgWeaponStash.WeaponSnapshot::getNotes)
                .containsExactly(".38/9mm自动手枪", 3, true,
                        "枪柄刻着L.E.");
        assertThat(stash.getValue().getStashedAt()).isNotNull();
        InOrder writes = inOrder(stashMapper, weaponMapper);
        writes.verify(stashMapper).insert(any(TrpgWeaponStash.class));
        writes.verify(weaponMapper).deleteById(901L);
        assertThat(result).isEqualTo(new KpEquipmentDTOs.StashResult(
                901L, ".38/9mm自动手枪", "林恩",
                "圣玛丽医院 - 阁楼",
                KpEquipmentDTOs.StashReason.DISARMED));
    }

    @Test
    void equipFromStashKeepsStableWeaponIdAndSnapshotState() {
        CocCharacter target = character(402L, "周远");
        TrpgWeaponStash stashed = new TrpgWeaponStash()
                .setWeaponId(901L)
                .setRunId(51L)
                .setSourceCharacterName("林恩")
                .setLocationName("圣玛丽医院 - 阁楼")
                .setStashReason("SEIZED")
                .setWeaponSnapshot(snapshot()
                        .setRemainingAmmo(2)
                        .setIsBroken(true));
        when(stashMapper.selectByRunIdAndWeaponIdForUpdate(51L, 901L))
                .thenReturn(stashed);
        when(characterMapper.selectList(any()))
                .thenReturn(List.of(target));
        when(weaponMapper.selectByCharacterIdAndNameForUpdate(
                402L, ".38/9mm自动手枪"))
                .thenReturn(List.of());
        when(weaponMapper.insert(any(CocCharacterWeapon.class))).thenReturn(1);
        when(stashMapper.deleteById(901L)).thenReturn(1);

        KpEquipmentDTOs.EquipResult result =
                service.equipWeaponFromStash(51L, 901L, "周远");

        ArgumentCaptor<CocCharacterWeapon> restored =
                ArgumentCaptor.forClass(CocCharacterWeapon.class);
        verify(weaponMapper).insert(restored.capture());
        assertThat(restored.getValue())
                .extracting(CocCharacterWeapon::getId,
                        CocCharacterWeapon::getCharacterId,
                        CocCharacterWeapon::getName,
                        CocCharacterWeapon::getRemainingAmmo,
                        CocCharacterWeapon::getIsBroken)
                .containsExactly(901L, 402L,
                        ".38/9mm自动手枪", 2, true);
        InOrder writes = inOrder(weaponMapper, stashMapper);
        writes.verify(weaponMapper).insert(any(CocCharacterWeapon.class));
        writes.verify(stashMapper).deleteById(901L);
        assertThat(result).isEqualTo(new KpEquipmentDTOs.EquipResult(
                901L, ".38/9mm自动手枪", "周远"));
    }

    @Test
    void purchaseEquipmentAddsCatalogWeaponAndPlainItemsForManyCharacters() {
        CocCharacter lin = character(401L, "林恩");
        CocCharacter zhou = character(402L, "周远");
        CocCharacterProfile zhouProfile = new CocCharacterProfile()
                .setId(502L).setCharacterId(402L)
                .setEquipmentText("绷带");
        CocCharacterProfile linProfile = new CocCharacterProfile()
                .setId(501L).setCharacterId(401L);
        when(characterMapper.selectList(any()))
                .thenReturn(List.of(lin), List.of(zhou));
        when(weaponMapper.selectByCharacterIdAndNameForUpdate(
                401L, ".38/9mm自动手枪"))
                .thenReturn(List.of());
        when(profileMapper.selectList(any()))
                .thenReturn(List.of(zhouProfile), List.of(linProfile));
        when(weaponMapper.insert(any(CocCharacterWeapon.class))).thenReturn(1);
        when(profileMapper.updateById(any(CocCharacterProfile.class)))
                .thenReturn(1);
        KpEquipmentDTOs.PurchaseRequest request =
                new KpEquipmentDTOs.PurchaseRequest(List.of(
                        new KpEquipmentDTOs.PurchaseEntry(
                                "林恩", KpEquipmentDTOs.PurchaseType.WEAPON,
                                ".38/9mm自动手枪"),
                        new KpEquipmentDTOs.PurchaseEntry(
                                "周远", KpEquipmentDTOs.PurchaseType.ITEM,
                                "急救包"),
                        new KpEquipmentDTOs.PurchaseEntry(
                                "林恩", KpEquipmentDTOs.PurchaseType.ITEM,
                                "手电筒")));

        KpEquipmentDTOs.PurchaseResult result =
                service.purchaseEquipment(51L, request);

        ArgumentCaptor<CocCharacterWeapon> purchasedWeapon =
                ArgumentCaptor.forClass(CocCharacterWeapon.class);
        verify(weaponMapper).insert(purchasedWeapon.capture());
        assertThat(purchasedWeapon.getValue())
                .extracting(CocCharacterWeapon::getCharacterId,
                        CocCharacterWeapon::getName,
                        CocCharacterWeapon::getSkillName,
                        CocCharacterWeapon::getDamage,
                        CocCharacterWeapon::getAmmoCapacity,
                        CocCharacterWeapon::getRemainingAmmo,
                        CocCharacterWeapon::getIsBroken)
                .containsExactly(401L, ".38/9mm自动手枪",
                        "射击:手枪", "1D10", 8, 8, false);
        assertThat(zhouProfile.getEquipmentText())
                .isEqualTo("绷带；急救包");
        assertThat(linProfile.getEquipmentText())
                .isEqualTo("手电筒");
        assertThat(result.entries()).containsExactly(
                new KpEquipmentDTOs.PurchaseLineResult(
                        "林恩", KpEquipmentDTOs.PurchaseType.WEAPON,
                        ".38/9mm自动手枪"),
                new KpEquipmentDTOs.PurchaseLineResult(
                        "周远", KpEquipmentDTOs.PurchaseType.ITEM,
                        "急救包"),
                new KpEquipmentDTOs.PurchaseLineResult(
                        "林恩", KpEquipmentDTOs.PurchaseType.ITEM,
                        "手电筒"));
    }

    @Test
    void purchaseRejectsUnknownWeaponNameBeforeWritingAnything() {
        when(characterMapper.selectList(any()))
                .thenReturn(List.of(character(401L, "林恩")));
        KpEquipmentDTOs.PurchaseRequest request =
                new KpEquipmentDTOs.PurchaseRequest(List.of(
                        new KpEquipmentDTOs.PurchaseEntry(
                                "林恩", KpEquipmentDTOs.PurchaseType.WEAPON,
                                "不存在的激光枪")));

        assertThatThrownBy(() -> service.purchaseEquipment(51L, request))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("武器目录");
        verify(weaponMapper, never()).insert(any(CocCharacterWeapon.class));
        verify(profileMapper, never()).updateById(
                any(CocCharacterProfile.class));
    }

    private GroupConversation conversation() {
        return new GroupConversation()
                .setId(51L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setActiveReplyPlanId(101L);
    }

    private CocCharacter character(Long id, String name) {
        return new CocCharacter().setId(id).setRunId(51L).setName(name);
    }

    private CocCharacterWeapon weapon(Long id, Long characterId) {
        return new CocCharacterWeapon()
                .setId(id)
                .setCharacterId(characterId)
                .setName(".38/9mm自动手枪")
                .setSkillName("射击:手枪")
                .setDamage("1D10")
                .setRange("15m")
                .setAttacksPerRound("1（3）")
                .setAmmoCapacity(8)
                .setRemainingAmmo(8)
                .setMalfunction("99")
                .setCanImpale(true)
                .setIsBroken(false)
                .setAbnormal(false)
                .setRiskTags(List.of("显眼"));
    }

    private TrpgWeaponStash.WeaponSnapshot snapshot() {
        return new TrpgWeaponStash.WeaponSnapshot()
                .setName(".38/9mm自动手枪")
                .setSkillName("射击:手枪")
                .setDamage("1D10")
                .setRange("15m")
                .setAttacksPerRound("1（3）")
                .setAmmoCapacity(8)
                .setRemainingAmmo(8)
                .setMalfunction("99")
                .setCanImpale(true)
                .setIsBroken(false)
                .setAbnormal(false)
                .setRiskTags(List.of("显眼"));
    }
}

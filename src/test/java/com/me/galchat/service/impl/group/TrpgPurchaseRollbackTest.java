package com.me.galchat.service.impl.group;

import com.me.galchat.domain.dto.KpEquipmentDTOs;
import com.me.galchat.domain.po.*;
import com.me.galchat.mapper.*;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.service.impl.trpg.*;
import com.me.galchat.groupchat.dice.DiceRollMessageCodec;
import com.me.galchat.support.MybatisPlusTestSupport;
import org.junit.jupiter.api.*;
import org.mockito.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrpgPurchaseRollbackTest {
    @Mock GroupTurnCheckpointMapper checkpoints;
    @Mock GroupChatMessageMapper messages;
    @Mock GroupChatToolCallMapper calls;
    @Mock GroupChatReplyStepMapper steps;
    @Mock GroupChatTurnMapper turns;
    @Mock DiceRollSummaryMapper dice;
    @Mock DiceRollMessageCodec codec;
    @Mock ICharacterCardService cards;
    @Mock GroupChatFavorRollbackService favor;
    @Mock TrpgMaterialRecoveryService materialRecovery;
    @Spy ObjectMapper json = JsonMapper.builder().build();
    final CocCharacterMapper characters = mock(CocCharacterMapper.class);
    final CocCharacterProfileMapper profiles = mock(CocCharacterProfileMapper.class);
    final CocCharacterWeaponMapper weapons = mock(CocCharacterWeaponMapper.class);
    @Spy TrpgEquipmentService equipment = new TrpgEquipmentService(characters, profiles, weapons,
            mock(TrpgWeaponStashMapper.class), mock(GroupConversationMapper.class), mock(TrpgSceneParticipantService.class));
    @InjectMocks GroupTurnCheckpointService service;
    AutoCloseable mocks;
    final CocCharacterProfile profile = new CocCharacterProfile().setId(501L).setCharacterId(401L).setEquipmentText("背包");
    long boundary = 0L;
    final List<GroupChatToolCall> records = new ArrayList<>();
    final GroupChatTurn turn = new GroupChatTurn().setId(101L).setConversationId(7L);
    final GroupChatReplyStep step = new GroupChatReplyStep().setId(103L).setTurnId(101L);

    @BeforeEach void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        MybatisPlusTestSupport.initialize(GroupChatReplyStep.class, GroupChatToolCall.class,
                CocCharacterProfile.class, CocCharacter.class, CocCharacterWeapon.class);
        var character = new CocCharacter().setId(401L).setRunId(7L).setName("林恩");
        when(characters.selectList(any())).thenReturn(List.of(character));
        when(profiles.selectList(any())).thenReturn(List.of(profile));
        when(profiles.updateById(any(CocCharacterProfile.class))).thenReturn(1);
        when(profiles.update(isNull(), any())).thenAnswer(i -> {
            var w = i.<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CocCharacterProfile>>getArgument(1);
            w.getSqlSet();
            profile.setEquipmentText((String) w.getParamNameValuePairs().get("MPGENVAL1"));
            return 1;
        });
        when(checkpoints.selectById(7L)).thenReturn(new GroupTurnCheckpoint().setTurnId(101L)
                .setReplyStepId(103L).setCheckpointType("STEP_START").setMessageId(0L).setToolCallId(0L));
        when(calls.selectList(any())).thenAnswer(i -> {
            var w = i.<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GroupChatToolCall>>getArgument(0);
            w.getSqlSegment();
            assertThat(w.getParamNameValuePairs()).containsValue(103L).containsValue(boundary);
            return w.getParamNameValuePairs().containsValue("purchaseEquipment")
                    ? records.reversed().stream().filter(r -> r.getId() > boundary).toList() : List.of();
        });
        when(calls.deleteAfterCheckpoint(eq(103L), anyLong())).thenAnswer(i -> { records.removeIf(r -> r.getId() > i.<Long>getArgument(1)); return 1; });
    }
    @AfterEach void close() throws Exception { mocks.close(); }
    KpEquipmentDTOs.PurchaseRequest items(String... names) {
        return new KpEquipmentDTOs.PurchaseRequest(Arrays.stream(names).map(n ->
                new KpEquipmentDTOs.PurchaseEntry("林恩", KpEquipmentDTOs.PurchaseType.ITEM, n)).toList());
    }
    void record(KpEquipmentDTOs.PurchaseResult result) {
        records.add(new GroupChatToolCall().setId((long) records.size()+1).setToolName("purchaseEquipment")
                .setToolResult(json.writeValueAsString(result)));
    }
    @Test void retryUndoesMultiplePurchasesInReverseAndStillAllowsRealDuplicates() {
        record(equipment.purchaseEquipment(7L, items("绷带", "绷带")));
        record(equipment.purchaseEquipment(7L, items("手电筒")));
        assertThat(profile.getEquipmentText()).isEqualTo("背包；绷带；绷带；手电筒");
        service.restore(turn, step);
        assertThat(profile.getEquipmentText()).isEqualTo("背包");
        assertThat(records).isEmpty();
        equipment.purchaseEquipment(7L, items("绷带", "绷带"));
        assertThat(profile.getEquipmentText()).isEqualTo("背包；绷带；绷带");
        verify(checkpoints, never()).upsert(any());
    }
    @Test void rollbackKeepsPurchasesBeforeExistingPausedBoundary() {
        record(equipment.purchaseEquipment(7L, items("绷带")));
        boundary = 1L;
        when(checkpoints.selectById(7L)).thenReturn(new GroupTurnCheckpoint().setTurnId(101L)
                .setReplyStepId(103L).setCheckpointType("PAUSED").setMessageId(0L).setToolCallId(1L));
        record(equipment.purchaseEquipment(7L, items("绷带")));
        service.restore(turn, step);
        assertThat(profile.getEquipmentText()).isEqualTo("背包；绷带");
        assertThat(records).singleElement().extracting(GroupChatToolCall::getId).isEqualTo(1L);
    }
    @Test void rollbackRestoresOriginallyNullInventory() {
        profile.setEquipmentText(null);
        record(equipment.purchaseEquipment(7L, items("绷带")));
        service.restore(turn, step);
        assertThat(profile.getEquipmentText()).isNull();
    }
    @Test void rollbackDeletesOnlyNewWeaponById() {
        Map<Long, CocCharacterWeapon> owned = new HashMap<>();
        owned.put(900L, new CocCharacterWeapon().setId(900L).setCharacterId(401L).setName("旧武器"));
        when(weapons.insert(any(CocCharacterWeapon.class))).thenAnswer(i -> {
            CocCharacterWeapon weapon = i.getArgument(0);
            weapon.setId(901L); owned.put(901L, weapon); return 1;
        });
        when(weapons.delete(any())).thenAnswer(i -> {
            var w = i.<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CocCharacterWeapon>>getArgument(0);
            assertThat(w.getSqlSegment()).contains("id =", "character_id =");
            assertThat(w.getParamNameValuePairs()).containsValue(901L).containsValue(401L);
            owned.remove(901L); return 1;
        });
        record(equipment.purchaseEquipment(7L, new KpEquipmentDTOs.PurchaseRequest(List.of(
                new KpEquipmentDTOs.PurchaseEntry("林恩", KpEquipmentDTOs.PurchaseType.WEAPON, ".38/9mm自动手枪")))));
        assertThat(owned).containsKeys(900L, 901L);
        service.restore(turn, step);
        assertThat(owned).containsOnlyKeys(900L);
    }
    @Test void legacyPurchaseWithoutUndoDataAbortsBeforeDeletingRecords() {
        records.add(new GroupChatToolCall().setId(1L).setToolName("purchaseEquipment")
                .setToolResult("{\"entries\":[{\"characterName\":\"林恩\",\"type\":\"ITEM\",\"name\":\"绷带\"}]}"));
        assertThatThrownBy(() -> service.restore(turn, step)).hasMessageContaining("缺少撤销数据");
        assertThat(records).hasSize(1);
        verify(messages, never()).deleteAfterCheckpoint(anyLong(), anyLong());
    }
    @Test void rollbackDoesNotOverwriteLaterInventoryEditOrDeleteItsUndoRecord() {
        record(equipment.purchaseEquipment(7L, items("绷带")));
        profile.setEquipmentText("背包；绷带；用户新物品");
        assertThatThrownBy(() -> service.restore(turn, step)).isInstanceOf(IllegalStateException.class);
        assertThat(profile.getEquipmentText()).isEqualTo("背包；绷带；用户新物品");
        assertThat(records).hasSize(1);
    }
}

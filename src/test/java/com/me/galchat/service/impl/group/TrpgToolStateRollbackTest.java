package com.me.galchat.service.impl.group;

import com.me.galchat.domain.po.*;
import com.me.galchat.domain.dto.*;
import com.me.galchat.mapper.*;
import com.me.galchat.service.impl.trpg.*;
import com.me.galchat.service.impl.character.*;
import com.me.galchat.support.MybatisPlusTestSupport;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class TrpgToolStateRollbackTest {
    TrpgSuspensionRollbackTest base;
    CocCharacterMapper cards = mock(CocCharacterMapper.class);
    CocCharacterWeaponMapper weapons = mock(CocCharacterWeaponMapper.class);
    TrpgWeaponStashMapper stashes = mock(TrpgWeaponStashMapper.class);
    GroupConversationMapper conversations = mock(GroupConversationMapper.class);
    Map<Long,CocCharacter> cardRows = new HashMap<>();
    Map<Long,CocCharacterWeapon> weaponRows = new HashMap<>();
    Map<Long,TrpgWeaponStash> stashRows = new HashMap<>();
    CocCharacter card;
    CharacterCardServiceImpl characterService;
    TrpgEquipmentService equipment;

    @BeforeEach void setup() {
        base = new TrpgSuspensionRollbackTest(); base.setup();
        MybatisPlusTestSupport.initialize(CocCharacter.class, CocCharacterWeapon.class, TrpgWeaponStash.class, GroupConversation.class);
        org.springframework.test.util.ReflectionTestUtils.setField(base.checkpointService,"toolStateRecoveryService",new TrpgToolStateRecoveryService(cards,weapons,stashes,base.json));
        org.springframework.test.util.ReflectionTestUtils.setField(base.checkpointService,"selectionRecoveryService",
                new TrpgSelectionRecoveryService(conversations,base.plans,base.items,base.steps,mock(TrpgSceneSelectionStore.class),base.json));
        card = new CocCharacter().setId(101L).setRunId(7L).setName("林恩"); cardRows.put(101L,card);
        when(cards.selectList(any())).thenAnswer(i->new ArrayList<>(cardRows.values()));
        when(cards.selectById(anyLong())).thenAnswer(i->cardRows.get(i.getArgument(0)));
        when(cards.updateById(any(CocCharacter.class))).thenReturn(1);
        when(cards.update(any(),any())).thenAnswer(i->TrpgSuspensionRollbackTest.applyUpdate(cardRows,i.getArgument(1)));
        when(weapons.selectById(anyLong())).thenAnswer(i->weaponRows.get(i.getArgument(0)));
        when(weapons.selectByCharacterIdAndNameForUpdate(anyLong(),anyString())).thenAnswer(i->weaponRows.values().stream()
                .filter(w->Objects.equals(w.getCharacterId(),i.getArgument(0)) && Objects.equals(w.getName(),i.getArgument(1))).toList());
        when(weapons.updateById(any(CocCharacterWeapon.class))).thenReturn(1);
        when(weapons.update(isNull(),any())).thenAnswer(i->TrpgSuspensionRollbackTest.applyUpdate(weaponRows,i.getArgument(1)));
        when(weapons.deleteById(anyLong())).thenAnswer(i->weaponRows.remove(i.getArgument(0))==null?0:1);
        when(weapons.insert(any(CocCharacterWeapon.class))).thenAnswer(i->{var w=i.<CocCharacterWeapon>getArgument(0);weaponRows.put(w.getId(),w);return 1;});
        when(stashes.selectById(anyLong())).thenAnswer(i->stashRows.get(i.getArgument(0)));
        when(stashes.selectByRunIdAndWeaponIdForUpdate(eq(7L),anyLong())).thenAnswer(i->stashRows.get(i.getArgument(1)));
        when(stashes.insert(any(TrpgWeaponStash.class))).thenAnswer(i->{var w=i.<TrpgWeaponStash>getArgument(0);stashRows.put(w.getWeaponId(),w);return 1;});
        when(stashes.deleteById(anyLong())).thenAnswer(i->stashRows.remove(i.getArgument(0))==null?0:1);
        characterService = new CharacterCardServiceImpl(cards,mock(CocCharacterSkillMapper.class),weapons,
                mock(CocCharacterProfileMapper.class),mock(CocSkillDefMapper.class),new CharacterSkillResolver(),
                mock(CharacterTemplateMapper.class),mock(UserInfoMapper.class),conversations,mock(com.me.galchat.service.impl.character.ImportedWeaponAuditQueue.class));
        var conversation = new GroupConversation().setId(7L).setMode(com.me.galchat.constant.GroupChatConstant.MODE_TRPG);
        when(conversations.selectById(7L)).thenReturn(conversation);
        var participants = mock(TrpgSceneParticipantService.class);
        when(participants.state(conversation)).thenReturn(new TrpgSceneParticipantService.SceneState(31L,"医院",List.of("林恩"),List.of(101L),List.of()));
        equipment = new TrpgEquipmentService(cards,mock(CocCharacterProfileMapper.class),weapons,stashes,conversations,participants);
    }
    @AfterEach void close() throws Exception { base.mocks.close(); }
    CocCharacterWeapon weapon() {
        var w=new CocCharacterWeapon().setId(81L).setCharacterId(101L).setName("左轮手枪").setAmmoCapacity(6).setRemainingAmmo(1).setIsBroken(true).setNotes("刻字");
        weaponRows.put(81L,w);return w;
    }
    void retry(String name,Object result) { base.record(name,result);base.checkpointService.restore(base.turn,base.step); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,retry", "true,retry", "false,pause", "true,pause", "false,failedRecord", "true,failedRecord"})
    void retryRestoresPublishedOptionsTimeAndAutomaticScene(boolean singleLocation, String mode) {
        var conversation = base.conversations.requireActive(7L).setActiveReplyPlanId(null)
                .setModuleId(8L).setMode(com.me.galchat.constant.GroupChatConstant.MODE_TRPG);
        when(conversations.selectById(7L)).thenReturn(conversation);
        when(conversations.updateById(any(GroupConversation.class))).thenReturn(1);
        when(conversations.update(isNull(),any())).thenAnswer(i->TrpgSuspensionRollbackTest.applyUpdate(Map.of(7L,conversation),i.getArgument(1)));
        var locations = mock(CocModuleLocationMapper.class);
        var location = new CocModuleLocation().setId(21L).setModuleId(8L).setName("医院");
        when(locations.selectList(any())).thenReturn(singleLocation ? List.of(location) : List.of(location,
                new CocModuleLocation().setId(22L).setModuleId(8L).setName("旅店")));
        var redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        var hashes = mock(org.springframework.data.redis.core.HashOperations.class);
        var values = mock(org.springframework.data.redis.core.ValueOperations.class);
        Map<String,Map<Object,Object>> hashRows = new HashMap<>(); Map<String,String> valueRows = new HashMap<>();
        when(redis.opsForHash()).thenReturn(hashes);when(redis.opsForValue()).thenReturn(values);
        when(hashes.entries(anyString())).thenAnswer(i->new HashMap<>(hashRows.getOrDefault(i.getArgument(0),Map.of())));
        doAnswer(i->{hashRows.computeIfAbsent(i.getArgument(0),k->new HashMap<>()).put(i.getArgument(1),i.getArgument(2));return null;}).when(hashes).put(anyString(),any(),any());
        doAnswer(i->{valueRows.put(i.getArgument(0),i.getArgument(1));return null;}).when(values).set(anyString(),anyString());
        when(values.get(anyString())).thenAnswer(i->valueRows.get(i.getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(i->{hashRows.remove(i.getArgument(0));valueRows.remove(i.getArgument(0));return true;});
        when(redis.delete(anyCollection())).thenAnswer(i->{for(Object key:i.<Collection<?>>getArgument(0)){hashRows.remove(key);valueRows.remove(key);}return 1L;});
        var store = new TrpgSceneSelectionStore(redis);
        var selection = new TrpgSceneSelectionService(base.conversations,locations,conversations,base.plans,base.items,store,base.participants,mock(TrpgSelectionRandomizer.class), base.steps);
        org.springframework.test.util.ReflectionTestUtils.setField(base.checkpointService,"selectionRecoveryService",
                new TrpgSelectionRecoveryService(conversations,base.plans,base.items,base.steps,store,base.json));
        base.scenePlans.clear();
        var pending = new GroupChatReplyStep().setId(55L).setTurnId(61L).setStatus("pending");
        when(base.steps.selectList(any())).thenReturn(List.of(pending));
        when(base.steps.selectById(55L)).thenReturn(pending);
        when(base.steps.update(isNull(),any())).thenAnswer(i->TrpgSuspensionRollbackTest.applyUpdate(Map.of(55L,pending,51L,base.step),i.getArgument(1)));
        org.springframework.test.util.ReflectionTestUtils.setField(selection,"stepMapper",base.steps);
        if ("failedRecord".equals(mode)) {
            var original = new GroupConversation();org.springframework.beans.BeanUtils.copyProperties(conversation,original);
            var tx = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object t, org.springframework.transaction.TransactionDefinition d) {}
                @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {}
                @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {
                    org.springframework.beans.BeanUtils.copyProperties(original,conversation);base.scenePlans.clear();base.sceneItems.clear();
                }
            });
            assertThatThrownBy(()->tx.executeWithoutResult(status->{
                selection.publishOptions(7L,61L,51L,singleLocation?List.of("医院"):List.of("医院","旅店"),1,"MORNING");
                throw new IllegalStateException("record failed");
            })).hasMessage("record failed");
            assertThat(store.getOptions(7L,61L)).isEmpty();assertThat(store.getSelections(7L,61L)).isEmpty();
            assertThat(store.currentTurnId(7L)).isNull();assertThat(base.scenePlans).isEmpty();
            assertThat(conversation.getGameDayNo()).isNull();return;
        }
        var result = selection.publishOptions(7L,61L,51L,singleLocation?List.of("医院"):List.of("医院","旅店"),1,"MORNING");
        if ("pause".equals(mode)) {
            base.record("publishExplorationScenes",result);base.checkpoint.setCheckpointType("PAUSED").setToolCallId(1L);
            base.checkpointService.restore(base.turn,base.step);
            assertThat(conversation.getGameDayNo()).isEqualTo(1);
            if (singleLocation) assertThat(base.scenePlans).hasSize(1);
            else assertThat(store.getOptions(7L,61L)).hasSize(2);
            assertThat(base.records).hasSize(1);return;
        }
        if (singleLocation) pending.setStatus("cancelled").setErrorMessage("单地点已自动分配");
        // PostgreSQL stores microseconds; in-memory tool results can still contain nanoseconds.
        conversation.setGameTimeUpdatedAt(conversation.getGameTimeUpdatedAt().withNano(0));
        conversation.setUpdatedAt(conversation.getUpdatedAt().withNano(0));
        retry("publishExplorationScenes",result);
        assertThat(pending.getStatus()).isEqualTo("pending");
        assertThat(pending.getErrorMessage()).isNull();
        assertThat(conversation.getGameDayNo()).isNull();
        assertThat(conversation.getGameTimePeriod()).isNull();
        assertThat(conversation.getActiveReplyPlanId()).isNull();
        assertThat(base.scenePlans).isEmpty();
        assertThat(store.getOptions(7L,61L)).isEmpty();
        assertThat(store.getSelections(7L,61L)).isEmpty();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"updateQuickNotes","updateWeaponState","stashWeapon","equipWeaponFromStash","updateCombatStates","publishExplorationScenes"})
    void legacySuccessfulResultWithoutUndoCannotBeSilentlyDiscarded(String tool) {
        base.record(tool,"updateQuickNotes".equals(tool)?"快速笔记已更新。":Map.of("message","success"));
        assertThatThrownBy(()->base.checkpointService.restore(base.turn,base.step)).hasMessageContaining("缺少撤销数据");
        assertThat(base.records).hasSize(1);
    }

    @Test void retryRestoresNullQuickNotes() {
        var tools = new com.me.galchat.tool.KpModuleTools(mock(TrpgModuleQueryService.class),mock(TrpgMaterialService.class),characterService);
        var context = new org.springframework.ai.chat.model.ToolContext(Map.of(
                com.me.galchat.constant.ChatToolContextConstant.ACTOR_TYPE_KEY,com.me.galchat.constant.GroupChatConstant.ACTOR_KP,
                com.me.galchat.constant.ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY,7L,
                com.me.galchat.constant.ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY,51L));
        retry("updateQuickNotes",tools.updateQuickNotes("林恩","新笔记",context));
        assertThat(card.getQuickNotes()).isNull();
    }
    @Test void retryRestoresAmmoAndDamageBeforeWeaponUpdate() {
        weapon(); retry("updateWeaponState",characterService.updateWeaponState(7L,"林恩","左轮手枪",new KpWeaponStateDTOs.Update(3,false)));
        assertThat(weaponRows.get(81L).getRemainingAmmo()).isEqualTo(1);
        assertThat(weaponRows.get(81L).getIsBroken()).isTrue();
    }
    @Test void retryReturnsStashedWeaponToOriginalOwner() {
        weapon();retry("stashWeapon",equipment.stashWeapon(7L,"林恩","左轮手枪",KpEquipmentDTOs.StashReason.DISARMED));
        assertThat(stashRows).isEmpty();assertThat(weaponRows.get(81L)).isNotNull();
        assertThat(weaponRows.get(81L).getCharacterId()).isEqualTo(101L);
        assertThat(weaponRows.get(81L).getNotes()).isEqualTo("刻字");
    }
    @Test void stashRollbackAcceptsDatabaseTimestampPrecision() {
        weapon();
        var result=equipment.stashWeapon(7L,"林恩","左轮手枪",KpEquipmentDTOs.StashReason.DISARMED);
        base.record("stashWeapon",result);
        var row=stashRows.get(81L);row.setStashedAt(row.getStashedAt().withNano(0));
        base.checkpointService.restore(base.turn,base.step);
        assertThat(weaponRows).containsKey(81L);assertThat(stashRows).isEmpty();
    }

    @Test void rollbackReversesWeaponUpdateStashEquipChain() {
        weapon();
        base.record("updateWeaponState",characterService.updateWeaponState(7L,"林恩","左轮手枪",new KpWeaponStateDTOs.Update(3,false)));
        base.record("stashWeapon",equipment.stashWeapon(7L,"林恩","左轮手枪",KpEquipmentDTOs.StashReason.DISARMED));
        base.record("equipWeaponFromStash",equipment.equipWeaponFromStash(7L,81L,"林恩"));
        base.checkpointService.restore(base.turn,base.step);
        assertThat(stashRows).isEmpty();assertThat(weaponRows.get(81L).getRemainingAmmo()).isEqualTo(1);
        assertThat(weaponRows.get(81L).getIsBroken()).isTrue();
    }

    @Test void pauseKeepsCommittedWeaponState() {
        weapon();base.record("updateWeaponState",characterService.updateWeaponState(7L,"林恩","左轮手枪",new KpWeaponStateDTOs.Update(3,false)));
        base.checkpoint.setCheckpointType("PAUSED").setToolCallId(1L);
        base.checkpointService.restore(base.turn,base.step);
        assertThat(weaponRows.get(81L).getRemainingAmmo()).isEqualTo(3);assertThat(base.records).hasSize(1);
    }

    @Test void refusesRollbackWhenWeaponWasChangedAfterRecordedResult() {
        weapon();base.record("updateWeaponState",characterService.updateWeaponState(7L,"林恩","左轮手枪",new KpWeaponStateDTOs.Update(3,false)));
        weaponRows.get(81L).setRemainingAmmo(4);
        assertThatThrownBy(()->base.checkpointService.restore(base.turn,base.step)).hasMessageContaining("状态已变化");
        assertThat(base.records).hasSize(1);assertThat(weaponRows.get(81L).getRemainingAmmo()).isEqualTo(4);
    }
    @Test void retryPutsEquippedWeaponBackIntoOriginalStash() {
        weapon();equipment.stashWeapon(7L,"林恩","左轮手枪",KpEquipmentDTOs.StashReason.SEIZED);
        var before=stashRows.get(81L);
        retry("equipWeaponFromStash",equipment.equipWeaponFromStash(7L,81L,"林恩"));
        assertThat(weaponRows).isEmpty();assertThat(stashRows.get(81L)).isEqualTo(before);
    }
    @Test void retryRestoresCombatConditionsIncludingNullRestraint() {
        var combat=mock(TrpgCombatLifecycleService.class);
        var conversation=base.conversations.requireActive(7L);
        var participants=base.json.createArrayNode().addObject().put("characterId",101L);
        cardRows.put(102L,new CocCharacter().setId(102L).setRunId(7L).setName("食尸鬼"));
        var snapshot=base.json.createArrayNode().add(participants);
        snapshot.addObject().put("characterId",102L);
        when(combat.requireActiveCombat(conversation)).thenReturn(new TrpgCombat().setParticipants(snapshot));
        var service=new TrpgCombatStateService(base.conversations,combat,cards);
        retry("updateCombatStates",service.updateCombatStates(7L,new KpCombatStateDTOs.Update(List.of(new KpCombatStateDTOs.Change("林恩",true,true,"食尸鬼")))));
        assertThat(card.getInCover()).isFalse();assertThat(card.getCoverActionForfeitPending()).isFalse();
        assertThat(card.getRestrainedByCharacterId()).isNull();
    }
}

package com.me.galchat.domain.dto;

import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.po.TrpgCombat;
import com.me.galchat.domain.po.TrpgWeaponStash;
import com.me.galchat.service.impl.TrpgSaveServiceImpl;
import com.me.galchat.typehandler.JsonbTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgSaveSnapshotJsonTest {

    @Test
    void saveSnapshotExposesPersistentWeaponStashRows() {
        assertThat(Arrays.stream(TrpgSaveSnapshotDTO.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .contains("getWeaponStash", "setWeaponStash");
    }

    @Test
    void jsonbRoundTripKeepsTypedRowsAndPrivateQuickNotesBackup()
            throws Exception {
        var quickNpcSpecs = JsonMapper.builder().build().createArrayNode();
        quickNpcSpecs.addObject()
                .put("name", "仓库守卫")
                .put("strength", "MEDIUM")
                .put("weapon", "PISTOL");
        TrpgSaveSnapshotDTO snapshot = new TrpgSaveSnapshotDTO()
                .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                .setConversationId(51L)
                .setCursors(new TrpgSaveSnapshotDTO.CursorSnapshot()
                        .setMaxMessageId(8L))
                .setReplyPlans(List.of(new GroupReplyPlan()
                        .setId(101L)
                        .setConversationId(51L)
                        .setExecutionKey("scene:101")
                        .setDisplayName("林间营地")))
                .setReplyPlanItems(List.of(new GroupReplyPlanItem()
                        .setId(201L)
                        .setPlanId(101L)
                        .setSubjectCharacterId(401L)
                        .setSubjectCharacterName("林默")))
                .setCharacters(List.of(new CocCharacter()
                        .setId(401L)
                        .setRunId(51L)
                        .setQuickNotes("藏着钥匙")))
                .setCharacterQuickNotes(Map.of(401L, "藏着钥匙"))
                .setWeaponStash(List.of(new TrpgWeaponStash()
                        .setWeaponId(901L)
                        .setRunId(51L)
                        .setSourceCharacterName("林默")
                        .setLocationName("森林 - 营地")
                        .setStashReason("DISCARDED")
                        .setWeaponSnapshot(
                                new TrpgWeaponStash.WeaponSnapshot()
                                        .setName("弓箭")
                                        .setRemainingAmmo(1)
                                        .setIsBroken(false))))
                .setCombats(List.of(new TrpgCombat()
                        .setId(501L)
                        .setConversationId(51L)
                        .setQuickNpcSpecs(quickNpcSpecs)));
        JsonbTypeHandler handler = new JsonbTypeHandler(
                TrpgSaveSnapshotDTO.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ArgumentCaptor<Object> jsonCaptor = ArgumentCaptor.forClass(Object.class);

        handler.setNonNullParameter(statement, 1, snapshot, JdbcType.OTHER);
        verify(statement).setObject(
                org.mockito.ArgumentMatchers.eq(1),
                jsonCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(Types.OTHER));
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("snapshot"))
                .thenReturn(jsonCaptor.getValue().toString());

        TrpgSaveSnapshotDTO restored = (TrpgSaveSnapshotDTO)
                handler.getNullableResult(resultSet, "snapshot");

        assertThat(restored.getCursors().getMaxMessageId()).isEqualTo(8L);
        assertThat(restored.getReplyPlans()).singleElement()
                .extracting(GroupReplyPlan::getId,
                        GroupReplyPlan::getExecutionKey,
                        GroupReplyPlan::getDisplayName)
                .containsExactly(101L, "scene:101", "林间营地");
        assertThat(restored.getCharacters()).singleElement()
                .extracting(CocCharacter::getId).isEqualTo(401L);
        assertThat(restored.getReplyPlanItems()).singleElement()
                .extracting(
                        GroupReplyPlanItem::getSubjectCharacterId,
                        GroupReplyPlanItem::getSubjectCharacterName)
                .containsExactly(401L, "林默");
        assertThat(restored.getCharacterQuickNotes())
                .containsEntry(401L, "藏着钥匙");
        assertThat(restored.getWeaponStash()).singleElement()
                .satisfies(stash -> {
                    assertThat(stash.getWeaponId()).isEqualTo(901L);
                    assertThat(stash.getLocationName())
                            .isEqualTo("森林 - 营地");
                    assertThat(stash.getWeaponSnapshot().getName())
                            .isEqualTo("弓箭");
                });
        assertThat(restored.getCombats()).singleElement()
                .extracting(combat -> combat.getQuickNpcSpecs()
                        .get(0).get("name").asText())
                .isEqualTo("仓库守卫");
    }
}

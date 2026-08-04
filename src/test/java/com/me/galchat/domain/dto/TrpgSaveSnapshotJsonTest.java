package com.me.galchat.domain.dto;

import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.typehandler.JsonbTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgSaveSnapshotJsonTest {

    @Test
    void jsonbRoundTripKeepsTypedRowsAndPrivateQuickNotesBackup()
            throws Exception {
        TrpgSaveSnapshotDTO snapshot = new TrpgSaveSnapshotDTO()
                .setFormatVersion(1)
                .setConversationId(51L)
                .setCursors(new TrpgSaveSnapshotDTO.CursorSnapshot()
                        .setMaxMessageId(8L))
                .setReplyPlans(List.of(new GroupReplyPlan()
                        .setId(101L)
                        .setConversationId(51L)))
                .setCharacters(List.of(new CocCharacter()
                        .setId(401L)
                        .setRunId(51L)
                        .setQuickNotes("藏着钥匙")))
                .setCharacterQuickNotes(Map.of(401L, "藏着钥匙"));
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
                .extracting(GroupReplyPlan::getId).isEqualTo(101L);
        assertThat(restored.getCharacters()).singleElement()
                .extracting(CocCharacter::getId).isEqualTo(401L);
        assertThat(restored.getCharacterQuickNotes())
                .containsEntry(401L, "藏着钥匙");
    }
}

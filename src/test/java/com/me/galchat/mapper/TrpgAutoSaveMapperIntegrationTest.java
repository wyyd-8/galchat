package com.me.galchat.mapper;

import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;
import com.me.galchat.domain.po.TrpgAutoSave;
import com.me.galchat.service.impl.trpg.TrpgSaveServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TrpgAutoSaveMapperIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TrpgAutoSaveMapper autoSaveMapper;

    @Test
    void upsertReplacesOnlyTheSameCheckpointTypeAndDeserializesJsonb() {
        jdbcTemplate.execute("""
                CREATE TEMP TABLE trpg_auto_save (
                    conversation_id BIGINT NOT NULL,
                    checkpoint_type VARCHAR(16) NOT NULL,
                    saved_at TIMESTAMP NOT NULL,
                    format_version INT NOT NULL,
                    snapshot JSONB NOT NULL,
                    PRIMARY KEY (conversation_id, checkpoint_type)
                ) ON COMMIT DROP
                """);
        TrpgAutoSave firstTurn = autoSave(-9003L, "TURN", -9010L);
        TrpgAutoSave replacementTurn = autoSave(-9003L, "TURN", -9020L);
        TrpgAutoSave scene = autoSave(-9003L, "SCENE", -9030L);

        autoSaveMapper.upsert(firstTurn);
        autoSaveMapper.upsert(replacementTurn);
        autoSaveMapper.upsert(scene);

        TrpgAutoSave selected = autoSaveMapper.selectByConversationAndType(
                -9003L, "TURN");
        List<TrpgAutoSave> checkpoints =
                autoSaveMapper.selectByConversationId(-9003L);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trpg_auto_save WHERE conversation_id = ?",
                Integer.class, -9003L);
        assertThat(count).isEqualTo(2);
        assertThat(checkpoints)
                .extracting(TrpgAutoSave::getCheckpointType)
                .containsExactlyInAnyOrder("TURN", "SCENE");
        assertThat(selected.getSnapshot()).isNotNull();
        assertThat(selected.getSnapshot().getConversationId())
                .isEqualTo(-9003L);
        assertThat(selected.getSnapshot().getUserWorldId())
                .isEqualTo(-9020L);
    }

    @Test
    void deleteAfterKeepsCheckpointsAtTheExactBoundary() {
        jdbcTemplate.execute("""
                CREATE TEMP TABLE trpg_auto_save (
                    conversation_id BIGINT NOT NULL,
                    checkpoint_type VARCHAR(16) NOT NULL,
                    saved_at TIMESTAMP NOT NULL,
                    format_version INT NOT NULL,
                    snapshot JSONB NOT NULL,
                    PRIMARY KEY (conversation_id, checkpoint_type)
                ) ON COMMIT DROP
                """);
        LocalDateTime boundary = LocalDateTime.of(2026, 8, 20, 12, 0);
        autoSaveMapper.upsert(autoSave(
                -9004L, "INITIAL", -9010L).setSavedAt(boundary.minusHours(1)));
        autoSaveMapper.upsert(autoSave(
                -9004L, "SCENE", -9020L).setSavedAt(boundary));
        autoSaveMapper.upsert(autoSave(
                -9004L, "TURN", -9030L).setSavedAt(boundary.plusMinutes(1)));

        autoSaveMapper.deleteAfter(-9004L, boundary);

        assertThat(autoSaveMapper.selectByConversationId(-9004L))
                .extracting(TrpgAutoSave::getCheckpointType)
                .containsExactlyInAnyOrder("INITIAL", "SCENE");
    }

    private TrpgAutoSave autoSave(
            Long conversationId, String checkpointType, Long userWorldId) {
        return new TrpgAutoSave()
                .setConversationId(conversationId)
                .setCheckpointType(checkpointType)
                .setSavedAt(LocalDateTime.now())
                .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                .setSnapshot(new TrpgSaveSnapshotDTO()
                        .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                        .setConversationId(conversationId)
                        .setUserWorldId(userWorldId));
    }
}

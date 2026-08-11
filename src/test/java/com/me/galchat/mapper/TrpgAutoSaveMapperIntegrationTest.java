package com.me.galchat.mapper;

import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;
import com.me.galchat.domain.po.TrpgAutoSave;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TrpgAutoSaveMapperIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TrpgAutoSaveMapper autoSaveMapper;

    @Test
    void upsertReplacesTheConversationCheckpointAndDeserializesJsonb() {
        jdbcTemplate.execute("""
                CREATE TEMP TABLE trpg_auto_save (
                    conversation_id BIGINT PRIMARY KEY,
                    saved_at TIMESTAMP NOT NULL,
                    format_version INT NOT NULL,
                    snapshot JSONB NOT NULL
                ) ON COMMIT DROP
                """);
        TrpgAutoSave first = autoSave(-9003L, -9010L);
        TrpgAutoSave replacement = autoSave(-9003L, -9020L);

        autoSaveMapper.upsert(first);
        autoSaveMapper.upsert(replacement);

        TrpgAutoSave selected = autoSaveMapper.selectById(-9003L);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trpg_auto_save WHERE conversation_id = ?",
                Integer.class, -9003L);
        assertThat(count).isEqualTo(1);
        assertThat(selected.getSnapshot()).isNotNull();
        assertThat(selected.getSnapshot().getConversationId())
                .isEqualTo(-9003L);
        assertThat(selected.getSnapshot().getUserWorldId())
                .isEqualTo(-9020L);
    }

    private TrpgAutoSave autoSave(Long conversationId, Long userWorldId) {
        return new TrpgAutoSave()
                .setConversationId(conversationId)
                .setSavedAt(LocalDateTime.now())
                .setFormatVersion(1)
                .setSnapshot(new TrpgSaveSnapshotDTO()
                        .setFormatVersion(1)
                        .setConversationId(conversationId)
                        .setUserWorldId(userWorldId));
    }
}

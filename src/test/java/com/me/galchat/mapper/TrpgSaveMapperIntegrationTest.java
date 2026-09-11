package com.me.galchat.mapper;

import com.me.galchat.domain.po.TrpgSave;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TrpgSaveMapperIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TrpgSaveMapper saveMapper;

    @Test
    void selectByConversationIdDeserializesSnapshotJsonb() {
        jdbcTemplate.execute("""
                CREATE TEMP TABLE trpg_save (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    conversation_id BIGINT NOT NULL,
                    remark VARCHAR(200),
                    saved_at TIMESTAMP NOT NULL,
                    format_version INT NOT NULL,
                    snapshot JSONB NOT NULL
                ) ON COMMIT DROP
                """);
        jdbcTemplate.update("""
                INSERT INTO trpg_save (
                    id, user_id, conversation_id, remark,
                    saved_at, format_version, snapshot
                ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CAST(? AS JSONB))
                """,
                -9001L, -9002L, -9003L, "mapper regression", 1,
                """
                        {
                          "formatVersion": 1,
                          "conversationId": -9003,
                          "userWorldId": -9004,
                          "worldId": -9005,
                          "moduleId": -9006
                        }
                        """);

        TrpgSave save = saveMapper.selectByConversationId(-9003L);

        assertThat(save).isNotNull();
        assertThat(save.getSnapshot()).isNotNull();
        assertThat(save.getSnapshot().getFormatVersion()).isEqualTo(1);
        assertThat(save.getSnapshot().getConversationId()).isEqualTo(-9003L);
    }
}

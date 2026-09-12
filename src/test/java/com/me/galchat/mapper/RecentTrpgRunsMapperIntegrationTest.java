package com.me.galchat.mapper;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "galchat.test.jdbc-url", matches = ".+")
class RecentTrpgRunsMapperIntegrationTest {
    private SqlSession session;
    private GroupConversationMapper mapper;

    @BeforeEach
    void setup() throws Exception {
        var dataSource = new UnpooledDataSource("org.postgresql.Driver",
                System.getProperty("galchat.test.jdbc-url"),
                System.getProperty("galchat.test.jdbc-user", System.getProperty("user.name")),
                System.getProperty("galchat.test.jdbc-password", ""));
        var configuration = new Configuration(new Environment("recent-runs", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(GroupConversationMapper.class);
        session = new SqlSessionFactoryBuilder().build(configuration).openSession();
        mapper = session.getMapper(GroupConversationMapper.class);
        sql("CREATE TEMP TABLE group_conversation (id BIGINT, user_world_id BIGINT, module_id BIGINT, mode TEXT, status TEXT, updated_at TIMESTAMP)");
        sql("CREATE TEMP TABLE group_chat_member (conversation_id BIGINT, actor_type TEXT, actor_id BIGINT)");
        sql("CREATE TEMP TABLE group_chat_message (conversation_id BIGINT, speaker_type TEXT, speaker_id BIGINT, visibility TEXT, status TEXT, message_kind TEXT, created_at TIMESTAMP)");
        sql("""
                INSERT INTO group_conversation VALUES
                (1,5,1,'trpg','active','2026-09-01'), (2,5,1,'trpg','closed','2026-09-05'),
                (3,5,1,'trpg','active','2026-09-03'), (4,5,1,'trpg','active','2026-09-03'),
                (5,6,1,'trpg','active','2026-09-12'), (6,5,1,'chat','active','2026-09-12'),
                (7,5,1,'trpg','active','2026-09-12'), (8,5,1,'trpg','active','2026-09-05')
                """);
        sql("INSERT INTO group_chat_member SELECT id,'character',9 FROM group_conversation WHERE id != 7");
        sql("INSERT INTO group_chat_member VALUES (4,'character',9),(7,'kp',9)");
        sql("""
                INSERT INTO group_chat_message VALUES
                (1,'character',9,'public','completed','dialogue','2026-09-01'),
                (2,'character',9,'public','completed','dialogue','2026-09-02'),
                (3,'character',9,'public','completed','dialogue','2026-09-03'),
                (4,'character',9,'public','completed','dialogue','2026-09-03'),
                (5,'character',9,'public','completed','dialogue','2026-09-12'),
                (6,'character',9,'public','completed','dialogue','2026-09-12'),
                (7,'character',9,'public','completed','dialogue','2026-09-12'),
                (8,'character',10,'public','completed','dialogue','2026-09-12'),
                (1,'character',10,'public','completed','dialogue','2026-09-12'),
                (1,'character',9,'private','completed','dialogue','2026-09-12'),
                (1,'character',9,'public','withdrawn','dialogue','2026-09-12'),
                (1,'character',9,'public','failed','dialogue','2026-09-12'),
                (1,'character',9,'public','completed','system_event','2026-09-12')
                """);
    }

    @AfterEach
    void close() { if (session != null) session.close(); }

    @Test
    void takesThreeByRunUpdateEvenWithoutOwnMessagesAndKeepsWorldIsolation() {
        var runs = mapper.selectRecentTrpgByCharacter(5L, 9L);
        assertThat(runs).extracting(run -> run.getId()).containsExactly(8L, 2L, 4L);
        assertThat(runs).extracting(run -> run.getModuleId()).containsOnly(1L);
        assertThat(runs.get(1).getStatus()).isEqualTo("closed");
        assertThat(mapper.selectRecentTrpgByCharacter(5L, 10L)).isEmpty();
        assertThat(mapper.selectRecentTrpgByCharacter(6L, 9L))
                .extracting(run -> run.getId()).containsExactly(5L);
    }

    @Test
    void runUpdatesAndDeletionImmediatelyChangeRecentIndex() throws Exception {
        sql("UPDATE group_conversation SET updated_at='2026-09-13' WHERE id=3");
        assertThat(mapper.selectRecentTrpgByCharacter(5L, 9L))
                .extracting(run -> run.getId()).containsExactly(3L, 8L, 2L);
        sql("DELETE FROM group_conversation WHERE id=3");
        assertThat(mapper.selectRecentTrpgByCharacter(5L, 9L))
                .extracting(run -> run.getId()).containsExactly(8L, 2L, 4L);
    }

    private void sql(String text) throws Exception {
        try (var statement = session.getConnection().createStatement()) { statement.execute(text); }
        session.clearCache();
    }
}

package com.me.galchat.mapper;

import com.me.galchat.domain.vo.TrpgParticipantHistoryVO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

/** Runs only against an explicitly selected PostgreSQL database; all fixtures are connection-local. */
@EnabledIfSystemProperty(named = "galchat.test.jdbc-url", matches = ".+")
class TrpgParticipantHistoryMapperIntegrationTest {
    private SqlSession session;
    private TrpgParticipantHistoryMapper mapper;

    @BeforeEach
    void setup() throws Exception {
        var dataSource = new UnpooledDataSource("org.postgresql.Driver", System.getProperty("galchat.test.jdbc-url"),
                System.getProperty("galchat.test.jdbc-user", System.getProperty("user.name")),
                System.getProperty("galchat.test.jdbc-password", ""));
        var configuration = new Configuration(new Environment("history-test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        try (var xml = getClass().getResourceAsStream("/mapper/TrpgParticipantHistoryMapper.xml")) {
            new XMLMapperBuilder(xml, configuration, "mapper/TrpgParticipantHistoryMapper.xml", configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession();
        mapper = session.getMapper(TrpgParticipantHistoryMapper.class);
        sql("CREATE TEMP TABLE user_character_info (user_world_id BIGINT, character_id BIGINT)");
        sql("CREATE TEMP TABLE group_conversation (id BIGINT PRIMARY KEY, user_world_id BIGINT, mode TEXT, status TEXT, title TEXT, module_id BIGINT, closed_at TIMESTAMP)");
        sql("CREATE TEMP TABLE group_chat_member (conversation_id BIGINT, actor_type TEXT, actor_id BIGINT, enabled BOOLEAN)");
        sql("CREATE TEMP TABLE group_chat_message (conversation_id BIGINT, visibility TEXT, status TEXT, message_kind TEXT, created_at TIMESTAMP)");
        sql("CREATE TEMP TABLE trpg_completion (conversation_id BIGINT PRIMARY KEY, data JSONB)");
        sql("CREATE TEMP TABLE coc_module (id BIGINT PRIMARY KEY, name TEXT)");
        sql("INSERT INTO user_character_info VALUES (1,11),(1,12),(1,13),(2,11)");
        sql("INSERT INTO coc_module VALUES (10,'深夜图书馆')");
        sql("""
                INSERT INTO group_conversation VALUES
                (101,1,'trpg','closed','已完成 A',10,'2026-09-01 12:00'),
                (102,1,'trpg','closed','已完成 B',10,'2026-09-01 12:00'),
                (103,1,'trpg','closed','手动关闭',10,'2026-09-02 12:00'),
                (104,1,'trpg','active','总结尚未生成',10,NULL),
                (105,1,'trpg','active','最近同行',99,NULL),
                (106,1,'trpg','active','尚未开始',10,NULL),
                (107,1,'chat','closed','普通群聊',10,'2026-09-08 12:00'),
                (108,2,'trpg','closed','其他世界',10,'2026-09-08 12:00')
                """);
        sql("INSERT INTO group_chat_member SELECT id,'character',11,FALSE FROM group_conversation");
        sql("INSERT INTO group_chat_member VALUES (101,'character',12,TRUE),(101,'kp',13,TRUE)");
        sql("INSERT INTO trpg_completion VALUES (101,'{}'),(102,'{}'),(104,NULL),(107,'{}'),(108,'{}')");
        sql("INSERT INTO group_chat_message SELECT id,'public','completed','dialogue','2026-09-01 10:00' FROM group_conversation WHERE id <> 106");
        sql("""
                INSERT INTO group_chat_message VALUES
                (101,'public','completed','dialogue','2026-09-01 11:00'),
                (105,'public','completed','dialogue','2026-09-07 12:00'),
                (101,'private','completed','dialogue','2026-09-09 12:00'),
                (102,'public','withdrawn','dialogue','2026-09-09 12:00'),
                (103,'public','failed','dialogue','2026-09-09 12:00'),
                (106,'public','completed','system_event','2026-09-09 12:00')
                """);
    }

    @AfterEach
    void close() { if (session != null) session.close(); }

    @Test
    void summariesCountRunsOnceAndExcludeOtherWorldsChatAndUnfinishedCompletions() {
        var summaries = mapper.selectSummaries(1L);
        assertThat(summaries).extracting(TrpgParticipantHistoryVO::getCharacterId).containsExactly(11L,12L,13L);
        assertThat(summaries).extracting(TrpgParticipantHistoryVO::getCompletedRunCount).containsExactly(2L,1L,0L);
        var latest = summaries.getFirst().getLatestRun();
        assertThat(latest.getConversationId()).isEqualTo(105L);
        assertThat(latest.getStatus()).isEqualTo("active");
        assertThat(latest.getModuleName()).isNull();
        assertThat(latest.getLastPlayedAt()).isEqualTo(LocalDateTime.of(2026,9,7,12,0));
        assertThat(summaries.get(1).getLatestRun().getStatus()).isEqualTo("completed");
        assertThat(summaries.get(2).getLatestRun()).isNull();
        assertThat(mapper.selectSummaries(99L)).isEmpty();
    }

    @Test
    void keysetPaginationBreaksTimestampTiesAndPreservesRepeatedModules() {
        var first = mapper.selectCompletedRuns(1L,11L,null,null,1);
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().getConversationId()).isEqualTo(102L);
        var second = mapper.selectCompletedRuns(1L,11L,first.getFirst().getCompletedAt(),102L,2);
        assertThat(second).extracting(run -> run.getConversationId()).containsExactly(101L);
        assertThat(second.getFirst().getModuleName()).isEqualTo("深夜图书馆");
        assertThat(mapper.characterExists(1L,13L)).isTrue();
        assertThat(mapper.characterExists(2L,13L)).isFalse();
    }

    @Test
    void restoringBeforeCompletionAndDeletingRunsImmediatelyChangesHistory() throws Exception {
        sql("DELETE FROM trpg_completion WHERE conversation_id=102");
        sql("UPDATE group_conversation SET status='active',closed_at=NULL WHERE id=102");
        assertThat(mapper.selectSummaries(1L).getFirst().getCompletedRunCount()).isEqualTo(1);
        sql("INSERT INTO trpg_completion VALUES (102,'{}')");
        sql("UPDATE group_conversation SET status='closed',closed_at='2026-09-03 12:00' WHERE id=102");
        assertThat(mapper.selectSummaries(1L).getFirst().getCompletedRunCount()).isEqualTo(2);
        sql("DELETE FROM group_conversation WHERE id=102");
        sql("DELETE FROM group_conversation WHERE id=105");
        var summary = mapper.selectSummaries(1L).getFirst();
        assertThat(summary.getCompletedRunCount()).isEqualTo(1);
        assertThat(summary.getLatestRun().getConversationId()).isEqualTo(101);
    }

    private void sql(String text) throws Exception {
        try (var statement = session.getConnection().createStatement()) { statement.execute(text); }
        session.clearCache();
    }
}

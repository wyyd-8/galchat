package com.me.galchat.service.impl.trpg;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.groupchat.material.MaterialMessageCodec;
import com.me.galchat.mapper.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TrpgSceneSummaryRangeIntegrationTest {

    @Test
    void readsFullCoveredRangeAcrossPlansWithoutLeakingPrivateOrOutOfRangeMessages() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yaml"));
        var properties = yaml.getObject();
        var environment = new StandardEnvironment();
        try (var connection = DriverManager.getConnection(
                environment.resolveRequiredPlaceholders(properties.getProperty("spring.datasource.url")),
                environment.resolveRequiredPlaceholders(properties.getProperty("spring.datasource.username")),
                environment.resolveRequiredPlaceholders(properties.getProperty("spring.datasource.password", "")))) {
            connection.setAutoCommit(false);
            try {
                // These tables shadow production tables only on this connection and are rolled back.
                try (var sql = connection.createStatement()) {
                    sql.execute("""
                            CREATE TEMP TABLE group_chat_turn (
                                id BIGINT PRIMARY KEY, plan_id BIGINT
                            ) ON COMMIT DROP;
                            CREATE TEMP TABLE group_chat_message (
                                id BIGINT PRIMARY KEY, conversation_id BIGINT,
                                turn_id BIGINT, sequence_no BIGINT, speaker_type TEXT,
                                message_kind TEXT, visibility TEXT, status TEXT, content TEXT,
                                scene_id BIGINT, reply_step_id BIGINT, client_request_id TEXT,
                                speaker_id BIGINT, created_at TIMESTAMP, updated_at TIMESTAMP
                            ) ON COMMIT DROP;
                            INSERT INTO group_chat_turn VALUES (1,31),(2,32);
                            INSERT INTO group_chat_message (
                                id,conversation_id,turn_id,sequence_no,speaker_type,
                                message_kind,visibility,status,content
                            ) VALUES
                            (1,7,1,10,'kp','dialogue','public','completed','进入棚屋'),
                            (2,7,1,20,'kp','dialogue','public','completed','场景结束'),
                            (3,7,NULL,12,'kp','combat_result','public','completed','战斗结果：众人逃脱'),
                            (4,7,2,15,'kp','dialogue','public','completed','战后叙述：重伤且装备遗失'),
                            (5,8,NULL,13,'kp','combat_result','public','completed','其他会话秘密'),
                            (6,7,2,9,'kp','dialogue','public','completed','前一场景'),
                            (7,7,2,21,'kp','dialogue','public','completed','后一场景'),
                            (8,7,2,14,'kp','dialogue','private','completed','KP私密真相'),
                            (9,7,2,16,'kp','dialogue','public','failed','失败草稿'),
                            (10,7,2,17,'character','dialogue','public','completed','我要去报社会合'),
                            (11,7,2,18,'kp','dice_roll','public','completed','原始骰点JSON');
                            """);
                }
                var config = new MybatisConfiguration();
                config.setMapUnderscoreToCamelCase(true);
                config.addMapper(GroupChatMessageMapper.class);
                try (var session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(connection)) {
                    var summaryMapper = mock(GroupContextSummaryMapper.class);
                    var generator = mock(TrpgSummaryTextGenerator.class);
                    var conversations = mock(GroupConversationMapper.class);
                    var plans = mock(GroupReplyPlanMapper.class);
                    var participants = mock(TrpgSceneParticipantService.class);
                    var conversation = new GroupConversation().setId(7L);
                    var plan = new GroupReplyPlan().setId(31L).setConversationId(7L).setParentPlanId(30L);
                    when(summaryMapper.selectList(any())).thenReturn(List.of());
                    when(conversations.selectById(7L)).thenReturn(conversation);
                    when(plans.selectById(31L)).thenReturn(plan);
                    when(participants.summaryState(conversation, plan)).thenReturn(
                            new TrpgSceneParticipantService.SceneSummaryState("棚屋", List.of("艾琳")));
                    when(generator.summarizeChildClues(any())).thenReturn("无");
                    when(generator.summarizeChildPlot(any())).thenReturn("众人负伤逃脱，装备遗失。");
                    var service = new TrpgSceneSummaryService(session.getMapper(GroupChatMessageMapper.class),
                            summaryMapper, mock(TrpgExplorationRecordService.class), generator,
                            conversations, plans, participants, mock(MaterialMessageCodec.class));

                    var result = service.summarize(7L, 21L, 31L);

                    assertThat(result.getStartSequence()).isEqualTo(10L);
                    assertThat(result.getEndSequence()).isEqualTo(20L);
                    var evidence = ArgumentCaptor.forClass(String.class);
                    verify(generator).summarizeChildPlot(evidence.capture());
                    assertThat(evidence.getValue()).containsSubsequence(
                                    "进入棚屋", "战斗结果：众人逃脱", "战后叙述：重伤且装备遗失", "场景结束")
                            .doesNotContain("其他会话秘密", "前一场景", "后一场景", "KP私密真相",
                                    "失败草稿", "我要去报社会合", "原始骰点JSON");
                    verify(summaryMapper).insert(result);
                }
            } finally {
                if (!connection.isClosed()) connection.rollback();
            }
        }
    }
}

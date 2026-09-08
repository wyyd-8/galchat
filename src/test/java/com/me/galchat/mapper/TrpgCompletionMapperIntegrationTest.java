package com.me.galchat.mapper;

import com.me.galchat.domain.dto.TrpgCompletionModels.*;
import com.me.galchat.domain.dto.TrpgEpilogueModels;
import com.me.galchat.domain.po.TrpgCompletion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class TrpgCompletionMapperIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TrpgCompletionMapper mapper;
    @Autowired GroupChatTurnMapper turnMapper;
    @Autowired GroupChatReplyStepMapper stepMapper;
    @Autowired GroupChatToolCallMapper toolMapper;

    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void legacySchemaUpgradeDiscardsPartialResultsAndKeepsOnlyNecessaryColumns() throws Exception {
        jdbc.execute("CREATE TEMP TABLE trpg_completion (conversation_id BIGINT PRIMARY KEY, turn_id BIGINT, data JSONB, status TEXT, completed_at TIMESTAMP, archived_at TIMESTAMP) ON COMMIT DROP");
        jdbc.execute("INSERT INTO trpg_completion VALUES (1,2,'{\"epilogues\":[],\"overview\":null}', 'failed', NULL, NULL), (3,4,'{\"epilogues\":[],\"overview\":{}}', 'ready', now(), now())");
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("data/maintenance/2026-09-08-trpg-summary-turn.sql"))
                .replace("BEGIN;", "").replace("COMMIT;", "");
        jdbc.execute(sql);
        jdbc.execute(sql);
        assertThat(jdbc.queryForObject("SELECT data IS NULL FROM trpg_completion WHERE conversation_id = 1", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT data IS NOT NULL FROM trpg_completion WHERE conversation_id = 3", Boolean.class)).isTrue();
        assertThat(jdbc.queryForList("SELECT attname FROM pg_attribute WHERE attrelid = 'trpg_completion'::regclass AND attnum > 0 AND NOT attisdropped ORDER BY attnum", String.class))
                .containsExactly("conversation_id", "turn_id", "data");
    }

    @Test
    void finishBoundaryWaitsForTheRequestingKpReplyToComplete() {
        jdbc.execute("CREATE TEMP TABLE group_chat_reply_step (id BIGINT, turn_id BIGINT, status TEXT) ON COMMIT DROP");
        jdbc.execute("CREATE TEMP TABLE group_chat_tool_call (reply_step_id BIGINT, tool_name TEXT, tool_result TEXT) ON COMMIT DROP");
        jdbc.execute("INSERT INTO group_chat_reply_step VALUES (1,9,'failed'), (2,10,'completed')");
        jdbc.execute("INSERT INTO group_chat_tool_call VALUES (1,'finishRun','ok'), (2,'finishRun','ok')");
        assertThat(stepMapper.countCompletedRunFinishByTurn(9L)).isZero();
        stepMapper.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.me.galchat.domain.po.GroupChatReplyStep>()
                .eq(com.me.galchat.domain.po.GroupChatReplyStep::getId, 1L)
                .set(com.me.galchat.domain.po.GroupChatReplyStep::getStatus, "completed"));
        assertThat(stepMapper.countCompletedRunFinishByTurn(9L)).isEqualTo(1L);
        toolMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.me.galchat.domain.po.GroupChatToolCall>()
                .eq(com.me.galchat.domain.po.GroupChatToolCall::getReplyStepId, 1L));
        assertThat(stepMapper.countCompletedRunFinishByTurn(9L)).isZero();
    }

    @Test
    void finalTransactionFailureRollsBackPublicationAndRetryRegeneratesEverything() throws Exception {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("data/maintenance/2026-09-07-trpg-completion.sql"))
                .replace("CREATE TABLE IF NOT EXISTS", "CREATE TEMP TABLE").replace("\n);", "\n) ON COMMIT DROP;");
        jdbc.execute(sql);
        jdbc.execute("CREATE TEMP TABLE completion_publication (content TEXT) ON COMMIT DROP");
        jdbc.execute("CREATE TEMP TABLE group_chat_turn (LIKE public.group_chat_turn INCLUDING ALL) ON COMMIT DROP");
        jdbc.execute("CREATE TEMP TABLE group_chat_reply_step (LIKE public.group_chat_reply_step INCLUDING ALL) ON COMMIT DROP");
        var turns = turnMapper;
        var conversations = org.mockito.Mockito.mock(com.me.galchat.service.impl.group.GroupConversationService.class);
        var locks = org.mockito.Mockito.mock(com.me.galchat.service.impl.group.GroupConversationLockService.class);
        var materials = org.mockito.Mockito.mock(com.me.galchat.service.impl.trpg.TrpgCompletionMaterialsService.class);
        var epilogues = org.mockito.Mockito.mock(com.me.galchat.service.impl.trpg.TrpgEpilogueService.class);
        var generator = org.mockito.Mockito.mock(com.me.galchat.service.impl.trpg.TrpgCompletionGenerator.class);
        var lifecycle = org.mockito.Mockito.mock(com.me.galchat.service.impl.group.GroupConversationLifecycleService.class);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        // A nested transaction isolates the final commit inside this test's rollback-only outer transaction.
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        var service = new com.me.galchat.service.impl.trpg.TrpgCompletionService(mapper, turns, conversations,
                materials, epilogues, generator, lifecycle, transaction, stepMapper);
        var conversation = new com.me.galchat.domain.po.GroupConversation().setId(-92001L).setMode("trpg").setStatus("active");
        var material = new Materials("灯塔", null, 42, 3, List.of(), List.of(), List.of());
        var turn = new com.me.galchat.domain.po.GroupChatTurn().setId(-92003L).setConversationId(-92001L).setPlanSource("summary").setStatus("running");
        var step = new com.me.galchat.domain.po.GroupChatReplyStep().setId(-92004L).setTurnId(-92003L).setActionType("trpg_summary").setStatus("running");
        var overview = new Overview("概要", "结局", List.of());
        mapper.insert(new TrpgCompletion().setConversationId(-92001L).setTurnId(-92002L));
        turn.setRevision(0).setCreatedAt(LocalDateTime.now()).setUpdatedAt(LocalDateTime.now());
        step.setStepNo(1).setGroupKey("summary").setGroupName("生成总结").setGroupOrder(1).setItemOrder(1)
                .setSpeakerType("kp").setForceReply(false)
                .setCreatedAt(LocalDateTime.now()).setUpdatedAt(LocalDateTime.now());
        turns.insert(new com.me.galchat.domain.po.GroupChatTurn().setId(-92002L).setConversationId(-92001L)
                .setPlanSource("SCENE").setStatus("completed").setRevision(0)
                .setCreatedAt(LocalDateTime.now()).setUpdatedAt(LocalDateTime.now()));
        turns.insert(turn);
        stepMapper.insert(step);
        org.mockito.Mockito.when(materials.capture(conversation, -92002L)).thenReturn(material);
        org.mockito.Mockito.when(epilogues.generate(conversation, material)).thenReturn(List.of());
        org.mockito.Mockito.when(generator.generate(material)).thenReturn(overview);
        org.mockito.Mockito.doAnswer(call -> { jdbc.update("INSERT INTO completion_publication VALUES ('epilogue')"); return null; })
                .when(epilogues).persist(conversation, List.of(), -92003L, -92004L);
        org.mockito.Mockito.doThrow(new IllegalStateException("close failed")).doAnswer(call -> {
            conversation.setClosedAt(LocalDateTime.of(2026, 9, 7, 12, 0)).setStatus("closed"); return null;
        }).when(lifecycle).closeWithCompletionUnderLock(conversation, material, "概要");
        assertThatThrownBy(() -> service.executeUnderLock(conversation, turn, step)).hasMessage("close failed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM completion_publication", Integer.class)).isZero();
        var failed = mapper.selectById(-92001L);
        assertThat(failed.getData()).isNull();
        assertThat(turns.selectById(-92003L).getStatus()).isEqualTo("running");
        assertThat(stepMapper.selectById(-92004L).getStatus()).isEqualTo("running");
        service.executeUnderLock(conversation, turn, step);
        assertThat(mapper.selectById(-92001L).getData().overview()).isEqualTo(overview);
        assertThat(turns.selectById(-92003L).getStatus()).isEqualTo("completed");
        assertThat(stepMapper.selectById(-92004L).getStatus()).isEqualTo("completed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM completion_publication", Integer.class)).isEqualTo(1);
        org.mockito.Mockito.verify(generator, org.mockito.Mockito.times(2)).generate(material);
        org.mockito.Mockito.verify(epilogues, org.mockito.Mockito.times(2)).generate(conversation, material);
    }

    @Test
    void persistsFinalResultWithTypedJsonbAndReplacesItOnRestore() throws Exception {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("data/maintenance/2026-09-07-trpg-completion.sql"))
                .replace("CREATE TABLE IF NOT EXISTS", "CREATE TEMP TABLE").replace("\n);", "\n) ON COMMIT DROP;");
        jdbc.execute(sql);
        var saved = new TrpgCompletion().setConversationId(-92001L).setTurnId(-92002L);
        mapper.insert(saved);
        assertThat(mapper.selectById(-92001L).getData()).isNull();
        var material = new Materials("灯塔", null, 42, 3,
                List.of(new Source(1, 42, "共同走出迷雾。")), List.of(), List.of());
        saved.setData(new Data(material, List.of(new TrpgEpilogueModels.Entry(11L, "林恩", "重返报社", "林恩回到了报社。")), null));
        mapper.updateById(saved);
        assertThat(mapper.selectById(-92001L).getData()).isEqualTo(saved.getData());
        saved.setData(new Data(material, saved.getData().epilogues(), new Overview("概要", "结局", List.of(new Chapter(0, "归来", "走出迷雾")))));
        mapper.updateById(saved);
        var archived = mapper.selectById(-92001L);
        assertThat(archived).isEqualTo(saved);
        mapper.deleteById(-92001L);
        assertThat(mapper.selectById(-92001L)).isNull();
        mapper.insert(archived);
        assertThat(mapper.selectById(-92001L)).isEqualTo(archived);
    }
}

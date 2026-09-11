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
    @Autowired TrpgCombatMapper combatMapper;
    @Autowired CocModuleLocationMapper locationMapper;

    @Test
    void combatCaptureFiltersRunStatusAndSummaryBoundaryAndOrdersByOccurrence() {
        jdbc.execute("""
                CREATE TEMP TABLE trpg_combat (id BIGINT, conversation_id BIGINT, source_scene_id BIGINT,
                    status TEXT, order_mode TEXT, current_round INT, participants JSONB, quick_npc_specs JSONB,
                    active_turn_results JSONB, start_requested_step_id BIGINT, finish_requested_step_id BIGINT,
                    start_sequence BIGINT, end_sequence BIGINT, summary TEXT, created_at TIMESTAMP,
                    updated_at TIMESTAMP, ended_at TIMESTAMP) ON COMMIT DROP
                """);
        jdbc.execute("""
                CREATE TEMP TABLE group_reply_plan (id BIGINT, conversation_id BIGINT, source TEXT,
                    context_id BIGINT, execution_key TEXT, display_name TEXT, next_plan_id BIGINT,
                    resume_plan_id BIGINT, parent_plan_id BIGINT, created_at TIMESTAMP, updated_at TIMESTAMP) ON COMMIT DROP
                """);
        jdbc.execute("""
                INSERT INTO trpg_combat (id, conversation_id, status, start_requested_step_id, end_sequence, summary)
                VALUES (11,7,'COMPLETED',30,70,'第二场'), (12,7,'COMPLETED',10,20,'第一场'),
                       (13,8,'COMPLETED',1,10,'其他跑团'), (14,7,'ACTIVE',40,75,'未完成'),
                       (15,7,'COMPLETED',50,100,'总结之后'), (16,7,'START_REQUESTED',5,NULL,'仅请求')
                """);
        // Normal completion removes the runtime plans; only persistent locations remain.
        jdbc.execute("CREATE TEMP TABLE coc_module_location (id BIGINT, module_id BIGINT, name TEXT, summary TEXT, content TEXT, created_at TIMESTAMP, updated_at TIMESTAMP) ON COMMIT DROP");
        jdbc.execute("INSERT INTO coc_module_location (id, name) VALUES (21, '林间营地')");
        jdbc.execute("UPDATE trpg_combat SET source_scene_id = 21");
        var messages = org.mockito.Mockito.mock(GroupChatMessageMapper.class);
        org.mockito.Mockito.when(messages.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new com.me.galchat.domain.po.GroupChatMessage().setSequenceNo(80L)));
        var materials = new com.me.galchat.service.impl.trpg.TrpgCompletionMaterialsService(
                org.mockito.Mockito.mock(GroupContextSummaryMapper.class),
                new com.me.galchat.service.impl.trpg.TrpgSummaryIntervalSelector(), messages,
                org.mockito.Mockito.mock(GroupChatTurnMapper.class), org.mockito.Mockito.mock(CocCharacterMapper.class),
                org.mockito.Mockito.mock(CocModuleMapper.class), org.mockito.Mockito.mock(TrpgAutoSaveMapper.class),
                org.mockito.Mockito.mock(DiceRollResultMapper.class),
                org.mockito.Mockito.mock(com.me.galchat.groupchat.dice.DiceRollMessageCodec.class),
                org.mockito.Mockito.mock(com.me.galchat.service.impl.trpg.TrpgEpilogueService.class), combatMapper, locationMapper);
        var result = materials.capture(new com.me.galchat.domain.po.GroupConversation().setId(7L), 9L);
        assertThat(result.combats()).containsExactly(
                new Combat(12L, "林间营地", "第一场"), new Combat(11L, "林间营地", "第二场"));
        assertThat(materials.capture(new com.me.galchat.domain.po.GroupConversation().setId(9L), 9L).combats()).isEmpty();
        var archived = List.of(new Combat(12L, null, "报告内的原始结果"),
                new Combat(11L, "保存时的名称", "第二场"), new Combat(13L, null, "其他跑团"),
                new Combat(99L, null, "已无战斗源记录"));
        assertThat(materials.fillMissingCombatSceneNames(7L, archived)).containsExactly(
                new Combat(12L, "林间营地", "报告内的原始结果"),
                new Combat(11L, "保存时的名称", "第二场"), new Combat(13L, null, "其他跑团"),
                new Combat(99L, null, "已无战斗源记录"));
        assertThat(archived.getFirst().sceneName()).isNull();
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
        com.me.galchat.support.ConsoleSqlTestSupport.initializeSchema(jdbc);
        jdbc.execute("CREATE TEMP TABLE completion_publication (content TEXT) ON COMMIT DROP");
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
        var actorRuntime = org.mockito.Mockito.mock(com.me.galchat.service.impl.group.GroupActorRuntimeService.class);
        var kpClient = org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.class);
        org.mockito.Mockito.when(actorRuntime.chatClient(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(kpClient))).thenReturn(kpClient);
        var service = new com.me.galchat.service.impl.trpg.TrpgCompletionService(mapper, turns, conversations,
                materials, epilogues, generator, lifecycle, transaction, stepMapper, actorRuntime, kpClient);
        var conversation = new com.me.galchat.domain.po.GroupConversation().setId(-92001L).setMode("trpg").setStatus("active");
        var material = new Materials("灯塔", null, 42, 3, List.of(), List.of(), List.of(), List.of());
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
        org.mockito.Mockito.when(epilogues.generate(kpClient, conversation, material)).thenReturn(List.of());
        org.mockito.Mockito.when(generator.generate(kpClient, material)).thenReturn(overview);
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
        org.mockito.Mockito.verify(generator, org.mockito.Mockito.times(2)).generate(kpClient, material);
        org.mockito.Mockito.verify(epilogues, org.mockito.Mockito.times(2)).generate(kpClient, conversation, material);
    }

    @Test
    void persistsFinalResultWithTypedJsonbAndReplacesItOnRestore() throws Exception {
        com.me.galchat.support.ConsoleSqlTestSupport.initializeSchema(jdbc);
        var saved = new TrpgCompletion().setConversationId(-92001L).setTurnId(-92002L);
        mapper.insert(saved);
        assertThat(mapper.selectById(-92001L).getData()).isNull();
        var material = new Materials("灯塔", null, 42, 3,
                List.of(new Source(1, 42, "共同走出迷雾。")), List.of(), List.of(),
                List.of(new Combat(11L, "林间营地", "战斗结果：\n1. 食尸鬼倒地。")));
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

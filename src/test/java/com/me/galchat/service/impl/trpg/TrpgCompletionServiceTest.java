package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.dto.TrpgCompletionModels.*;
import com.me.galchat.domain.po.*;
import com.me.galchat.mapper.*;
import com.me.galchat.service.impl.group.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrpgCompletionServiceTest {
    final TrpgCompletionMapper mapper = mock(TrpgCompletionMapper.class);
    final GroupChatTurnMapper turns = mock(GroupChatTurnMapper.class);
    final GroupActorRuntimeService actorRuntime = mock(GroupActorRuntimeService.class);
    final org.springframework.ai.chat.client.ChatClient kpDefault = mock(org.springframework.ai.chat.client.ChatClient.class);
    final org.springframework.ai.chat.client.ChatClient selectedClient = mock(org.springframework.ai.chat.client.ChatClient.class);
    final GroupChatReplyStepMapper steps = mock(GroupChatReplyStepMapper.class);
    final GroupConversationService conversations = mock(GroupConversationService.class);
    final TrpgCompletionMaterialsService materials = mock(TrpgCompletionMaterialsService.class);
    final TrpgEpilogueService epilogues = mock(TrpgEpilogueService.class);
    final TrpgCompletionGenerator generator = mock(TrpgCompletionGenerator.class);
    final GroupConversationLifecycleService lifecycle = mock(GroupConversationLifecycleService.class);
    final TransactionTemplate transactions = mock(TransactionTemplate.class);
    final GroupConversation conversation = new GroupConversation().setId(7L).setMode("trpg").setStatus("active");
    final TrpgCompletion saved = new TrpgCompletion().setConversationId(7L).setTurnId(9L);
    final GroupChatTurn turn = new GroupChatTurn().setId(10L).setConversationId(7L).setPlanSource("summary").setStatus("running");
    final GroupChatReplyStep step = new GroupChatReplyStep().setId(11L).setTurnId(10L).setActionType("trpg_summary").setSpeakerType("kp").setStatus("running");
    final TrpgCompletionService service = new TrpgCompletionService(mapper, turns, conversations,
            materials, epilogues, generator, lifecycle, transactions, steps, actorRuntime, kpDefault);
    final Materials frozen = new Materials("灯塔", null, 42, 3,
            List.of(new Source(1, 42, "他们共同登上了灯塔。")), List.of(), List.of(), List.of());
    final Overview overview = new Overview("概要", "团队结局", List.of(new Chapter(0, "灯塔", "共同登上了灯塔")));

    @BeforeEach
    void setup() {
        when(actorRuntime.chatClient(conversation, step, kpDefault)).thenReturn(selectedClient);
        when(mapper.selectById(7L)).thenReturn(saved);
        when(conversations.requireAuthorized(7L)).thenReturn(conversation);
        when(turns.selectById(9L)).thenReturn(new GroupChatTurn().setId(9L).setConversationId(7L).setStatus("completed"));
        when(materials.capture(conversation, 9L)).thenReturn(frozen);
        when(epilogues.generate(selectedClient, conversation, frozen)).thenReturn(List.of());
        when(generator.generate(selectedClient, frozen)).thenReturn(overview);
        doAnswer(call -> { call.<Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0)
                .accept(new SimpleTransactionStatus()); return null; }).when(transactions).executeWithoutResult(any());
        doAnswer(call -> { conversation.setStatus("closed").setClosedAt(LocalDateTime.of(2026, 9, 7, 12, 0)); return null; })
                .when(lifecycle).closeWithCompletionUnderLock(conversation, frozen, "概要");
    }

    @Test
    void publishesFrozenCombatResultsAndKeepsLegacyAbsenceDistinctFromEmpty() throws Exception {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var legacy = json.readValue("""
                {"title":"旧报告","endSequence":42,"turnCount":3,"sources":[],"investigators":[],"rolls":[]}
                """, Materials.class);
        saved.setData(new Data(legacy, List.of(), new Overview("概要", "结局", List.of())));
        assertThat(service.get(7L).combats()).isNull();
        var combat = new Combat(11L, "林间营地", "战斗结果：原有裁定。");
        var withCombat = new Materials("旅程", null, 42, 3, List.of(), List.of(), List.of(), List.of(combat));
        saved.setData(new Data(withCombat, List.of(), new Overview("概要", "结局", List.of())));
        assertThat(service.get(7L).combats()).containsExactly(combat);
        saved.setData(new Data(frozen, List.of(), overview));
        assertThat(service.get(7L).combats()).isEmpty();
    }

    @Test
    void readingArchivedReportFillsMissingNamesWithoutRegeneratingOrChangingSavedResults() {
        var entries = List.of(new Combat(11L, null, "原有结果"));
        var archived = new Materials("旅程", null, 42, 3, List.of(), List.of(), List.of(), entries);
        saved.setData(new Data(archived, List.of(), new Overview("概要", "结局", List.of())));
        when(materials.fillMissingCombatSceneNames(7L, entries))
                .thenReturn(List.of(new Combat(11L, "林间营地", "原有结果")));
        assertThat(service.get(7L).combats()).containsExactly(new Combat(11L, "林间营地", "原有结果"));
        assertThat(saved.getData().materials().combats().getFirst().sceneName()).isNull();
        verifyNoInteractions(generator, epilogues, lifecycle);
    }

    @Test
    void bothSummaryRequestsUseTheKpClientSelectedForThisStep() {
        service.executeUnderLock(conversation, turn, step);
        verify(actorRuntime).chatClient(conversation, step, kpDefault);
        verify(epilogues).generate(selectedClient, conversation, frozen);
        verify(generator).generate(selectedClient, frozen);
        verifyNoInteractions(kpDefault);
    }

    @Test
    void failureDiscardsAllResultsAndRetryRegeneratesBothBranches() {
        when(generator.generate(selectedClient, frozen)).thenThrow(new IllegalStateException("model unavailable")).thenReturn(overview);
        assertThatThrownBy(() -> service.executeUnderLock(conversation, turn, step)).hasMessage("model unavailable");
        assertThat(saved.getData()).isNull();
        assertThat(conversation.getStatus()).isEqualTo("active");
        verify(epilogues, never()).persist(any(), any(), any(), any());
        verifyNoInteractions(lifecycle);
        service.executeUnderLock(conversation, turn, step);
        assertThat(service.get(7L).status()).isEqualTo("ready");
        assertThat(service.get(7L).archivedAt()).isEqualTo(conversation.getClosedAt());
        assertThat(turn.getStatus()).isEqualTo("completed");
        assertThat(step.getStatus()).isEqualTo("completed");
        verify(materials, times(2)).capture(conversation, 9L);
        verify(epilogues, times(2)).generate(selectedClient, conversation, frozen);
        verify(generator, times(2)).generate(selectedClient, frozen);
        verify(epilogues).persist(conversation, List.of(), 10L, 11L);
    }

    @Test
    void bothBranchesStartBeforeEitherCompletes() {
        var started = new CountDownLatch(2);
        when(epilogues.generate(selectedClient, conversation, frozen)).thenAnswer(call -> {
            started.countDown(); assertThat(started.await(3, TimeUnit.SECONDS)).isTrue(); return List.of();
        });
        when(generator.generate(selectedClient, frozen)).thenAnswer(call -> {
            started.countDown(); assertThat(started.await(3, TimeUnit.SECONDS)).isTrue(); return overview;
        });
        service.executeUnderLock(conversation, turn, step);
        assertThat(saved.getData().overview()).isEqualTo(overview);
    }

    @Test
    void epilogueFailureAlsoLeavesReportUnpublished() {
        when(epilogues.generate(selectedClient, conversation, frozen)).thenThrow(new IllegalStateException("epilogue failed"));
        assertThatThrownBy(() -> service.executeUnderLock(conversation, turn, step)).hasMessage("epilogue failed");
        assertThat(saved.getData()).isNull();
        verify(generator).generate(selectedClient, frozen);
        verifyNoInteractions(transactions, lifecycle);
    }

    @Test
    void sourceTurnMustBeCompletedBeforeGeneration() {
        when(turns.selectById(9L)).thenReturn(new GroupChatTurn().setStatus("failed"));
        assertThatThrownBy(() -> service.executeUnderLock(conversation, turn, step)).hasMessageContaining("最后的场景");
        verifyNoInteractions(materials, epilogues, generator);
    }
}

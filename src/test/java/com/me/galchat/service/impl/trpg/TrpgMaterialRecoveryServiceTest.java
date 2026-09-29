package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.po.*;
import com.me.galchat.mapper.*;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.service.impl.group.*;
import com.me.galchat.groupchat.dice.DiceRollMessageCodec;
import com.me.galchat.support.MybatisPlusTestSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.core.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrpgMaterialRecoveryServiceTest {
    @ParameterizedTest
    @CsvSource({"false,commit", "true,commit", "false,rollback", "false,commit_failure", "false,redis_failure"})
    void recoveryReconcilesOnlyDeletedDisplaysAfterDatabaseCommit(boolean earlierDisplay, String outcome) {
        MybatisPlusTestSupport.initialize(GroupChatReplyStep.class);
        var messages = mock(GroupChatMessageMapper.class);
        var redis = mock(StringRedisTemplate.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(sets);
        Set<String> cached = new HashSet<>(Set.of("31", "32"));
        List<String> events = new ArrayList<>();
        when(sets.remove(anyString(), any())).thenAnswer(i -> {
            events.add("cache");
            if (outcome.equals("redis_failure")) throw new IllegalStateException("redis unavailable");
            assertThat(i.<String>getArgument(0)).isEqualTo("trpg:group:shown-materials:7");
            return cached.remove(i.<String>getArgument(1)) ? 1L : 0L;
        });
        when(sets.add(anyString(), any(String[].class))).thenAnswer(i -> {
            events.add("cache"); cached.add(i.getArgument(1)); return 1L;
        });
        List<GroupChatMessage> rows = new ArrayList<>();
        rows.add(message(12L, 103L, 31)); // removed suffix
        rows.add(message(9L, 103L, 32)); // boundary itself survives
        if (earlierDisplay) rows.add(message(3L, 99L, 31));
        when(messages.selectList(any())).thenAnswer(i -> List.copyOf(rows));
        when(messages.deleteAfterCheckpoint(103L, 9L)).thenAnswer(i -> {
            events.add("delete"); rows.removeIf(m -> m.getReplyStepId() == 103L && m.getId() > 9L); return 1;
        });
        var checkpoints = mock(GroupTurnCheckpointMapper.class);
        when(checkpoints.selectById(7L)).thenReturn(new GroupTurnCheckpoint().setTurnId(101L)
                .setReplyStepId(103L).setCheckpointType("STEP_START").setMessageId(9L).setToolCallId(0L));
        var service = new GroupTurnCheckpointService(checkpoints, messages, mock(GroupChatToolCallMapper.class),
                mock(GroupChatReplyStepMapper.class), mock(GroupChatTurnMapper.class), mock(DiceRollSummaryMapper.class),
                mock(DiceRollMessageCodec.class), mock(ICharacterCardService.class), JsonMapper.builder().build(),
                mock(GroupChatFavorRollbackService.class), mock(TrpgEquipmentService.class),
                new TrpgMaterialRecoveryService(messages, new TrpgMaterialStateStore(redis), JsonMapper.builder().build()));
        var tx = new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object t, TransactionDefinition d) { }
            @Override protected void doCommit(DefaultTransactionStatus s) {
                if (outcome.equals("commit_failure")) throw new IllegalStateException("commit failed");
                events.add("commit");
            }
            @Override protected void doRollback(DefaultTransactionStatus s) { events.add("rollback"); }
        });
        Throwable failure = catchThrowable(() -> tx.executeWithoutResult(s -> {
            service.restore(new GroupChatTurn().setId(101L).setConversationId(7L),
                    new GroupChatReplyStep().setId(103L).setTurnId(101L));
            assertThat(cached).containsExactlyInAnyOrder("31", "32");
            if (outcome.equals("rollback")) s.setRollbackOnly();
        }));
        if (outcome.equals("commit_failure")) assertThat(failure).hasMessage("commit failed");
        else assertThat(failure).isNull();
        if (outcome.equals("commit")) {
            assertThat(cached).contains("32");
            assertThat(cached.contains("31")).isEqualTo(earlierDisplay);
            assertThat(events).containsExactly("delete", "commit", "cache");
        } else {
            assertThat(cached).containsExactlyInAnyOrder("31", "32");
            if (!outcome.equals("redis_failure")) assertThat(events).doesNotContain("cache");
        }
    }
    private GroupChatMessage message(long id, long stepId, long materialId) {
        return new GroupChatMessage().setId(id).setReplyStepId(stepId).setConversationId(7L)
                .setMessageKind("material").setStatus("completed").setContent("{\"materialId\":" + materialId + "}");
    }
}

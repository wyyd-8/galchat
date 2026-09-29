package com.me.galchat.service.impl.trpg;

import com.me.galchat.groupchat.runtime.GroupActorRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.core.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrpgFinalizationRedisStateTest {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final SetOperations<String, String> sets = mock(SetOperations.class);
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    final TrpgSceneProgressStore progress = new TrpgSceneProgressStore(redis);
    final TrpgSceneSelectionStore selections = new TrpgSceneSelectionStore(redis);

    @Test
    void progressWritesHaveNoDeadlineAndRemoveOldSetExpiry() {
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForValue()).thenReturn(values);

        progress.markReady(7L, 20L, 31L);
        progress.requestFinish(7L, 20L);

        verify(sets).add("trpg:group:scene-progress:7:20:ready", "character-card:31");
        verify(redis).persist("trpg:group:scene-progress:7:20:ready");
        verify(values).set("trpg:group:scene-progress:7:20:finish", "1");
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void selectionWritesHaveNoDeadlineAndRemoveOldChoiceExpiry() {
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForValue()).thenReturn(values);

        selections.putOptions(7L, 9L, Map.of("1", new TrpgSceneSelectionStore.LocationOption(30L, "码头")));
        selections.put(7L, 9L, new GroupActorRef("character", 31L), 30L);

        verify(hashes).put("trpg:group:scene-selection:7:9:options", "1", "30\t码头");
        verify(redis).persist("trpg:group:scene-selection:7:9:choices");
        verify(values).set("trpg:group:scene-selection:7:active", "9");
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @ParameterizedTest
    @CsvSource({"scene,commit", "scene,rollback", "scene,commit_failure", "scene,redis_failure",
            "ready,commit", "ready,rollback", "selection,commit", "selection,rollback",
            "selection,commit_failure", "selection,redis_failure"})
    void clearsMarkersOnlyAfterSuccessfulCommit(String kind, String outcome) {
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("trpg:group:scene-selection:7:active")).thenReturn("9");
        List<String> events = new ArrayList<>();
        when(redis.delete(any(Collection.class))).thenAnswer(call -> {
            events.add("delete");
            if (outcome.equals("redis_failure")) throw new IllegalStateException("redis unavailable");
            return 1L;
        });
        when(redis.delete(anyString())).thenAnswer(call -> {
            events.add("delete");
            if (outcome.equals("redis_failure")) throw new IllegalStateException("redis unavailable");
            return true;
        });
        when(sets.remove(anyString(), any())).thenAnswer(call -> { events.add("delete"); return 1L; });
        var transactions = new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) {
                if (outcome.equals("commit_failure")) throw new IllegalStateException("commit failed");
                events.add("commit");
            }
            @Override protected void doRollback(DefaultTransactionStatus status) { events.add("rollback"); }
        });

        var execution = catchThrowable(() -> transactions.executeWithoutResult(status -> {
            switch (kind) {
                case "scene" -> progress.clear(7L, 20L);
                case "ready" -> progress.clearReady(7L, 20L, 31L);
                case "selection" -> selections.clear(7L);
                default -> throw new AssertionError(kind);
            }
            assertThat(events).as("Redis markers must survive until commit").isEmpty();
            if (outcome.equals("rollback")) status.setRollbackOnly();
        }));

        if (outcome.equals("commit_failure")) {
            assertThat(execution).hasMessage("commit failed");
            assertThat(events).doesNotContain("delete");
        } else if (outcome.equals("rollback")) {
            assertThat(execution).isNull();
            assertThat(events).containsExactly("rollback");
        } else {
            assertThat(execution).as("cleanup cannot undo an already committed turn").isNull();
            assertThat(events).startsWith("commit", "delete");
        }
    }
}

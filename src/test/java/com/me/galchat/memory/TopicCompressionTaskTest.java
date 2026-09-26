package com.me.galchat.memory;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import static org.assertj.core.api.Assertions.*;

class TopicCompressionTaskTest {
    @Test void streamsReplyBeforeCompressionAndPublishesOnlyAfterBothFinish() {
        var queue = new ArrayList<Runnable>();
        var events = new ArrayList<String>();
        var task = new TopicCompressionTask(queue::add, () -> events.add("unlock"));
        task.submit(() -> () -> events.add("publish"));
        task.attach(Flux.just("reply")).subscribe(events::add, e -> fail(e.getMessage()), () -> events.add("complete"));
        assertThat(events).containsExactly("reply");
        queue.getFirst().run();
        assertThat(events).containsExactly("reply", "publish", "unlock", "complete");
    }
    @Test void completedCompressionCannotChangeWindowBetweenGroupSpeakers() {
        var queue = new ArrayList<Runnable>();
        var events = new ArrayList<String>();
        var replies = Sinks.many().unicast().<String>onBackpressureBuffer();
        var task = new TopicCompressionTask(queue::add, () -> events.add("unlock"));
        task.submit(() -> () -> events.add("publish"));
        task.attach(replies.asFlux()).subscribe(events::add);
        replies.tryEmitNext("actor1");
        queue.getFirst().run();
        replies.tryEmitNext("actor2");
        assertThat(events).containsExactly("actor1", "actor2");
        replies.tryEmitComplete();
        assertThat(events).containsExactly("actor1", "actor2", "publish", "unlock");
    }
    @Test void cancellationKeepsLockUntilBackgroundWritesHaveStopped() {
        var queue = new ArrayList<Runnable>();
        var events = new ArrayList<String>();
        var task = new TopicCompressionTask(queue::add, () -> events.add("unlock"));
        task.submit(() -> () -> events.add("publish"));
        var subscription = task.attach(Flux.never()).subscribe();
        subscription.dispose();
        assertThat(events).isEmpty();
        queue.getFirst().run();
        assertThat(events).containsExactly("publish", "unlock");
    }
    @Test void failureAndRejectionKeepWindowAndReleaseLock() {
        List<Executor> executors = List.of(Runnable::run, task -> { throw new RejectedExecutionException(); });
        for (Executor executor : executors) {
            var events = new ArrayList<String>();
            var task = new TopicCompressionTask(executor, () -> events.add("unlock"));
            task.submit(() -> { throw new IllegalStateException("model failed"); });
            assertThat(task.attach(Flux.just("reply")).collectList().block()).containsExactly("reply");
            assertThat(events).containsExactly("unlock");
        }
    }
    @Test void cancellationCleansUpReplyBeforeReleasingLock() {
        var effects = new ArrayList<String>();
        var task = new TopicCompressionTask(Runnable::run, () -> effects.add("unlock"));
        task.submit(() -> () -> effects.add("publish"));
        var subscription = task.attach(Flux.never().doOnCancel(() -> effects.add("cancel steps"))).subscribe();
        subscription.dispose();
        assertThat(effects).containsExactly("cancel steps", "publish", "unlock");
    }

    @Test void cancellationBeforeAdvisorStartsRejectsLateWork() {
        var queue = new ArrayList<Runnable>();
        var task = new TopicCompressionTask(queue::add, () -> {});
        task.finish();
        task.submit(() -> () -> {});
        assertThat(queue).isEmpty();
    }
}

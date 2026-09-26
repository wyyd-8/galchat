package com.me.galchat.memory;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class TopicModelCallTest {
    @Test void retrievalFromReplyThreadKeepsDelegateBehavior() throws Exception {
        var delegate = org.mockito.Mockito.mock(org.springframework.ai.embedding.EmbeddingModel.class);
        org.mockito.Mockito.when(delegate.embed("query")).thenReturn(new float[]{0.1F, 0.2F});
        var model = TopicModelCall.boundedEmbedding(delegate);
        float[] value = reactor.core.publisher.Mono.fromCallable(() -> model.embed("query"))
                .subscribeOn(reactor.core.scheduler.Schedulers.parallel()).toFuture().get(2, TimeUnit.SECONDS);
        assertThat(value).containsExactly(0.1F, 0.2F);
    }

    @Test void timedOutModelCannotContinueIntoArchivalWrites() throws Exception {
        var interrupted = new CountDownLatch(1);
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> {
            TopicModelCall.read(() -> {
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return "late response";
            }, Duration.ofMillis(100));
            writes.incrementAndGet();
        }).isInstanceOf(IllegalStateException.class);
        assertThat(writes.get()).isZero();
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    }
}

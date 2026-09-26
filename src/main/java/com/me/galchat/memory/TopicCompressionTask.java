package com.me.galchat.memory;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** 一轮回复与归档共持会话锁。取消输出也必须等后台写入结束才能解锁。 */
@Slf4j
public final class TopicCompressionTask {
    public static final String CONTEXT_KEY = "topicCompressionTask";
    private final Executor executor;
    private final Runnable unlock;
    private CompletableFuture<Runnable> prepared = CompletableFuture.completedFuture(null);
    private final CompletableFuture<Void> released = new CompletableFuture<>();
    private boolean submitted;
    private boolean finished;

    public TopicCompressionTask(Executor executor, Runnable unlock) {
        this.executor = executor;
        this.unlock = unlock;
    }

    public void submit(Supplier<Runnable> preparation) {
        CompletableFuture<Runnable> result;
        synchronized (this) {
            if (finished || submitted) {
                return;
            }
            submitted = true;
            result = new CompletableFuture<>();
            prepared = result;
        }
        try {
            executor.execute(() -> {
                try {
                    result.complete(preparation.get());
                } catch (Throwable e) {
                    log.warn("话题压缩失败，保留原窗口供后续重试", e);
                    result.complete(null);
                }
            });
        } catch (RuntimeException e) {
            log.warn("话题压缩任务提交失败，保留原窗口", e);
            result.complete(null);
        }
    }

    public <T> Flux<T> attach(Flux<T> replies) {
        return replies.concatWith(Mono.defer(() -> {
                    finish();
                    return Mono.fromFuture(released, true).then(Mono.<T>empty());
                }))
                .doOnError(error -> finish())
                .doFinally(signal -> {
                    if (signal == reactor.core.publisher.SignalType.CANCEL) {
                        finish();
                    }
                });
    }

    public void finish() {
        CompletableFuture<Runnable> result;
        synchronized (this) {
            if (finished) {
                return;
            }
            finished = true;
            result = prepared;
        }
        result.whenComplete((publish, error) -> {
            try {
                if (publish != null) {
                    publish.run();
                }
            } catch (RuntimeException e) {
                log.warn("话题边界发布失败，保留原窗口供后续重试", e);
            } finally {
                try {
                    unlock.run();
                } finally {
                    released.complete(null);
                }
            }
        });
    }
}

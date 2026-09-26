package com.me.galchat.memory;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.function.Supplier;

/** 仅包装无本地写入的模型调用。超时后调用链退出，不能继续向量写入或边界发布。 */
public final class TopicModelCall {
    private static final Scheduler IO = Schedulers.newBoundedElastic(4, 32, "topic-model");
    public static final Duration SCORE_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration SUMMARY_TIMEOUT = Duration.ofSeconds(120);

    private TopicModelCall() { }

    public static <T> T read(Supplier<T> call, Duration timeout) {
        return Mono.fromSupplier(call).subscribeOn(IO).block(timeout);
    }

    public static EmbeddingModel boundedEmbedding(EmbeddingModel delegate) {
        return new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                return read(() -> delegate.call(request), SUMMARY_TIMEOUT);
            }

            @Override
            public float[] embed(String text) {
                // 在线检索沿用原调用；只有归档的批量 embedding 需要本任务的超时保护。
                return delegate.embed(text);
            }

            @Override
            public float[] embed(Document document) {
                return read(() -> delegate.embed(document), SUMMARY_TIMEOUT);
            }

            @Override
            public String getEmbeddingContent(Document document) {
                return delegate.getEmbeddingContent(document);
            }

            @Override
            public int dimensions() {
                return read(delegate::dimensions, SUMMARY_TIMEOUT);
            }
        };
    }
}

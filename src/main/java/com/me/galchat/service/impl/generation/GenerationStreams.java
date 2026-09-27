package com.me.galchat.service.impl.generation;

import com.me.galchat.exception.UserRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Shared lifecycle for detached, replayable generations. Only outcome markers outlive completion. */
public final class GenerationStreams<C, E> {
    public interface Events<E> {
        E sequence(E event, long sequence);
        boolean failed(E event);
        E failure(Throwable error);
        E completion(boolean failed);
        default E terminal() { return completion(false); }
        default E caughtUp() { return null; }
    }

    private record Key<C>(C conversation, String requestId) { }
    private record Frame<E>(long sequence, E event) { }
    private static final class Completion {
        final boolean failed;
        Completion(boolean failed) { this.failed = failed; }
    }
    private final Map<Key<C>, Entry> entries = new HashMap<>();
    private final Map<C, Key<C>> active = new HashMap<>();
    private final Map<Key<C>, Completion> completed = new HashMap<>();
    private final Duration retention;

    public GenerationStreams(Duration retention) { this.retention = retention; }

    public Flux<E> start(C conversation, String requestId, Flux<E> source, Events<E> protocol) {
        Key<C> key = new Key<>(conversation, requestId);
        Entry entry;
        synchronized (this) {
            Completion marker = completed.get(key);
            if (marker != null) return Flux.just(protocol.completion(marker.failed));
            Entry existing = entries.get(key);
            if (existing != null) return existing.events(0, protocol);
            if (active.containsKey(conversation)) {
                throw new UserRequestException("当前会话正在生成回复，请稍后再试");
            }
            entry = new Entry();
            entries.put(key, entry);
            active.put(conversation, key);
        }
        // Subscribe outside the registry monitor: model work may block or complete synchronously.
        source.contextCapture().subscribe(event -> entry.emit(event, protocol), error -> {
            entry.failed = true;
            finish(key, entry, protocol, protocol.failure(error));
        }, () -> finish(key, entry, protocol, entry.failed ? null : protocol.terminal()));
        return entry.events(0, protocol);
    }

    /** Null means missing/expired; the caller supplies its domain-specific recovery policy. */
    public synchronized Flux<E> resume(C conversation, String requestId, long after, Events<E> protocol) {
        if (after < 0) throw new UserRequestException("生成事件序号无效");
        Key<C> key = new Key<>(conversation, requestId);
        Entry entry = entries.get(key);
        if (entry != null) return entry.events(after, protocol);
        Completion marker = completed.get(key);
        return marker == null ? null : Flux.just(protocol.completion(marker.failed));
    }

    public synchronized void evict(Predicate<C> matches) {
        entries.keySet().removeIf(key -> matches.test(key.conversation()));
        completed.keySet().removeIf(key -> matches.test(key.conversation()));
    }

    private void finish(Key<C> key, Entry entry, Events<E> protocol, E terminal) {
        Completion marker = new Completion(entry.failed);
        synchronized (this) {
            // A history mutation may have invalidated this entry before its last callback.
            if (entries.remove(key, entry)) completed.put(key, marker);
            active.remove(key.conversation(), key);
        }
        if (terminal != null) entry.emit(terminal, protocol);
        entry.sink.tryEmitComplete();
        // This callback captures no Entry, source publisher, reply or error trace.
        Mono.delay(retention).subscribe(ignored -> removeMarker(key, marker));
    }

    private synchronized void removeMarker(Key<C> key, Completion marker) {
        completed.remove(key, marker);
    }

    private final class Entry {
        final Sinks.Many<Frame<E>> sink = Sinks.many().replay().all();
        long sequence;
        boolean failed;
        synchronized void emit(E event, Events<E> protocol) {
            failed |= protocol.failed(event);
            long next = ++sequence;
            sink.tryEmitNext(new Frame<>(next, protocol.sequence(event, next)));
        }
        Flux<E> events(long after, Events<E> protocol) {
            return Flux.defer(() -> {
                long boundary;
                synchronized (this) { boundary = sequence; }
                E caughtUp = protocol.caughtUp();
                return Flux.concat(
                        sink.asFlux().take(boundary).filter(frame -> frame.sequence() > after).map(Frame::event),
                        caughtUp == null ? Flux.empty() : Flux.just(caughtUp),
                        sink.asFlux().skip(Math.max(boundary, after)).map(Frame::event));
            });
        }
    }
}

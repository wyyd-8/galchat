package com.me.galchat.constant;

import java.time.Duration;

public final class WebSocketConstant {

    public static final String MESSAGE_FRAGMENT = "fragment";
    public static final String MESSAGE_TYPING = "typing";

    public static final String TRIGGER_BERT = "bert";
    public static final String TRIGGER_FALLBACK = "fallback";
    public static final Duration BERT_TRIGGER_DELAY = Duration.ofMillis(700);
    public static final Duration FALLBACK_TRIGGER_DELAY = Duration.ofSeconds(3);

    private WebSocketConstant() {
    }
}

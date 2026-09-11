package com.me.galchat.utils;

import io.micrometer.context.ContextRegistry;
import reactor.core.publisher.Hooks;

public class CurrentHolder {

    private static final String CONTEXT_KEY = CurrentHolder.class.getName();
    private static final ThreadLocal<Integer> CURRENT_LOCAL = new ThreadLocal<>();

    static {
        ContextRegistry.getInstance().registerThreadLocalAccessor(
                CONTEXT_KEY, CURRENT_LOCAL);
        Hooks.enableAutomaticContextPropagation();
    }

    public static void setCurrentId(Integer employeeId) {
        CURRENT_LOCAL.set(employeeId);
    }

    public static Integer getCurrentId() {
        return CURRENT_LOCAL.get();
    }

    public static void remove() {
        CURRENT_LOCAL.remove();
    }
}

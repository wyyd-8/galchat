package com.me.galchat.groupchat.runtime.trpg;

public class DecisionActionStreamParser {

    private static final String DECISION_OPEN = "<decision>";
    private static final String DECISION_CLOSE = "</decision>";
    private static final String ACTION_OPEN = "<action>";
    private static final String ACTION_CLOSE = "</action>";

    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder decision = new StringBuilder();
    private final StringBuilder action = new StringBuilder();
    private State state = State.BEFORE_DECISION;

    public Delta accept(String chunk) {
        if (chunk != null) {
            pending.append(chunk);
        }
        StringBuilder decisionDelta = new StringBuilder();
        StringBuilder actionDelta = new StringBuilder();
        boolean decisionCompleted = false;
        boolean advanced;
        do {
            advanced = false;
            switch (state) {
                case BEFORE_DECISION -> {
                    stripLeadingWhitespace();
                    if (consumeOpening(DECISION_OPEN)) {
                        state = State.IN_DECISION;
                        advanced = true;
                    } else {
                        requirePossiblePrefix(DECISION_OPEN);
                    }
                }
                case IN_DECISION -> {
                    int closing = pending.indexOf(DECISION_CLOSE);
                    if (closing >= 0) {
                        appendContent(decision, decisionDelta,
                                pending.substring(0, closing));
                        pending.delete(0,
                                closing + DECISION_CLOSE.length());
                        requireContent(decision, "决策");
                        state = State.BETWEEN_PARTS;
                        decisionCompleted = true;
                        advanced = true;
                    } else {
                        emitSafeContent(
                                DECISION_CLOSE,
                                decision, decisionDelta);
                    }
                }
                case BETWEEN_PARTS -> {
                    stripLeadingWhitespace();
                    if (consumeOpening(ACTION_OPEN)) {
                        state = State.IN_ACTION;
                        advanced = true;
                    } else {
                        requirePossiblePrefix(ACTION_OPEN);
                    }
                }
                case IN_ACTION -> {
                    int closing = pending.indexOf(ACTION_CLOSE);
                    if (closing >= 0) {
                        appendContent(action, actionDelta,
                                pending.substring(0, closing));
                        pending.delete(0,
                                closing + ACTION_CLOSE.length());
                        requireContent(action, "行动");
                        state = State.COMPLETE;
                        advanced = true;
                    } else {
                        emitSafeContent(
                                ACTION_CLOSE, action, actionDelta);
                    }
                }
                case COMPLETE -> {
                    if (!pending.toString().isBlank()) {
                        fail("行动结束标签之后存在额外正文");
                    }
                    pending.setLength(0);
                }
            }
        } while (advanced);
        return new Delta(
                decisionDelta.toString(),
                actionDelta.toString(),
                decisionCompleted);
    }

    public void finish() {
        accept(null);
        if (state == State.IN_ACTION
                && ACTION_CLOSE.startsWith(pending.toString())) {
            pending.append(ACTION_CLOSE,
                    pending.length(), ACTION_CLOSE.length());
            accept(null);
        }
        if (state != State.COMPLETE) {
            fail("决策—行动输出不完整");
        }
    }

    public String decision() {
        return decision.toString();
    }

    public String action() {
        return action.toString();
    }

    private boolean consumeOpening(String token) {
        if (pending.length() < token.length()
                || !pending.toString().startsWith(token)) {
            return false;
        }
        pending.delete(0, token.length());
        return true;
    }

    private void requirePossiblePrefix(String token) {
        if (pending.isEmpty()
                || token.startsWith(pending.toString())) {
            return;
        }
        fail("决策—行动标签缺失或顺序错误");
    }

    private void emitSafeContent(
            String closingToken,
            StringBuilder target,
            StringBuilder delta) {
        int retained = longestTokenPrefixSuffix(
                pending, closingToken);
        int emittedLength = pending.length() - retained;
        if (emittedLength <= 0) {
            return;
        }
        String emitted = pending.substring(0, emittedLength);
        appendContent(target, delta, emitted);
        pending.delete(0, emittedLength);
    }

    private int longestTokenPrefixSuffix(
            StringBuilder value, String token) {
        int maximum = Math.min(value.length(), token.length() - 1);
        for (int length = maximum; length > 0; length--) {
            String suffix = value.substring(value.length() - length);
            if (token.startsWith(suffix)) {
                return length;
            }
        }
        return 0;
    }

    private void appendContent(
            StringBuilder target,
            StringBuilder delta,
            String content) {
        target.append(content);
        delta.append(content);
    }

    private void stripLeadingWhitespace() {
        int index = 0;
        while (index < pending.length()
                && Character.isWhitespace(pending.charAt(index))) {
            index++;
        }
        if (index > 0) {
            pending.delete(0, index);
        }
    }

    private void requireContent(
            StringBuilder content, String label) {
        if (content.toString().isBlank()) {
            fail(label + "内容不能为空");
        }
    }

    private void fail(String message) {
        throw new DecisionActionProtocolException(message);
    }

    private enum State {
        BEFORE_DECISION,
        IN_DECISION,
        BETWEEN_PARTS,
        IN_ACTION,
        COMPLETE
    }

    public record Delta(
            String decision,
            String action,
            boolean decisionCompleted) {
    }
}

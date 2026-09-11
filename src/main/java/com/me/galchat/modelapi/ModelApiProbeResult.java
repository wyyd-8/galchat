package com.me.galchat.modelapi;

public record ModelApiProbeResult(
        ModelApiTestStatus status,
        ModelApiCapability chat,
        ModelApiCapability streaming,
        ModelApiCapability toolCalling,
        ReasoningOutputStatus reasoningOutput,
        String code,
        String message) {
}

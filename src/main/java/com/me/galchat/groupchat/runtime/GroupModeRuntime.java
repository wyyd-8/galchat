package com.me.galchat.groupchat.runtime;

public interface GroupModeRuntime {

    String mode();

    GroupTurnPolicy turnPolicy();

    GroupContextPolicy contextPolicy();

    GroupAgentPolicy agentPolicy();
}

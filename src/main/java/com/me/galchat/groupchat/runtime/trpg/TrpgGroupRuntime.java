package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.groupchat.runtime.GroupAgentPolicy;
import com.me.galchat.groupchat.runtime.GroupContextPolicy;
import com.me.galchat.groupchat.runtime.GroupModeRuntime;
import com.me.galchat.groupchat.runtime.GroupTurnPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class TrpgGroupRuntime implements GroupModeRuntime {

    private final GroupTurnPolicy turnPolicy;
    private final GroupContextPolicy contextPolicy;
    private final GroupAgentPolicy agentPolicy;

    public TrpgGroupRuntime(@Qualifier("trpgGroupTurnPolicy") GroupTurnPolicy turnPolicy,
                            @Qualifier("trpgGroupContextPolicy") GroupContextPolicy contextPolicy,
                            @Qualifier("trpgGroupAgentPolicy") GroupAgentPolicy agentPolicy) {
        this.turnPolicy = turnPolicy;
        this.contextPolicy = contextPolicy;
        this.agentPolicy = agentPolicy;
    }

    @Override
    public String mode() {
        return GroupChatConstant.MODE_TRPG;
    }

    @Override
    public GroupTurnPolicy turnPolicy() {
        return turnPolicy;
    }

    @Override
    public GroupContextPolicy contextPolicy() {
        return contextPolicy;
    }

    @Override
    public GroupAgentPolicy agentPolicy() {
        return agentPolicy;
    }
}

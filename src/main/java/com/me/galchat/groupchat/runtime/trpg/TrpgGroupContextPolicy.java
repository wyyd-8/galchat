package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupContextMaterial;
import com.me.galchat.groupchat.runtime.GroupContextPolicy;
import com.me.galchat.service.impl.GroupContextAssembler;
import org.springframework.stereotype.Component;

@Component
public class TrpgGroupContextPolicy implements GroupContextPolicy {

    private final GroupContextAssembler contextAssembler;

    public TrpgGroupContextPolicy(GroupContextAssembler contextAssembler) {
        this.contextAssembler = contextAssembler;
    }

    @Override
    public void onTurnStarted(GroupConversation conversation, GroupChatMessage userMessage) {
        // 第一版直接保留 TRPG 公开原文；场景与战斗摘要在此插槽内扩展。
    }

    @Override
    public GroupContextMaterial load(GroupConversation conversation, GroupActionSpec action) {
        return new GroupContextMaterial(
                contextAssembler.assembleContext(conversation, action.actorId(), null));
    }
}

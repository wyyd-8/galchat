package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupContextMaterial;
import com.me.galchat.groupchat.runtime.GroupContextPolicy;
import com.me.galchat.service.impl.GroupContextAssembler;
import com.me.galchat.service.impl.TrpgModuleContextAssembler;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;

@Component
public class TrpgGroupContextPolicy implements GroupContextPolicy {

    private final GroupContextAssembler contextAssembler;
    private final TrpgModuleContextAssembler moduleContextAssembler;

    public TrpgGroupContextPolicy(
            GroupContextAssembler contextAssembler,
            TrpgModuleContextAssembler moduleContextAssembler) {
        this.contextAssembler = contextAssembler;
        this.moduleContextAssembler = moduleContextAssembler;
    }

    @Override
    public void onTurnStarted(GroupConversation conversation, GroupChatMessage userMessage) {
        // 第一版直接保留 TRPG 公开原文；场景与战斗摘要在此插槽内扩展。
    }

    @Override
    public GroupContextMaterial load(GroupConversation conversation, GroupActionSpec action) {
        java.util.List<Message> messages = new java.util.ArrayList<>();
        if (GroupChatConstant.ACTOR_KP.equals(action.actorType())) {
            messages.add(new SystemMessage(
                    moduleContextAssembler.formatKpContext(conversation)));
        }
        messages.addAll(contextAssembler.assembleContext(
                conversation, action.actor(), null));
        return new GroupContextMaterial(java.util.List.copyOf(messages));
    }
}

package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupContextMaterial;
import com.me.galchat.groupchat.runtime.GroupContextPolicy;
import com.me.galchat.service.impl.GroupContextAssembler;
import com.me.galchat.service.impl.TrpgAgentDecisionContextAssembler;
import com.me.galchat.service.impl.TrpgModuleContextAssembler;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TrpgGroupContextPolicy implements GroupContextPolicy {

    private final GroupContextAssembler contextAssembler;
    private final TrpgModuleContextAssembler moduleContextAssembler;
    private final TrpgAgentDecisionContextAssembler decisionContextAssembler;

    public TrpgGroupContextPolicy(
            GroupContextAssembler contextAssembler,
            TrpgModuleContextAssembler moduleContextAssembler) {
        this(contextAssembler, moduleContextAssembler, null);
    }

    @Autowired
    public TrpgGroupContextPolicy(
            GroupContextAssembler contextAssembler,
            TrpgModuleContextAssembler moduleContextAssembler,
            TrpgAgentDecisionContextAssembler decisionContextAssembler) {
        this.contextAssembler = contextAssembler;
        this.moduleContextAssembler = moduleContextAssembler;
        this.decisionContextAssembler = decisionContextAssembler;
    }

    @Override
    public void onTurnStarted(GroupConversation conversation, GroupChatMessage userMessage) {
        // 第一版直接保留 TRPG 公开原文；场景与战斗摘要在此插槽内扩展。
    }

    @Override
    public GroupContextMaterial load(GroupConversation conversation, GroupActionSpec action) {
        java.util.List<Message> messages = new java.util.ArrayList<>();
        if (GroupChatConstant.ACTOR_KP.equals(action.actorType())) {
            String moduleContext =
                    moduleContextAssembler.formatKpContext(conversation);
            if (StringUtils.hasText(moduleContext)) {
                messages.add(new SystemMessage(moduleContext));
            }
        }
        messages.addAll(contextAssembler.assembleContext(
                conversation, action.actor(), null));
        if (usesPrivateDecisionContext(action)
                && decisionContextAssembler != null) {
            String privateContext =
                    decisionContextAssembler.format(
                            conversation, action);
            if (StringUtils.hasText(privateContext)) {
                messages.add(new SystemMessage(privateContext));
            }
        }
        return new GroupContextMaterial(java.util.List.copyOf(messages));
    }

    private boolean usesPrivateDecisionContext(
            GroupActionSpec action) {
        if (!GroupChatConstant.ACTOR_CHARACTER.equals(
                action.actorType())) {
            return false;
        }
        return GroupChatConstant.ACTION_TRPG_SCENE.equals(
                action.actionType())
                || GroupChatConstant.ACTION_TRPG_COMBAT.equals(
                action.actionType())
                || GroupChatConstant.ACTION_COMBAT_ATTACK.equals(
                action.actionType())
                || GroupChatConstant.ACTION_COMBAT_DEFENSE.equals(
                action.actionType());
    }
}

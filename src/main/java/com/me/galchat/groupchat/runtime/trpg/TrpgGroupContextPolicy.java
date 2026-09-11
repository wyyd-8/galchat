package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupContextMaterial;
import com.me.galchat.groupchat.runtime.GroupContextPolicy;
import com.me.galchat.service.impl.trpg.TrpgAgentDecisionContextAssembler;
import com.me.galchat.service.impl.trpg.TrpgExplorationContextAssembler;
import com.me.galchat.service.impl.trpg.TrpgModuleContextAssembler;
import com.me.galchat.service.impl.trpg.TrpgSceneRuntimeContextAssembler;
import com.me.galchat.service.impl.trpg.TrpgGameTimeContextAssembler;
import com.me.galchat.service.impl.trpg.TrpgNpcContextSelector;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TrpgGroupContextPolicy implements GroupContextPolicy {

    private final TrpgModuleContextAssembler moduleContextAssembler;
    private final TrpgAgentDecisionContextAssembler decisionContextAssembler;
    private final TrpgExplorationContextAssembler
            explorationContextAssembler;
    private final TrpgSceneRuntimeContextAssembler
            sceneRuntimeContextAssembler;
    private final TrpgGameTimeContextAssembler gameTimeContextAssembler;
    private final TrpgNpcContextSelector npcContextSelector;

    @Autowired
    public TrpgGroupContextPolicy(
            TrpgModuleContextAssembler moduleContextAssembler,
            TrpgAgentDecisionContextAssembler decisionContextAssembler,
            TrpgExplorationContextAssembler
                    explorationContextAssembler,
            TrpgSceneRuntimeContextAssembler
                    sceneRuntimeContextAssembler,
            TrpgGameTimeContextAssembler gameTimeContextAssembler,
            TrpgNpcContextSelector npcContextSelector) {
        this.moduleContextAssembler = moduleContextAssembler;
        this.decisionContextAssembler = decisionContextAssembler;
        this.explorationContextAssembler =
                explorationContextAssembler;
        this.sceneRuntimeContextAssembler =
                sceneRuntimeContextAssembler;
        this.gameTimeContextAssembler = gameTimeContextAssembler;
        this.npcContextSelector = npcContextSelector;
    }

    public TrpgGroupContextPolicy(
            TrpgModuleContextAssembler moduleContextAssembler,
            TrpgAgentDecisionContextAssembler decisionContextAssembler,
            TrpgExplorationContextAssembler explorationContextAssembler,
            TrpgSceneRuntimeContextAssembler sceneRuntimeContextAssembler,
            TrpgGameTimeContextAssembler gameTimeContextAssembler) {
        this(moduleContextAssembler, decisionContextAssembler,
                explorationContextAssembler, sceneRuntimeContextAssembler,
                gameTimeContextAssembler, null);
    }

    @Override
    public void onTurnStarted(GroupConversation conversation, GroupChatMessage userMessage) {
        // 第一版直接保留 TRPG 公开原文；场景与战斗摘要在此插槽内扩展。
    }

    @Override
    public GroupContextMaterial load(GroupConversation conversation, GroupActionSpec action) {
        java.util.List<Message> messages = new java.util.ArrayList<>();
        java.util.Set<Long> currentSceneInvestigatorIds =
                java.util.Set.of();
        String gameTime = gameTimeContextAssembler.format(
                conversation.getId());
        if (StringUtils.hasText(gameTime)) {
            messages.add(new SystemMessage(gameTime));
        }
        if (GroupChatConstant.ACTOR_KP.equals(action.actorType())) {
            String moduleContext =
                    moduleContextAssembler.formatKpContext(conversation);
            if (StringUtils.hasText(moduleContext)) {
                messages.add(new SystemMessage(moduleContext));
            }
            if (!GroupChatConstant.ACTION_TRPG_SCENE_SELECTION
                    .equals(action.actionType())) {
                TrpgSceneRuntimeContextAssembler.RuntimeContext
                        runtimeContext =
                        sceneRuntimeContextAssembler.assemble(
                                conversation, action);
                if (runtimeContext != null) {
                    if (StringUtils.hasText(runtimeContext.prompt())) {
                        messages.add(new SystemMessage(
                                runtimeContext.prompt()));
                    }
                    currentSceneInvestigatorIds =
                            runtimeContext.activeInvestigatorCharacterIds();
                }
            }
        }
        messages.addAll(explorationContextAssembler.assemble(
                conversation, action.actor()));
        if (usesPrivateDecisionContext(action)) {
            String privateContext =
                    decisionContextAssembler.format(
                            conversation, action);
            if (StringUtils.hasText(privateContext)) {
                messages.add(new SystemMessage(privateContext));
            }
        }
        java.util.Set<Long> relevantCharacterIds =
                GroupChatConstant.ACTOR_KP.equals(action.actorType())
                        && npcContextSelector != null
                        ? npcContextSelector.select(conversation, action)
                        : java.util.Set.of();
        return new GroupContextMaterial(
                java.util.List.copyOf(messages), relevantCharacterIds,
                currentSceneInvestigatorIds);
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

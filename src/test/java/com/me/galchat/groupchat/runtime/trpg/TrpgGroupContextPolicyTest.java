package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.service.impl.TrpgModuleContextAssembler;
import com.me.galchat.service.impl.TrpgAgentDecisionContextAssembler;
import com.me.galchat.service.impl.TrpgExplorationContextAssembler;
import com.me.galchat.service.impl.TrpgSceneRuntimeContextAssembler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgGroupContextPolicyTest {

    @Test
    void trpgContextUsesIntervalCompressedExplorationRecord() {
        TrpgExplorationContextAssembler explorationAssembler =
                mock(TrpgExplorationContextAssembler.class);
        TrpgGroupContextPolicy policy = new TrpgGroupContextPolicy(
                mock(TrpgModuleContextAssembler.class),
                mock(TrpgAgentDecisionContextAssembler.class),
                explorationAssembler,
                mock(TrpgSceneRuntimeContextAssembler.class));
        GroupConversation conversation =
                new GroupConversation().setId(7L);
        GroupActionSpec action = action(
                GroupChatConstant.ACTOR_CHARACTER, 9L);
        when(explorationAssembler.assemble(
                conversation, action.actor())).thenReturn(List.of(
                new org.springframework.ai.chat.messages.UserMessage(
                        "<context-summary>阁楼摘要</context-summary>")));

        assertThat(policy.load(conversation, action).messages())
                .extracting(message -> message.getText())
                .contains("<context-summary>阁楼摘要</context-summary>");
    }

    @Test
    void kpReceivesCurrentSceneAndRoundParticipants() {
        TrpgSceneRuntimeContextAssembler runtimeAssembler =
                mock(TrpgSceneRuntimeContextAssembler.class);
        GroupConversation conversation =
                new GroupConversation().setId(7L);
        GroupActionSpec kpAction = action(
                GroupChatConstant.ACTOR_KP, null);
        when(runtimeAssembler.format(
                conversation, kpAction)).thenReturn(
                "<current-scene-runtime>书房：艾琳</current-scene-runtime>");
        TrpgExplorationContextAssembler explorationAssembler =
                mock(TrpgExplorationContextAssembler.class);
        when(explorationAssembler.assemble(
                any(), any())).thenReturn(List.of());
        TrpgGroupContextPolicy policy = new TrpgGroupContextPolicy(
                mock(TrpgModuleContextAssembler.class),
                mock(TrpgAgentDecisionContextAssembler.class),
                explorationAssembler,
                runtimeAssembler);

        assertThat(policy.load(conversation, kpAction).messages())
                .extracting(message -> message.getText())
                .contains(
                        "<current-scene-runtime>书房：艾琳</current-scene-runtime>");
    }

    @Test
    void onlyKpReceivesPrivateModuleContext() {
        TrpgModuleContextAssembler moduleAssembler =
                mock(TrpgModuleContextAssembler.class);
        TrpgExplorationContextAssembler explorationAssembler =
                mock(TrpgExplorationContextAssembler.class);
        TrpgGroupContextPolicy policy = new TrpgGroupContextPolicy(
                moduleAssembler,
                mock(TrpgAgentDecisionContextAssembler.class),
                explorationAssembler,
                mock(TrpgSceneRuntimeContextAssembler.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setModuleId(3L);
        when(explorationAssembler.assemble(
                any(), any())).thenReturn(List.of());
        when(moduleAssembler.formatKpContext(conversation))
                .thenReturn("<module-global-context>幕后真相</module-global-context>");

        var kpContext = policy.load(conversation, action(
                GroupChatConstant.ACTOR_KP, null));
        var investigatorContext = policy.load(conversation, action(
                GroupChatConstant.ACTOR_CHARACTER, 9L));

        assertThat(kpContext.messages())
                .extracting(message -> message.getText())
                .anyMatch(text -> text.contains("幕后真相"));
        assertThat(investigatorContext.messages())
                .extracting(message -> message.getText())
                .noneMatch(text -> text.contains("幕后真相"));
    }

    @Test
    void onlyEligibleCharacterReceivesItsPrivateDecisionHistory() {
        TrpgAgentDecisionContextAssembler decisionAssembler =
                mock(TrpgAgentDecisionContextAssembler.class);
        TrpgExplorationContextAssembler explorationAssembler =
                mock(TrpgExplorationContextAssembler.class);
        TrpgGroupContextPolicy policy = new TrpgGroupContextPolicy(
                mock(TrpgModuleContextAssembler.class),
                decisionAssembler,
                explorationAssembler,
                mock(TrpgSceneRuntimeContextAssembler.class));
        GroupConversation conversation =
                new GroupConversation().setId(7L);
        GroupActionSpec sceneAction = action(
                GroupChatConstant.ACTOR_CHARACTER, 9L);
        GroupActionSpec selectionAction = new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE_SELECTION,
                GroupChatConstant.ACTOR_CHARACTER,
                9L, "scene-selection", "选景", 1, 1);
        when(explorationAssembler.assemble(
                any(), any())).thenReturn(List.of());
        when(decisionAssembler.format(
                conversation, sceneAction))
                .thenReturn("<private-decision-history>先前判断</private-decision-history>");

        var sceneContext = policy.load(
                conversation, sceneAction);
        var selectionContext = policy.load(
                conversation, selectionAction);
        var kpContext = policy.load(conversation, action(
                GroupChatConstant.ACTOR_KP, null));

        assertThat(sceneContext.messages())
                .extracting(message -> message.getText())
                .contains(
                        "<private-decision-history>先前判断</private-decision-history>");
        assertThat(selectionContext.messages())
                .extracting(message -> message.getText())
                .doesNotContain(
                        "<private-decision-history>先前判断</private-decision-history>");
        assertThat(kpContext.messages())
                .extracting(message -> message.getText())
                .doesNotContain(
                        "<private-decision-history>先前判断</private-decision-history>");
        verify(decisionAssembler).format(
                conversation, sceneAction);
        verify(decisionAssembler, never()).format(
                conversation, selectionAction);
    }

    private GroupActionSpec action(String actorType, Long actorId) {
        return new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE,
                actorType,
                actorId,
                "scene:21",
                "医院",
                1,
                1);
    }
}

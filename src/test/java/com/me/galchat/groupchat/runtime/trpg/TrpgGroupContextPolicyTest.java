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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgGroupContextPolicyTest {

    @Test
    void kpContextCarriesStructuredRelevantNpcIds() {
        com.me.galchat.service.impl.TrpgNpcContextSelector selector =
                mock(com.me.galchat.service.impl
                        .TrpgNpcContextSelector.class);
        TrpgExplorationContextAssembler explorationAssembler =
                mock(TrpgExplorationContextAssembler.class);
        when(explorationAssembler.assemble(any(), any()))
                .thenReturn(List.of());
        TrpgGroupContextPolicy policy = new TrpgGroupContextPolicy(
                mock(TrpgModuleContextAssembler.class),
                mock(TrpgAgentDecisionContextAssembler.class),
                explorationAssembler,
                mock(TrpgSceneRuntimeContextAssembler.class),
                mock(com.me.galchat.service.impl
                        .TrpgGameTimeContextAssembler.class),
                selector);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setModuleId(3L);
        GroupActionSpec action = action(GroupChatConstant.ACTOR_KP, null);
        when(selector.select(conversation, action))
                .thenReturn(Set.of(81L, 82L));

        assertThat(policy.load(conversation, action)
                .relevantCharacterIds())
                .containsExactlyInAnyOrder(81L, 82L);
    }

    @Test
    void trpgContextUsesIntervalCompressedExplorationRecord() {
        TrpgExplorationContextAssembler explorationAssembler =
                mock(TrpgExplorationContextAssembler.class);
        TrpgGroupContextPolicy policy = new TrpgGroupContextPolicy(
                mock(TrpgModuleContextAssembler.class),
                mock(TrpgAgentDecisionContextAssembler.class),
                explorationAssembler,
                mock(TrpgSceneRuntimeContextAssembler.class),
                mock(com.me.galchat.service.impl
                        .TrpgGameTimeContextAssembler.class));
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
    void kpAndInvestigatorReceiveLatestGameTimeContext() {
        com.me.galchat.service.impl.TrpgGameTimeContextAssembler
                timeAssembler = mock(com.me.galchat.service.impl
                .TrpgGameTimeContextAssembler.class);
        TrpgExplorationContextAssembler explorationAssembler =
                mock(TrpgExplorationContextAssembler.class);
        when(explorationAssembler.assemble(any(), any()))
                .thenReturn(List.of());
        when(timeAssembler.format(7L)).thenReturn(
                "<current-game-time day=\"2\" period=\"EVENING\">第二天 - 晚上</current-game-time>");
        TrpgGroupContextPolicy policy = new TrpgGroupContextPolicy(
                mock(TrpgModuleContextAssembler.class),
                mock(TrpgAgentDecisionContextAssembler.class),
                explorationAssembler,
                mock(TrpgSceneRuntimeContextAssembler.class),
                timeAssembler);
        GroupConversation conversation =
                new GroupConversation().setId(7L);

        var kp = policy.load(conversation, action(
                GroupChatConstant.ACTOR_KP, null));
        var investigator = policy.load(conversation, action(
                GroupChatConstant.ACTOR_CHARACTER, 9L));

        assertThat(kp.messages())
                .extracting(message -> message.getText())
                .contains("<current-game-time day=\"2\" period=\"EVENING\">第二天 - 晚上</current-game-time>");
        assertThat(investigator.messages())
                .extracting(message -> message.getText())
                .contains("<current-game-time day=\"2\" period=\"EVENING\">第二天 - 晚上</current-game-time>");
    }

    @Test
    void kpReceivesCurrentSceneAndRoundParticipants() {
        TrpgSceneRuntimeContextAssembler runtimeAssembler =
                mock(TrpgSceneRuntimeContextAssembler.class);
        GroupConversation conversation =
                new GroupConversation().setId(7L);
        GroupActionSpec kpAction = action(
                GroupChatConstant.ACTOR_KP, null);
        when(runtimeAssembler.assemble(
                conversation, kpAction)).thenReturn(
                new TrpgSceneRuntimeContextAssembler.RuntimeContext(
                        "<current-scene-runtime>书房：艾琳</current-scene-runtime>",
                        Set.of(72L)));
        TrpgExplorationContextAssembler explorationAssembler =
                mock(TrpgExplorationContextAssembler.class);
        when(explorationAssembler.assemble(
                any(), any())).thenReturn(List.of());
        TrpgGroupContextPolicy policy = new TrpgGroupContextPolicy(
                mock(TrpgModuleContextAssembler.class),
                mock(TrpgAgentDecisionContextAssembler.class),
                explorationAssembler,
                runtimeAssembler,
                mock(com.me.galchat.service.impl
                        .TrpgGameTimeContextAssembler.class));

        var context = policy.load(conversation, kpAction);
        assertThat(context.messages())
                .extracting(message -> message.getText())
                .contains(
                        "<current-scene-runtime>书房：艾琳</current-scene-runtime>");
        assertThat(context.currentSceneInvestigatorIds())
                .containsExactly(72L);
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
                mock(TrpgSceneRuntimeContextAssembler.class),
                mock(com.me.galchat.service.impl
                        .TrpgGameTimeContextAssembler.class));
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
                mock(TrpgSceneRuntimeContextAssembler.class),
                mock(com.me.galchat.service.impl
                        .TrpgGameTimeContextAssembler.class));
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

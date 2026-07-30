package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.groupchat.runtime.GroupContextMaterial;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.service.impl.CharacterCardContextFormatter;
import com.me.galchat.service.impl.GroupContextAssembler;
import com.me.galchat.tool.KpDiceTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrpgGroupAgentPolicyTest {

    @Test
    void kpUsesWorldPromptAllCardsAndDirectDiceInstructions() {
        ChatClient client = mock(ChatClient.class);
        GroupContextAssembler contextAssembler = mock(GroupContextAssembler.class);
        ICharacterCardService cardService = mock(ICharacterCardService.class);
        CharacterCardContextFormatter formatter = new CharacterCardContextFormatter();
        KpDiceTools kpDiceTools = mock(KpDiceTools.class);
        com.me.galchat.tool.KpModuleTools kpModuleTools =
                mock(com.me.galchat.tool.KpModuleTools.class);
        com.me.galchat.tool.KpSceneTools kpSceneTools =
                mock(com.me.galchat.tool.KpSceneTools.class);
        com.me.galchat.tool.KpRunTools kpRunTools =
                mock(com.me.galchat.tool.KpRunTools.class);
        com.me.galchat.service.impl.TrpgContextWindowService contextWindowService =
                mock(com.me.galchat.service.impl.TrpgContextWindowService.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setWorldId(2L).setUserWorldId(5L);
        GroupActorRef kp = new GroupActorRef(GroupChatConstant.ACTOR_KP, null);
        when(contextAssembler.baseSystemPrompt(conversation, kp)).thenReturn("仅世界提示词");
        when(cardService.listDiceCharacters(7L)).thenReturn(List.of(card("林恩", null)));

        TrpgGroupAgentPolicy policy =
                new TrpgGroupAgentPolicy(
                        client, contextAssembler, cardService, formatter, kpDiceTools,
                        mock(com.me.galchat.tool.TrpgSceneSelectionTools.class),
                        mock(com.me.galchat.tool.KpSceneSelectionTools.class),
                        kpModuleTools,
                        mock(com.me.galchat.tool.InvestigatorSceneTools.class),
                        kpSceneTools,
                        kpRunTools,
                        contextWindowService,
                        mock(com.me.galchat.service.impl.TrpgInvestigatorContextAssembler.class));
        var invocation = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_TRPG_SCENE,
                        GroupChatConstant.ACTOR_KP,
                        null,
                        "scene:1",
                        "地下室",
                        1,
                        1),
                new GroupContextMaterial(List.of()));

        assertThat(invocation.prompt().getInstructions().getFirst().getText())
                .contains("仅世界提示词")
                .contains("林恩")
                .contains("KP不是可见的调查员")
                .contains("最多调用一个会改变状态的掷骰工具")
                .contains("本次响应会暂停")
                .contains("恢复同一步骤");
        assertThat(policy.actorName(5L, kp)).isEqualTo("KP");
        assertThat(invocation.tools())
                .containsExactly(
                        kpDiceTools, kpModuleTools,
                        kpSceneTools, kpRunTools);
        org.mockito.Mockito.verify(contextWindowService)
                .recordPrompt(
                        org.mockito.ArgumentMatchers.eq(7L),
                        org.mockito.ArgumentMatchers.eq(
                                invocation.prompt().getInstructions()));
    }

    @Test
    void investigatorSelectionActionReceivesSelectionTool() {
        ICharacterCardService cardService =
                mock(ICharacterCardService.class);
        when(cardService.listDiceCharacters(7L)).thenReturn(List.of());
        GroupContextAssembler contextAssembler =
                mock(GroupContextAssembler.class);
        com.me.galchat.service.impl.TrpgInvestigatorContextAssembler
                investigatorAssembler = mock(
                com.me.galchat.service.impl.TrpgInvestigatorContextAssembler.class);
        com.me.galchat.tool.TrpgSceneSelectionTools selectionTools =
                mock(com.me.galchat.tool.TrpgSceneSelectionTools.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setWorldId(2L).setUserWorldId(5L);
        GroupActionSpec selectionAction = new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE_SELECTION,
                GroupChatConstant.ACTOR_CHARACTER,
                9L,
                "scene-selection",
                "选景",
                1,
                2);
        when(contextAssembler.actorName(
                5L, selectionAction.actor())).thenReturn("林登");
        when(investigatorAssembler.format(
                conversation, selectionAction))
                .thenReturn("仅角色性格和裁剪卡");
        TrpgGroupAgentPolicy policy = new TrpgGroupAgentPolicy(
                mock(ChatClient.class),
                contextAssembler,
                cardService,
                new CharacterCardContextFormatter(),
                mock(KpDiceTools.class),
                selectionTools,
                mock(com.me.galchat.tool.KpSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpModuleTools.class),
                mock(com.me.galchat.tool.InvestigatorSceneTools.class),
                mock(com.me.galchat.tool.KpSceneTools.class),
                mock(com.me.galchat.tool.KpRunTools.class),
                mock(com.me.galchat.service.impl.TrpgContextWindowService.class),
                investigatorAssembler);

        var invocation = policy.prepare(
                conversation,
                selectionAction,
                new GroupContextMaterial(List.of()));

        assertThat(invocation.tools()).containsExactly(selectionTools);
        assertThat(invocation.prompt().getInstructions().getFirst().getText())
                .contains("仅角色性格和裁剪卡");
        assertThat(invocation.prompt().getInstructions().getLast().getText())
                .contains("编号Map")
                .contains("推荐优先选择不同地点")
                .contains("selectExplorationScene")
                .doesNotContain("<decision>")
                .doesNotContain("<action>");
        org.mockito.Mockito.verify(
                contextAssembler, org.mockito.Mockito.never())
                .baseSystemPrompt(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void kpSelectionActionMustPublishTodaysLocationNames() {
        ICharacterCardService cardService =
                mock(ICharacterCardService.class);
        when(cardService.listDiceCharacters(7L)).thenReturn(List.of());
        com.me.galchat.tool.KpModuleTools moduleTools =
                mock(com.me.galchat.tool.KpModuleTools.class);
        com.me.galchat.tool.KpRunTools runTools =
                mock(com.me.galchat.tool.KpRunTools.class);
        com.me.galchat.tool.KpSceneSelectionTools selectionTools =
                mock(com.me.galchat.tool.KpSceneSelectionTools.class);
        TrpgGroupAgentPolicy policy = new TrpgGroupAgentPolicy(
                mock(ChatClient.class),
                mock(GroupContextAssembler.class),
                cardService,
                new CharacterCardContextFormatter(),
                mock(KpDiceTools.class),
                mock(com.me.galchat.tool.TrpgSceneSelectionTools.class),
                selectionTools,
                moduleTools,
                mock(com.me.galchat.tool.InvestigatorSceneTools.class),
                mock(com.me.galchat.tool.KpSceneTools.class),
                runTools,
                mock(com.me.galchat.service.impl.TrpgContextWindowService.class),
                mock(com.me.galchat.service.impl.TrpgInvestigatorContextAssembler.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setWorldId(2L).setUserWorldId(5L);

        var invocation = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_TRPG_SCENE_SELECTION,
                        GroupChatConstant.ACTOR_KP,
                        null,
                        "scene-selection",
                        "选景",
                        1,
                        1),
                new GroupContextMaterial(List.of()));

        assertThat(invocation.prompt().getInstructions().getLast().getText())
                .contains("当天能够探索")
                .contains("publishExplorationScenes")
                .contains("不得输出地点ID");
        assertThat(invocation.tools())
                .containsExactly(
                        selectionTools, moduleTools, runTools);
    }

    @Test
    void investigatorSceneActionReceivesOnlySceneLifecycleTool() {
        ICharacterCardService cardService =
                mock(ICharacterCardService.class);
        when(cardService.listDiceCharacters(7L)).thenReturn(List.of());
        com.me.galchat.tool.InvestigatorSceneTools sceneTools =
                mock(com.me.galchat.tool.InvestigatorSceneTools.class);
        TrpgGroupAgentPolicy policy = new TrpgGroupAgentPolicy(
                mock(ChatClient.class),
                mock(GroupContextAssembler.class),
                cardService,
                new CharacterCardContextFormatter(),
                mock(KpDiceTools.class),
                mock(com.me.galchat.tool.TrpgSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpModuleTools.class),
                sceneTools,
                mock(com.me.galchat.tool.KpSceneTools.class),
                mock(com.me.galchat.tool.KpRunTools.class),
                mock(com.me.galchat.service.impl.TrpgContextWindowService.class),
                mock(com.me.galchat.service.impl.TrpgInvestigatorContextAssembler.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setWorldId(2L).setUserWorldId(5L);

        var invocation = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_TRPG_SCENE,
                        GroupChatConstant.ACTOR_CHARACTER,
                        9L,
                        "scene:1",
                        "场景",
                        1,
                        1),
                new GroupContextMaterial(List.of()));

        assertThat(invocation.tools()).containsExactly(sceneTools);
        assertThat(invocation.prompt().getInstructions().getLast().getText())
                .contains("<decision>")
                .contains("</decision>")
                .contains("<action>")
                .contains("</action>")
                .contains("决策必须先于行动");
    }

    @Test
    void investigatorCombatActionUsesDecisionActionProtocolWithoutTools() {
        ICharacterCardService cardService =
                mock(ICharacterCardService.class);
        when(cardService.listDiceCharacters(7L)).thenReturn(List.of());
        TrpgGroupAgentPolicy policy = new TrpgGroupAgentPolicy(
                mock(ChatClient.class),
                mock(GroupContextAssembler.class),
                cardService,
                new CharacterCardContextFormatter(),
                mock(KpDiceTools.class),
                mock(com.me.galchat.tool.TrpgSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpModuleTools.class),
                mock(com.me.galchat.tool.InvestigatorSceneTools.class),
                mock(com.me.galchat.tool.KpSceneTools.class),
                mock(com.me.galchat.tool.KpRunTools.class),
                mock(com.me.galchat.service.impl.TrpgContextWindowService.class),
                mock(com.me.galchat.service.impl.TrpgInvestigatorContextAssembler.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setWorldId(2L).setUserWorldId(5L);

        var invocation = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_TRPG_COMBAT,
                        GroupChatConstant.ACTOR_CHARACTER,
                        9L,
                        "combat:1",
                        "战斗",
                        1,
                        1),
                new GroupContextMaterial(List.of()));

        assertThat(invocation.tools()).isEmpty();
        assertThat(invocation.prompt().getInstructions().getLast().getText())
                .contains("<decision>")
                .contains("<action>");
    }

    private CocDiceCharacterVO card(String name, Long participantId) {
        return new CocDiceCharacterVO(
                71L, participantId, name, Map.of("CON", 55),
                10, 10, 54, 60, 55, 0,
                false, false, false, false,
                false, null, null);
    }
}

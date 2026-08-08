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
import com.me.galchat.tool.KpPushedCheckTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        KpPushedCheckTools kpPushedCheckTools =
                mock(KpPushedCheckTools.class);
        com.me.galchat.tool.KpModuleTools kpModuleTools =
                mock(com.me.galchat.tool.KpModuleTools.class);
        com.me.galchat.tool.KpSkillRuleTools kpSkillRuleTools =
                mock(com.me.galchat.tool.KpSkillRuleTools.class);
        com.me.galchat.tool.KpSceneTools kpSceneTools =
                mock(com.me.galchat.tool.KpSceneTools.class);
        com.me.galchat.tool.KpRunTools kpRunTools =
                mock(com.me.galchat.tool.KpRunTools.class);
        com.me.galchat.tool.KpCombatTools kpCombatTools =
                mock(com.me.galchat.tool.KpCombatTools.class);
        com.me.galchat.service.impl.TrpgContextWindowService contextWindowService =
                mock(com.me.galchat.service.impl.TrpgContextWindowService.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setWorldId(2L).setUserWorldId(5L);
        GroupActorRef kp = new GroupActorRef(GroupChatConstant.ACTOR_KP, null);
        when(contextAssembler.baseSystemPrompt(conversation, kp)).thenReturn("仅世界提示词");
        when(cardService.listDiceCharacters(7L)).thenReturn(List.of(
                card("林恩", null),
                card("艾琳", 9L),
                npcCard("食尸鬼")));

        TrpgGroupAgentPolicy policy =
                new TrpgGroupAgentPolicy(
                        client, contextAssembler, cardService, formatter, kpDiceTools,
                        kpPushedCheckTools,
                        mock(com.me.galchat.tool.TrpgSceneSelectionTools.class),
                        mock(com.me.galchat.tool.KpSceneSelectionTools.class),
                        kpModuleTools,
                        kpSkillRuleTools,
                        mock(com.me.galchat.tool.InvestigatorSceneTools.class),
                        kpSceneTools,
                        kpRunTools,
                        contextWindowService,
                        mock(com.me.galchat.service.impl.TrpgInvestigatorContextAssembler.class),
                        kpCombatTools,
                        mock(com.me.galchat.service.impl
                                .TrpgCombatLifecycleService.class),
                        mock(com.me.galchat.tool.KpChildSceneTools.class),
                        mock(com.me.galchat.tool
                                .KpWaitingInvestigatorTools.class),
                        mock(com.me.galchat.service.impl
                                .TrpgChildSceneCommandService.class));
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
                .contains("<investigator-card name=\"林恩\"")
                .contains("<investigator-card name=\"艾琳\"")
                .contains("<npc-card name=\"食尸鬼\"")
                .doesNotContain("<investigator-card name=\"食尸鬼\"")
                .contains("基础游戏循环")
                .contains("<kp-skill-index>")
                .contains("- 侦查：")
                .contains("- 格斗:斧：")
                .contains("skillNames只能使用下列准确技能名")
                .doesNotContain("完整战斗循环")
                .contains("KP不是可见的调查员")
                .contains("最多调用一个会改变状态的掷骰工具")
                .contains("本次响应会暂停")
                .contains("恢复同一步骤");
        assertThat(invocation.prompt().getInstructions().getLast().getText())
                .contains("共同场景提案")
                .contains("统一裁定")
                .contains("不要按调查员逐条机械回复");
        assertThat(policy.actorName(5L, kp)).isEqualTo("KP");
        assertThat(invocation.tools())
                .containsExactly(
                        kpDiceTools, kpPushedCheckTools, kpModuleTools,
                        kpSkillRuleTools, kpSceneTools, kpRunTools,
                        kpCombatTools);
        assertThat(exposedToolNames(invocation.tools()))
                .contains("requestPushedCheck", "readSkillRules");

        var combatInvocation = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_COMBAT_ADJUDICATE,
                        GroupChatConstant.ACTOR_KP,
                        null,
                        "combat:1",
                        "战斗裁定",
                        1,
                        1),
                new GroupContextMaterial(List.of()));
        assertThat(exposedToolNames(combatInvocation.tools()))
                .contains("readSkillRules")
                .doesNotContain("requestPushedCheck");
        assertThat(combatInvocation.prompt().getInstructions()
                .getFirst().getText())
                .contains("基础游戏循环")
                .contains("完整战斗循环");

        var npcAttack = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_COMBAT_ATTACK,
                        GroupChatConstant.ACTOR_KP,
                        null,
                        71L,
                        "combat:1",
                        "战斗攻击",
                        1,
                        1),
                new GroupContextMaterial(List.of()));
        assertThat(npcAttack.prompt().getInstructions().getLast().getText())
                .contains("在规则允许的范围内")
                .contains("不要尝试攻击")
                .contains("昏迷")
                .contains("濒死");
        org.mockito.Mockito.verify(contextWindowService)
                .recordPrompt(
                        org.mockito.ArgumentMatchers.eq(7L),
                        org.mockito.ArgumentMatchers.eq(
                                invocation.prompt().getInstructions()));
    }

    @Test
    void kpSceneOffersChildAndResumeToolsOnlyWhenTheirRuntimeConditionsHold() {
        ICharacterCardService cardService = mock(ICharacterCardService.class);
        when(cardService.listDiceCharacters(7L)).thenReturn(List.of());
        var childTools = mock(com.me.galchat.tool.KpChildSceneTools.class);
        var waitingTools = mock(
                com.me.galchat.tool.KpWaitingInvestigatorTools.class);
        var commandService = mock(
                com.me.galchat.service.impl.TrpgChildSceneCommandService.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setWorldId(2L).setUserWorldId(5L);
        when(commandService.canStartChildScene(conversation))
                .thenReturn(true);
        when(commandService.hasWaitingInvestigators(conversation))
                .thenReturn(true);
        TrpgGroupAgentPolicy policy = new TrpgGroupAgentPolicy(
                mock(ChatClient.class),
                mock(GroupContextAssembler.class),
                cardService,
                new CharacterCardContextFormatter(),
                mock(KpDiceTools.class),
                mock(KpPushedCheckTools.class),
                mock(com.me.galchat.tool.TrpgSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpModuleTools.class),
                mock(com.me.galchat.tool.KpSkillRuleTools.class),
                mock(com.me.galchat.tool.InvestigatorSceneTools.class),
                mock(com.me.galchat.tool.KpSceneTools.class),
                mock(com.me.galchat.tool.KpRunTools.class),
                mock(com.me.galchat.service.impl.TrpgContextWindowService.class),
                mock(com.me.galchat.service.impl.TrpgInvestigatorContextAssembler.class),
                mock(com.me.galchat.tool.KpCombatTools.class),
                mock(com.me.galchat.service.impl
                        .TrpgCombatLifecycleService.class),
                childTools,
                waitingTools,
                commandService);

        var invocation = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_TRPG_SCENE,
                        GroupChatConstant.ACTOR_KP,
                        null,
                        "scene:1",
                        "教堂",
                        1,
                        1),
                new GroupContextMaterial(List.of()));

        assertThat(invocation.tools())
                .contains(childTools, waitingTools);
        assertThat(invocation.prompt().getInstructions().getLast().getText())
                .contains("分头行动")
                .contains("不会加载更多模组信息")
                .contains("一起行动时应保持在主场景");
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
                mock(KpPushedCheckTools.class),
                selectionTools,
                mock(com.me.galchat.tool.KpSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpModuleTools.class),
                mock(com.me.galchat.tool.KpSkillRuleTools.class),
                mock(com.me.galchat.tool.InvestigatorSceneTools.class),
                mock(com.me.galchat.tool.KpSceneTools.class),
                mock(com.me.galchat.tool.KpRunTools.class),
                mock(com.me.galchat.service.impl.TrpgContextWindowService.class),
                investigatorAssembler,
                mock(com.me.galchat.tool.KpCombatTools.class),
                mock(com.me.galchat.service.impl
                        .TrpgCombatLifecycleService.class),
                mock(com.me.galchat.tool.KpChildSceneTools.class),
                mock(com.me.galchat.tool
                        .KpWaitingInvestigatorTools.class),
                mock(com.me.galchat.service.impl
                        .TrpgChildSceneCommandService.class));

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
                mock(KpPushedCheckTools.class),
                mock(com.me.galchat.tool.TrpgSceneSelectionTools.class),
                selectionTools,
                moduleTools,
                mock(com.me.galchat.tool.KpSkillRuleTools.class),
                mock(com.me.galchat.tool.InvestigatorSceneTools.class),
                mock(com.me.galchat.tool.KpSceneTools.class),
                runTools,
                mock(com.me.galchat.service.impl.TrpgContextWindowService.class),
                mock(com.me.galchat.service.impl.TrpgInvestigatorContextAssembler.class),
                mock(com.me.galchat.tool.KpCombatTools.class),
                mock(com.me.galchat.service.impl
                        .TrpgCombatLifecycleService.class),
                mock(com.me.galchat.tool.KpChildSceneTools.class),
                mock(com.me.galchat.tool
                        .KpWaitingInvestigatorTools.class),
                mock(com.me.galchat.service.impl
                        .TrpgChildSceneCommandService.class));
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
                .contains("不得输出地点ID")
                .contains("首次选景")
                .contains("targetDay")
                .contains("targetPeriod")
                .contains("保持当前时间")
                .contains("内部判断")
                .contains("不要输出时间推进原因");
        assertThat(invocation.tools())
                .containsExactly(
                        selectionTools, moduleTools, runTools);
    }

    @Test
    void investigatorSceneActionReceivesOnlySceneLifecycleTool() {
        ICharacterCardService cardService =
                mock(ICharacterCardService.class);
        when(cardService.listDiceCharacters(7L)).thenReturn(List.of(
                card("林恩", null),
                card("艾琳", 9L),
                npcCard("食尸鬼")));
        com.me.galchat.service.impl.TrpgInvestigatorContextAssembler
                investigatorAssembler = mock(
                com.me.galchat.service.impl
                        .TrpgInvestigatorContextAssembler.class);
        when(investigatorAssembler.format(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn("<controlled-investigator>艾琳</controlled-investigator>");
        com.me.galchat.tool.InvestigatorSceneTools sceneTools =
                mock(com.me.galchat.tool.InvestigatorSceneTools.class);
        TrpgGroupAgentPolicy policy = new TrpgGroupAgentPolicy(
                mock(ChatClient.class),
                mock(GroupContextAssembler.class),
                cardService,
                new CharacterCardContextFormatter(),
                mock(KpDiceTools.class),
                mock(KpPushedCheckTools.class),
                mock(com.me.galchat.tool.TrpgSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpModuleTools.class),
                mock(com.me.galchat.tool.KpSkillRuleTools.class),
                sceneTools,
                mock(com.me.galchat.tool.KpSceneTools.class),
                mock(com.me.galchat.tool.KpRunTools.class),
                mock(com.me.galchat.service.impl.TrpgContextWindowService.class),
                investigatorAssembler,
                mock(com.me.galchat.tool.KpCombatTools.class),
                mock(com.me.galchat.service.impl
                        .TrpgCombatLifecycleService.class),
                mock(com.me.galchat.tool.KpChildSceneTools.class),
                mock(com.me.galchat.tool
                        .KpWaitingInvestigatorTools.class),
                mock(com.me.galchat.service.impl
                        .TrpgChildSceneCommandService.class));
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
        assertThat(invocation.prompt().getInstructions().getFirst().getText())
                .contains("<investigator-card name=\"林恩\"")
                .contains("<investigator-card name=\"艾琳\"")
                .doesNotContain("食尸鬼")
                .doesNotContain("<npc-cards>")
                .contains("<investigator-resident-rules>")
                .contains("以角色本身的性格为准")
                .contains("KP 决定是否检定")
                .contains("不要为了使用最高数值而扭曲行动")
                .contains("结束探索只结束自己的主动行动")
                .contains("重伤但清醒时仍可行动")
                .doesNotContain("<kp-resident-rules>");
        assertThat(invocation.prompt().getInstructions().getLast().getText())
                .contains("<decision>")
                .contains("</decision>")
                .contains("<action>")
                .contains("</action>")
                .contains("公开行动通常只用一至两句")
                .contains("问题数量压到完成当前意图所需的最少")
                .contains("不追加无关的动作描写、语气渲染、履历、自我评价、能力说明、重复理由或后续计划")
                .contains("决策必须先于行动")
                .contains("本轮首位提案者")
                .contains("首先提出一个具体可执行的计划")
                .contains("不能替其他调查员决定");

        var contributor = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_TRPG_SCENE,
                        GroupChatConstant.ACTOR_CHARACTER,
                        10L,
                        "scene:1",
                        "场景",
                        1,
                        2),
                new GroupContextMaterial(List.of()));
        assertThat(contributor.prompt().getInstructions().getLast().getText())
                .contains("本轮后续调查员")
                .contains("支持、补充、修改、反对或提出替代计划")
                .contains("也可以只表达认同")
                .contains("不得假设尚未经过KP裁定的行动已经成功");
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
                mock(KpPushedCheckTools.class),
                mock(com.me.galchat.tool.TrpgSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpModuleTools.class),
                mock(com.me.galchat.tool.KpSkillRuleTools.class),
                mock(com.me.galchat.tool.InvestigatorSceneTools.class),
                mock(com.me.galchat.tool.KpSceneTools.class),
                mock(com.me.galchat.tool.KpRunTools.class),
                mock(com.me.galchat.service.impl.TrpgContextWindowService.class),
                mock(com.me.galchat.service.impl.TrpgInvestigatorContextAssembler.class),
                mock(com.me.galchat.tool.KpCombatTools.class),
                mock(com.me.galchat.service.impl
                        .TrpgCombatLifecycleService.class),
                mock(com.me.galchat.tool.KpChildSceneTools.class),
                mock(com.me.galchat.tool
                        .KpWaitingInvestigatorTools.class),
                mock(com.me.galchat.service.impl
                        .TrpgChildSceneCommandService.class));
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
                71L, participantId == null ? "PLAYER" : "BOT",
                participantId, name, Map.of("CON", 55),
                10, 10, 54, 60, 55, 0,
                false, false, false, false,
                false, null, null);
    }

    private CocDiceCharacterVO npcCard(String name) {
        return new CocDiceCharacterVO(
                81L, "NPC", null, name, Map.of("DEX", 60),
                12, 12, 0, 0, 50, 1,
                false, false, false, false,
                false, null, null);
    }

    private Set<String> exposedToolNames(List<Object> tools) {
        Set<String> names = new LinkedHashSet<>();
        for (Object toolObject : tools) {
            List<Class<?>> hierarchy = new ArrayList<>();
            for (Class<?> type = toolObject.getClass(); type != null;
                 type = type.getSuperclass()) {
                hierarchy.add(type);
            }
            for (Class<?> type : hierarchy) {
                for (Method method : type.getDeclaredMethods()) {
                    Tool tool = method.getAnnotation(Tool.class);
                    if (tool != null) {
                        names.add(tool.name());
                    }
                }
            }
        }
        return names;
    }
}

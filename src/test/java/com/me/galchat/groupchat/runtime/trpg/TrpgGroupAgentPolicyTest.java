package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.CharacterCardVO;
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
        when(cardService.getById(81L)).thenReturn(new CharacterCardVO(
                new CocCharacter().setId(81L).setRunId(7L)
                        .setActorType("NPC").setName("食尸鬼")
                        .setOccupation("食腐怪物")
                        .setHpCurrent(12).setHpMax(12)
                        .setMpCurrent(8).setMpMax(8)
                        .setDex(60).setCon(50).setArmor(1)
                        .setBuild(0).setMov(9).setDamageBonus("0"),
                List.of(
                        new CocCharacterSkill()
                                .setDisplayName("斗殴").setValue(45)
                                .setBaseValue(25),
                        new CocCharacterSkill()
                                .setDisplayName("聆听").setValue(20)
                                .setBaseValue(20)),
                List.of(),
                new CocCharacterProfile()
                        .setAppearance("弓背、皮肤灰白，利爪沾着泥土。")
                        .setTraits("饥饿、谨慎，会优先拖走落单者。")
                        .setIdeology("保护巢穴并寻找尸体。")));
        when(cardService.getById(71L)).thenReturn(new CharacterCardVO(
                new CocCharacter().setId(71L).setRunId(7L)
                        .setActorType("PLAYER").setName("林恩"),
                List.of(),
                List.of(new CocCharacterWeapon()
                        .setCharacterId(71L)
                        .setName("左轮手枪")
                        .setSkillName("射击:手枪")
                        .setDamage("1D10")
                        .setAmmoCapacity(6)
                        .setRemainingAmmo(4)
                        .setIsBroken(false)),
                null));

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
        var clarificationTools = mock(
                com.me.galchat.tool.KpClarificationTools.class);
        policy.setKpClarificationTools(clarificationTools);
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
                new GroupContextMaterial(List.of(), Set.of(81L)));

        assertThat(invocation.prompt().getInstructions().getFirst().getText())
                .contains("仅世界提示词")
                .contains("<investigator-card name=\"林恩\"")
                .contains("<investigator-card name=\"艾琳\"")
                .contains("<npc-roster>", "食尸鬼")
                .contains("<active-npc name=\"食尸鬼\"")
                .contains("职业：食腐怪物")
                .contains("外貌：弓背、皮肤灰白，利爪沾着泥土。")
                .contains("特征：饥饿、谨慎，会优先拖走落单者。")
                .contains("动机：保护巢穴并寻找尸体。")
                .contains("HP 12/12", "DEX 60", "斗殴=45")
                .doesNotContain("<investigator-card name=\"食尸鬼\"")
                .doesNotContain("聆听=20")
                .contains("基础游戏循环")
                .contains("<kp-skill-index>")
                .contains("- 侦查：")
                .contains("- 格斗:斧：")
                .contains("skillNames只能使用下列准确技能名")
                .contains("普通成功、困难成功和极难成功能够获取的信息必须完全一致")
                .contains("只有大成功可以获得合理的额外收益")
                .contains("等级差异只能体现在实现目标的过程")
                .contains("更高等级的成功应表现为过程更轻松")
                .contains("禁止输出括号式或标签式的检定结果摘要")
                .contains("（威尔追踪成功：确认有两人进入森林，其中一人受伤、步态踉跄，方向明确指向东北。）")
                .contains("信息只能自然融入叙述")
                .doesNotContain("完整战斗循环")
                .contains("KP不是可见的调查员")
                .contains("最多调用一个会改变状态的掷骰工具")
                .contains("本次响应会暂停")
                .contains("恢复同一步骤");
        assertThat(invocation.prompt().getInstructions().getLast().getText())
                .contains("共同场景提案")
                .contains("统一裁定")
                .contains("不要按调查员逐条机械回复")
                .contains("不确定是否需要追问时不要调用")
                .contains("团队问题只向真人玩家确认")
                .contains("可以改变、补充或放弃原行动");
        assertThat(policy.actorName(5L, kp)).isEqualTo("KP");
        assertThat(invocation.tools())
                .containsExactly(
                        kpDiceTools, kpPushedCheckTools, kpModuleTools,
                        kpSkillRuleTools, kpSceneTools, kpRunTools,
                        kpCombatTools, clarificationTools);
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
                .contains("readSkillRules", "updateWeaponState")
                .doesNotContain("askForClarification")
                .doesNotContain("requestPushedCheck");

        var routeInvocation = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE,
                        GroupChatConstant.ACTOR_KP,
                        null,
                        "combat:1",
                        "战斗反应路由",
                        1,
                        1),
                new GroupContextMaterial(List.of()));
        assertThat(routeInvocation.tools())
                .containsExactly(clarificationTools);
        assertThat(routeInvocation.prompt().getInstructions()
                .getLast().getText())
                .contains("无法唯一确定时调用askForClarification")
                .contains("不确定是否需要追问时不要调用");
        assertThat(combatInvocation.prompt().getInstructions()
                .getFirst().getText())
                .contains("基础游戏循环")
                .contains("完整战斗循环")
                .contains("<investigator-weapon-states>")
                .contains("左轮手枪/射击:手枪")
                .contains("弹药4/6")
                .contains("每次实际射击后")
                .contains("updateWeaponState")
                .contains("一次射出3发")
                .contains("装填")
                .contains("大失败")
                .contains("武器损坏", "误伤", "走火")
                .doesNotContain("弹药与故障、射程档位")
                .doesNotContain("不使用射程、抵近、移动修正、装填、连射、自动武器、弹药、故障");

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
                .contains("结束当前主场景或子场景时必须调用finishSceneExploration")
                .contains("结束子场景不会影响父场景")
                .contains("只能说明“XXX决定离开了XX”")
                .contains("不得加入后续前往场景的任何内容")
                .contains("声明希望前往当前场景的不同地区时，必须调用")
                .contains("动态子场景")
                .contains("继承当前大场景")
                .contains("只能说明")
                .contains("前往")
                .contains("不得涉及新场景的具体内容")
                .contains("允许且推荐")
                .contains("多次调用")
                .contains("子场景工具流程示例")
                .contains("startChildScene({\"childSceneName\":\"钟楼\",\"investigatorNames\":[\"林恩\"]})")
                .contains("林恩前往钟楼")
                .contains("直接进行钟楼子场景")
                .contains("finishSceneExploration")
                .contains("林恩决定离开了钟楼")
                .contains("返回父场景");

        when(commandService.canStartChildScene(conversation))
                .thenReturn(false);
        when(commandService.hasWaitingInvestigators(conversation))
                .thenReturn(false);
        when(commandService.isActiveChildScene(conversation))
                .thenReturn(true);
        var childInvocation = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_TRPG_SCENE,
                        GroupChatConstant.ACTOR_KP,
                        null,
                        "scene:child:1",
                        "钟楼",
                        2,
                        1),
                new GroupContextMaterial(List.of()));

        assertThat(childInvocation.tools())
                .doesNotContain(childTools);
        assertThat(childInvocation.prompt().getInstructions()
                .getLast().getText())
                .contains("未提供startChildScene")
                .contains("已经处于子场景")
                .contains("直接进行当前子场景")
                .contains("不得寻找、虚构或重试startChildScene");
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
        GroupContextAssembler contextAssembler =
                mock(GroupContextAssembler.class);
        when(contextAssembler.actorName(
                5L, new GroupActorRef(
                        GroupChatConstant.ACTOR_CHARACTER, 9L)))
                .thenReturn("林登");
        when(cardService.listDiceCharacters(7L)).thenReturn(List.of(
                card(71L, "林恩", null),
                card(72L, "艾琳", 9L),
                npcCard("食尸鬼")));
        when(cardService.getById(71L)).thenReturn(new CharacterCardVO(
                new CocCharacter().setId(71L).setName("林恩")
                        .setParticipantId(null)
                        .setStr(45).setDex(60)
                        .setHpCurrent(10).setHpMax(10)
                        .setSanCurrent(54).setSanMax(60),
                List.of(
                        new CocCharacterSkill().setDisplayName("聆听")
                                .setBaseValue(20).setValue(20),
                        new CocCharacterSkill().setDisplayName("图书馆使用")
                                .setBaseValue(20).setValue(70)),
                List.of(),
                new CocCharacterProfile().setNotes("队友私密档案")));
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
                contextAssembler,
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
        String investigatorSystem = invocation.prompt().getInstructions()
                .getFirst().getText();
        assertThat(investigatorSystem)
                .contains("<controlled-investigator>艾琳</controlled-investigator>")
                .contains("Agent身份名是“林登”")
                .contains("操控的调查员名是“艾琳”")
                .contains("“林登”不是调查员姓名")
                .contains("对外发言、自称和行动一律使用“艾琳”")
                .contains("<other-investigator name=\"林恩\"")
                .contains("STR=45", "DEX=60", "图书馆使用=70")
                .doesNotContain("<other-investigator name=\"艾琳\"")
                .doesNotContain("聆听=20", "队友私密档案")
                .doesNotContain("食尸鬼")
                .doesNotContain("<npc-cards>")
                .contains("<investigator-resident-rules>")
                .contains("以角色本身的性格为准")
                .contains("KP 决定是否检定")
                .contains("不要为了使用最高数值而扭曲行动")
                .contains("结束探索只结束自己的主动行动")
                .contains("重伤但清醒时仍可行动")
                .doesNotContain("<kp-resident-rules>");
        assertThat(investigatorSystem).endsWith("""
                【思维模式要求】在你的思考过程（<think>标签内）中，请遵守以下规则：
                1. 禁止使用圆括号包裹内心独白，例如"（心想：……）"或"(内心OS：……)"，所有分析内容直接陈述即可
                2. 禁止以角色第一人称描写内心活动，例如"我心想""我觉得""我暗自"等，请用分析性语言替代
                3. 思考内容应聚焦于剧情走向分析和回复内容规划，不要在思考中进行角色扮演式的内心戏表演
                """.stripTrailing());
        assertThat(invocation.prompt().getInstructions().getLast().getText())
                .contains("现在轮到艾琳执行当前场景探索行动")
                .doesNotContain("现在轮到林登执行")
                .contains("<decision>")
                .contains("</decision>")
                .contains("<action>")
                .contains("</action>")
                .contains("像群聊里的简短口语行动，不写成小说段落或规则说明")
                .contains("action通常只写一句，最多50个汉字")
                .contains("默认在直接说的话与动作声明之间二选一")
                .contains("主语优先使用受控调查员姓名，或在语义清楚时直接省略")
                .contains("多段姓名（由“·”、空格等分隔）")
                .contains("默认只保留其中一段常用名或姓")
                .contains("“埃莉诺·哈珀”写作“埃莉诺”或“哈珀”")
                .contains("不要写完整姓名")
                .contains("即使出现重名，也先改用另一段称呼")
                .contains("仍有歧义时只增加最少必要片段，避免还原完整姓名")
                .contains("不要让多轮action频繁以“我”开头")
                .contains("用口语化的短动词")
                .contains("<action>埃莉诺去书房翻翻，看看有没有账本。</action>")
                .doesNotContain("<action>埃莉诺·哈珀去书房")
                .contains("<action>先在门外瞧一眼，没动静再进去。</action>")
                .contains("<action>威尔贴右边往前探探，看看血点和拖痕；大家拉开几步。</action>")
                .doesNotContain("<action>我去书房搜索。</action>")
                .doesNotContain("<action>我贴右侧向前探路")
                .contains("错误：先写台词，再用第三人称重述同一行动")
                .contains("决策必须先于行动")
                .contains("本轮首位提案者")
                .contains("首先提出一个具体可执行的计划")
                .contains("不能替其他调查员决定")
                .contains("确定不再执行当前场景行动时可调用endSceneExploration")
                .doesNotContain("调用后的action只能说明“XXX决定离开了XX”");

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
        return card(71L, name, participantId);
    }

    private CocDiceCharacterVO card(
            Long cardId, String name, Long participantId) {
        return new CocDiceCharacterVO(
                cardId, participantId == null ? "PLAYER" : "BOT",
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

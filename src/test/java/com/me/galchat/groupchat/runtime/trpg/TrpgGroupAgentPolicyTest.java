package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.TrpgCombat;
import com.me.galchat.domain.vo.CharacterCardVO;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.groupchat.runtime.GroupContextMaterial;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.service.impl.CharacterCardContextFormatter;
import com.me.galchat.service.impl.GroupContextAssembler;
import com.me.galchat.service.impl.TrpgStepInteractionService;
import com.me.galchat.service.impl.TrpgChildSceneCommandService;
import com.me.galchat.service.impl.TrpgCombatLifecycleService;
import com.me.galchat.service.impl.TrpgContextWindowService;
import com.me.galchat.service.impl.TrpgInvestigatorContextAssembler;
import com.me.galchat.service.impl.TrpgInvestigatorSuspensionService;
import com.me.galchat.tool.KpDiceTools;
import com.me.galchat.tool.KpPushedCheckTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.annotation.Tool;
import tools.jackson.databind.json.JsonMapper;

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
    void kpSceneIntroUsesCurrentRuntimeInsteadOfCompletedSceneSummary() {
        ICharacterCardService cardService = mock(ICharacterCardService.class);
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
                .setId(7L).setWorldId(2L).setUserWorldId(5L)
                .setActiveReplyPlanId(41L);
        TrpgInvestigatorSuspensionService suspensions =
                mock(TrpgInvestigatorSuspensionService.class);
        when(suspensions.sceneReentryPrompt(7L, 41L))
                .thenReturn("<kp-storyline-reentry>KP恢复桥接</kp-storyline-reentry>");
        policy.setInvestigatorSuspensionTools(
                mock(com.me.galchat.tool
                        .KpInvestigatorSuspensionTools.class),
                mock(com.me.galchat.tool
                        .KpSuspendedInvestigatorRecoveryTools.class),
                suspensions);

        var invocation = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_TRPG_SCENE_INTRO,
                        GroupChatConstant.ACTOR_KP,
                        null,
                        "scene:41",
                        "本宁顿图书馆",
                        1,
                        1),
                new GroupContextMaterial(List.of(
                        new SystemMessage("""
                                <current-scene-runtime>
                                当前场景：第一天－上午 - 本宁顿图书馆
                                引入完成后参与行动的调查员：
                                - 埃莉诺·哈珀
                                </current-scene-runtime>"""),
                        new UserMessage("""
                                <context-summary status="completed">
                                已结束地点：本宁顿旗帜报报社
                                场景参与者：沃尔顿·红莲
                                </context-summary>""")), Set.of(), Set.of(),
                        "不要进入地下室。"));

        assertThat(invocation.prompt().getInstructions().getLast().getText())
                .contains("当前SCENE计划名称：“本宁顿图书馆”")
                .contains("以 <current-scene-runtime> 指定的当前场景和参与者为唯一准则")
                .contains("<context-summary status=\"completed\">")
                .contains("不得继续或重新引入其中已经结束的场景")
                .contains("不要使用其中的参与者代替当前参与者")
                .contains("<kp-storyline-reentry>")
                .contains("KP恢复桥接")
                .contains("公开回复必须使用无包裹的小说正文")
                .contains("任何段落不得以全角或半角圆括号、方括号开头或结尾")
                .contains("不得使用括号、星号、地点标签或角色标签充当舞台指示")
                .contains("存在多个并行地点时，直接用空行分段，不添加镜头标记")
                .contains("不得附加括号式或其他场外行动提示")
                .contains("不要建议调查员换一种查法")
                .contains("每次回复都尽量让当前场景发生有意义的变化")
                .contains("推进调查、揭示信息、产生后果、提供选择，或回应调查员的行动")
                .contains("避免没有新信息、新选择或新变化的空转")
                .contains("完成当前叙述后立即结束回复");
        assertThat(invocation.prompt().getInstructions())
                .noneMatch(message -> message.getText()
                        .contains("不要进入地下室。"));
    }

    @Test
    void postCombatTransitionOffersOnlyNarrativeSuspensionManagement() {
        ICharacterCardService cardService = mock(ICharacterCardService.class);
        when(cardService.listDiceCharacters(7L)).thenReturn(List.of());
        var suspendTools = mock(com.me.galchat.tool
                .KpInvestigatorSuspensionTools.class);
        var recoveryTools = mock(com.me.galchat.tool
                .KpSuspendedInvestigatorRecoveryTools.class);
        TrpgGroupAgentPolicy policy = new TrpgGroupAgentPolicy(
                mock(ChatClient.class), mock(GroupContextAssembler.class),
                cardService, new CharacterCardContextFormatter(),
                mock(KpDiceTools.class), mock(KpPushedCheckTools.class),
                mock(com.me.galchat.tool.TrpgSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpModuleTools.class),
                mock(com.me.galchat.tool.KpSkillRuleTools.class),
                mock(com.me.galchat.tool.InvestigatorSceneTools.class),
                mock(com.me.galchat.tool.KpSceneTools.class),
                mock(com.me.galchat.tool.KpRunTools.class),
                mock(TrpgContextWindowService.class),
                mock(TrpgInvestigatorContextAssembler.class),
                mock(com.me.galchat.tool.KpCombatTools.class),
                mock(TrpgCombatLifecycleService.class),
                mock(com.me.galchat.tool.KpChildSceneTools.class),
                mock(com.me.galchat.tool.KpWaitingInvestigatorTools.class),
                mock(TrpgChildSceneCommandService.class));
        policy.setInvestigatorSuspensionTools(
                suspendTools, recoveryTools,
                mock(TrpgInvestigatorSuspensionService.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setWorldId(2L).setUserWorldId(5L);

        var invocation = policy.prepare(conversation,
                new GroupActionSpec(
                        GroupChatConstant
                                .ACTION_TRPG_POST_COMBAT_TRANSITION,
                        GroupChatConstant.ACTOR_KP, null,
                        "post-combat:77", "战斗结束后的叙事过渡",
                        1, 1),
                new GroupContextMaterial(List.of()));

        assertThat(invocation.tools())
                .containsExactly(suspendTools)
                .doesNotContain(recoveryTools);
        assertThat(invocation.prompt().getInstructions().getLast().getText())
                .contains("战斗结束后的叙事过渡")
                .contains("所有调查员的下一次行动之前")
                .contains("不要仅因昏迷、受伤、受控或暂时无法行动")
                .contains("适合继续主持")
                .contains("切换镜头");
    }

    @Test
    void firstRestoredInvestigatorActionReceivesCompressionSafeBridge() {
        ICharacterCardService cardService = mock(ICharacterCardService.class);
        CocDiceCharacterVO elaine = card(109L, "艾琳", 9L);
        when(cardService.listDiceCharacters(7L))
                .thenReturn(List.of(elaine));
        GroupContextAssembler contextAssembler =
                mock(GroupContextAssembler.class);
        TrpgInvestigatorContextAssembler investigatorAssembler =
                mock(TrpgInvestigatorContextAssembler.class);
        TrpgContextWindowService contextWindowService =
                mock(TrpgContextWindowService.class);
        TrpgInvestigatorSuspensionService suspensions =
                mock(TrpgInvestigatorSuspensionService.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setWorldId(2L).setUserWorldId(5L)
                .setActiveReplyPlanId(31L);
        GroupActionSpec action = new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE,
                GroupChatConstant.ACTOR_CHARACTER, 9L, 109L,
                "scene:31", "营地", 1, 1);
        when(contextAssembler.actorName(5L, action.actor()))
                .thenReturn("调查员Agent");
        when(investigatorAssembler.format(conversation, action))
                .thenReturn("调查员卡");
        when(suspensions.reentryPrompt(7L, 109L, 31L))
                .thenReturn("<investigator-storyline-reentry>桥接内容</investigator-storyline-reentry>");
        TrpgGroupAgentPolicy policy = new TrpgGroupAgentPolicy(
                mock(ChatClient.class), contextAssembler, cardService,
                new CharacterCardContextFormatter(),
                mock(KpDiceTools.class), mock(KpPushedCheckTools.class),
                mock(com.me.galchat.tool.TrpgSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpModuleTools.class),
                mock(com.me.galchat.tool.KpSkillRuleTools.class),
                mock(com.me.galchat.tool.InvestigatorSceneTools.class),
                mock(com.me.galchat.tool.KpSceneTools.class),
                mock(com.me.galchat.tool.KpRunTools.class),
                contextWindowService, investigatorAssembler,
                mock(com.me.galchat.tool.KpCombatTools.class),
                mock(TrpgCombatLifecycleService.class),
                mock(com.me.galchat.tool.KpChildSceneTools.class),
                mock(com.me.galchat.tool.KpWaitingInvestigatorTools.class),
                mock(TrpgChildSceneCommandService.class));
        policy.setInvestigatorSuspensionTools(
                mock(com.me.galchat.tool
                        .KpInvestigatorSuspensionTools.class),
                mock(com.me.galchat.tool
                        .KpSuspendedInvestigatorRecoveryTools.class),
                suspensions);

        var invocation = policy.prepare(
                conversation, action,
                new GroupContextMaterial(List.of()));

        assertThat(invocation.prompt().getInstructions().getLast().getText())
                .contains("<investigator-storyline-reentry>")
                .contains("桥接内容");
        org.mockito.Mockito.verify(contextWindowService).recordPrompt(
                7L, GroupChatConstant.ACTOR_CHARACTER, 109L,
                invocation.prompt().getInstructions());
    }

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
                card(71L, "林恩", null),
                card(72L, "艾琳", 9L),
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
                        .setRange("15m")
                        .setAttacksPerRound("1（3）")
                        .setAmmoCapacity(6)
                        .setRemainingAmmo(4)
                        .setIsBroken(false)
                        .setAbnormal(true)
                        .setRiskTags(List.of("显眼", "严格管制"))),
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
        var firearmTools = mock(com.me.galchat.tool.KpFirearmTools.class);
        policy.setKpFirearmTools(firearmTools);
        var meleeTools = mock(com.me.galchat.tool.KpMeleeTools.class);
        policy.setKpMeleeTools(meleeTools);
        var inquiryLuckTools = mock(
                com.me.galchat.tool.KpInquiryLuckTools.class);
        policy.setKpInquiryLuckTools(inquiryLuckTools);
        var investigatorInquiryTools = mock(
                com.me.galchat.tool.InvestigatorKpInquiryTools.class);
        policy.setInvestigatorKpInquiryTools(
                investigatorInquiryTools);
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
                new GroupContextMaterial(
                        List.of(), Set.of(81L), Set.of(71L)));

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
                .contains("<kp-abnormal-weapon-rules>")
                .contains("林恩：左轮手枪（riskTags：显眼、严格管制）")
                .contains("关键线索不能因此永久消失")
                .contains("<kp-skill-index>")
                .contains("- 侦查：")
                .contains("- 格斗:斧：")
                .contains("skillNames只能使用下列准确技能名")
                .contains("普通成功、困难成功和极难成功能够获取的信息必须完全一致")
                .contains("只有大成功可以获得合理的额外收益")
                .contains("等级差异只能体现在实现目标的过程")
                .contains("更高等级的成功应表现为过程更轻松")
                .contains("最终想改变的场景事实")
                .contains("更换方法、检定项或执行者")
                .contains("急救和医学的技能检定允许孤注一掷")
                .contains("同一阶段对同一治疗目标")
                .contains("失败不等于目标必然落空")
                .contains("付出明确代价后达成")
                .contains("轮转聚光灯")
                .contains("每名调查员都有作出重要选择")
                .contains("`requestPushedCheck`")
                .contains("原检定的 `summary-id`")
                .contains("重新提供执行者、候选技能、难度、修饰和群体规则")
                .contains("禁止输出括号式或标签式的检定结果摘要")
                .contains("（威尔追踪成功：确认有两人进入森林，其中一人受伤、步态踉跄，方向明确指向东北。）")
                .contains("信息只能自然融入叙述")
                .doesNotContain("完整战斗循环")
                .contains("KP不是可见的调查员")
                .contains("最多调用一个会改变状态的掷骰工具")
                .contains("本次响应会暂停")
                .contains("恢复同一步骤");
        assertThat(invocation.prompt().getInstructions().getFirst().getText())
                .doesNotContain("最近一次失败作出的第二次")
                .doesNotContain("通常沿用原项目和难度")
                .doesNotContain("治疗和 SAN 损失不能孤注一掷");
        assertThat(invocation.prompt().getInstructions().getLast().getText())
                .contains("共同场景提案")
                .contains("统一裁定")
                .contains("不要按调查员逐条机械回复")
                .contains("“也跟上去”“也一起去”“也留下来”")
                .contains("继承其明确指向的此前调查员行动")
                .contains("不是新的独立行动目标")
                .contains("历史消息的speaker是规范调查员姓名")
                .contains("正文省略姓名或主语时仍按speaker归属行动和掷骰")
                .contains("不确定是否需要追问时不要调用")
                .contains("团队问题只向真人玩家确认")
                .contains("可以改变、补充或放弃原行动")
                .contains("调用startCombat后")
                .contains("未有人物卡的临时 NPC")
                .contains("quickNpcs")
                .contains("WEAK、MEDIUM、STRONG")
                .contains("UNARMED、LARGE_CLUB、MEDIUM_KNIFE、PISTOL、SMALL_RIFLE、HUNTING_RIFLE")
                .contains("战斗尚未激活")
                .contains("不得描述先攻顺序、战斗轮或任何角色的新行动")
                .contains("确认参战者后立即结束回复")
                .contains("公开回复必须使用无包裹的小说正文")
                .contains("任何段落不得以全角或半角圆括号、方括号开头或结尾")
                .contains("不得使用括号、星号、地点标签或角色标签充当舞台指示")
                .contains("存在多个并行地点时，直接用空行分段，不添加镜头标记")
                .contains("不得附加括号式或其他场外行动提示")
                .contains("不要建议调查员换一种查法")
                .contains("完成当前叙述后立即结束回复");
        assertThat(policy.actorName(5L, kp)).isEqualTo("KP");
        assertThat(invocation.tools())
                .containsExactly(
                        kpDiceTools, kpPushedCheckTools, kpModuleTools,
                        kpSkillRuleTools, kpSceneTools, kpRunTools,
                        kpCombatTools, clarificationTools);
        assertThat(exposedToolNames(invocation.tools()))
                .contains("requestPushedCheck", "readSkillRules");

        var otherSceneInvocation = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_TRPG_SCENE,
                        GroupChatConstant.ACTOR_KP,
                        null,
                        "scene:2",
                        "阁楼",
                        2,
                        1),
                new GroupContextMaterial(
                        List.of(), Set.of(), Set.of(72L)));
        assertThat(otherSceneInvocation.prompt().getInstructions()
                .getFirst().getText())
                .doesNotContain("<kp-abnormal-weapon-rules>")
                .doesNotContain("riskTags：显眼、严格管制");

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
                .contains("readSkillRules", "updateWeaponState",
                        "requestFirearmAttack", "requestMeleeAttack")
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

        var inquiryInvocation = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant
                                .ACTION_TRPG_INTERACTION_RESPONSE,
                        GroupChatConstant.ACTOR_KP,
                        null,
                        71L,
                        "scene:1",
                        "地下室",
                        1,
                        1,
                        com.me.galchat.service.impl
                                .TrpgStepInteractionService
                                .INVESTIGATOR_KP_INQUIRY),
                new GroupContextMaterial(List.of()));
        assertThat(inquiryInvocation.tools())
                .containsExactly(inquiryLuckTools);
        assertThat(inquiryInvocation.prompt().getInstructions()
                .getLast().getText())
                .contains("只回答调查员最新提出的一个问题")
                .contains("不是行动裁定")
                .contains("requestInquiryLuck")
                .contains("外部偶然事件")
                .contains("空载出租车")
                .contains("从抽屉外部看不见里面")
                .contains("不能决定出租车司机停车")
                .contains("工具结果已经出现")
                .contains("不得再次调用");
        String assembledRoutePrompt = routeInvocation.prompt()
                .getInstructions().stream()
                .map(message -> message.getText())
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(assembledRoutePrompt)
                .contains("“每轮”表示攻击或射击频率，不是伤害公式")
                .contains("`1（3）`", "常规射击1发", "最多射击3发")
                .contains("枪械射击的目标不能用普通闪避或反击")
                .contains("察觉到枪械攻击且有躲避空间时才可选择寻找掩护")
                .contains("投掷武器可以闪避")
                .contains("NPC 默认优先反击")
                .doesNotContain("requestFirearmAttack", "requestMeleeAttack");
        assertThat(combatInvocation.prompt().getInstructions()
                .getFirst().getText())
                .contains("基础游戏循环")
                .contains("完整战斗循环")
                .doesNotContain("<kp-abnormal-weapon-rules>")
                .contains("<investigator-weapon-states>")
                .contains("左轮手枪/射击:手枪")
                .contains("射程15m")
                .contains("弹药4/6")
                .contains("一次调用的 `targets`")
                .contains("之后所有已掷攻击组失效")
                .contains("updateWeaponState")
                .contains("requestFirearmAttack")
                .contains("requestMeleeAttack")
                .contains("装填")
                .contains("大失败")
                .contains("武器损坏", "误伤", "走火")
                .contains("射程按人物卡基础射程换算", "近/中/远")
                .contains("SINGLE", "SEMI_AUTO")
                .contains("SHORT_BURST", "FULL_AUTO")
                .doesNotContain("弹药与故障、射程档位")
                .doesNotContain("不使用射程、抵近、移动修正、装填、连射、自动武器、弹药、故障");
        String assembledCombatPrompt = combatInvocation.prompt()
                .getInstructions().stream()
                .map(message -> message.getText())
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(assembledCombatPrompt)
                .contains("#### 近战通用示例")
                .contains("{\"request\":{\"reason\":\"用折刀刺击邪教徒\"")
                .contains("\"defenseMode\":\"COUNTERATTACK\"")
                .contains("#### 远程通用示例")
                .contains("{\"request\":{\"reason\":\"寻找掩护\"")
                .contains("{\"request\":{\"reason\":\"用手枪射击林恩\"")
                .contains("\"firingMode\":\"SINGLE\"")
                .contains("#### 战技通用示例")
                .contains("{\"request\":{\"reason\":\"抓住并控制邪教徒\"")
                .contains("\"restrainedByCharacterName\":\"林恩\"")
                .contains("`rollDamage` 调用示例")
                .contains("{\"request\":{\"reason\":\"坠落伤害\",\"targets\":[{\"targetCharacterName\":\"林恩\",\"formula\":\"1D6\"}]}}")
                .contains("这些来源模式不适用于 `rollDamage`")
                .doesNotContain("`FOLLOW_UP` 伤害")
                .doesNotContain("`STANDALONE` 伤害")
                .doesNotContain("前置检定成功后的伤害");

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
                .contains("濒死")
                .contains("只输出一段简短自然语言行动声明")
                .contains("当前步骤没有可调用的工具")
                .contains("不得输出或模拟工具调用协议")
                .contains("combatAttack", "tool_calls", "DSML");
        String assembledNpcAttackPrompt = npcAttack.prompt()
                .getInstructions().stream()
                .map(message -> message.getText())
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(assembledNpcAttackPrompt)
                .contains("“每轮”表示攻击或射击频率，不是伤害公式")
                .contains("`1（3）`", "常规射击1发", "最多射击3发")
                .contains("枪械射击的目标不能用普通闪避或反击")
                .contains("投掷武器可以闪避")
                .contains("NPC 默认优先反击")
                .doesNotContain("requestMeleeAttack",
                        "requestFirearmAttack", "`rollDamage`");
        assertThat(npcAttack.prompt().getInstructions()
                .getFirst().getText())
                .contains("当前步骤只负责行动声明")
                .doesNotContain("每次响应最多调用一个会改变状态的掷骰工具");
        assertThat(npcAttack.tools()).isEmpty();
        assertThat(npcAttack.tools())
                .doesNotContain(investigatorInquiryTools);
        org.mockito.Mockito.verify(contextWindowService)
                .recordPrompt(
                        org.mockito.ArgumentMatchers.eq(7L),
                        org.mockito.ArgumentMatchers.eq(
                                GroupChatConstant.ACTOR_KP),
                        org.mockito.ArgumentMatchers.isNull(),
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
                .contains("只提交<name>元素中的地点名称")
                .contains("不得包含<summary>")
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
        com.me.galchat.tool.InvestigatorKpInquiryTools inquiryTools =
                mock(com.me.galchat.tool
                        .InvestigatorKpInquiryTools.class);
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
        policy.setInvestigatorKpInquiryTools(inquiryTools);
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
                new GroupContextMaterial(
                        List.of(), Set.of(), Set.of(),
                        "优先确认地下室入口。"));

        assertThat(invocation.tools())
                .containsExactly(sceneTools, inquiryTools);
        assertThat(invocation.prompt().getInstructions())
                .anyMatch(message -> message instanceof UserMessage
                        && message.getText().contains(
                                "优先确认地下室入口。")
                        && message.getText().contains(
                                "仅用于调整本行动轮的行动倾向"));
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
                .contains("适用性优先于数值")
                .contains("优先采用成功率更高的策略")
                .contains("NPC 已拒绝、回避或当前交涉方法失败")
                .contains("不得只改写问题再次询问")
                .contains("观察其言行、表情、迟疑或矛盾")
                .contains("心理学或侦查")
                .contains("证据、利益、关系或把柄")
                .contains("说服改成魅惑、话术或恐吓")
                .contains("仍属于同一次尝试的孤注一掷")
                .contains("最终想改变的场景事实")
                .contains("更换方法、检定项或执行者")
                .contains("急救和医学的技能检定允许孤注一掷")
                .contains("同一阶段对同一治疗目标")
                .contains("失败不等于目标必然落空")
                .contains("付出明确代价后达成")
                .contains("行动只需明确当前调查员要做什么")
                .contains("对象、去向或直接目标")
                .contains("职业、技能、性格和装备用于决定选择什么行动")
                .contains("不要求在行动文本中展示这些决策依据")
                .contains("结束探索只结束自己的主动行动")
                .contains("重伤但清醒时仍可行动")
                .contains("队友昏迷时可尝试急救")
                .contains("医学不能解除昏迷")
                .contains("队友重伤时可使用急救或医学")
                .contains("队友濒死时优先急救")
                .contains("HP 为 0 不等于自动处于濒死")
                .doesNotContain("接受失败、改变目标、改变方法")
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
                .contains("<action>查理沿偏北的血迹追过去。</action>")
                .contains("<action>威尔也跟上去。</action>")
                .contains("<action>埃莉诺留在岔口做记号。</action>")
                .doesNotContain("<action>埃莉诺·哈珀去书房")
                .contains("同一去向、对象和即时目标")
                .contains("不要换一种说法复述该行动")
                .contains("写出所跟随的调查员")
                .contains("不要因为职业、技能或性格不同")
                .doesNotContain("<action>我去书房搜索。</action>")
                .doesNotContain("<action>我贴右侧向前探路")
                .contains("错误：先写台词，再用第三人称重述同一行动")
                .contains("决策必须先于行动")
                .contains("本轮首位提案者")
                .contains("只声明当前调查员接下来要做什么")
                .contains("不要展开行动方法、检查项目、步骤、站位或风险预案")
                .contains("不能替其他调查员决定")
                .contains("确定不再执行当前场景行动时可调用endSceneExploration")
                .contains("【向KP询问：askKp】")
                .contains("询问本身不算正式行动")
                .contains("不得要求KP掷幸运")
                .contains("从我现在的位置，能看见正在经过或停靠的空载出租车吗")
                .contains("不要询问关闭的抽屉里面有什么")
                .contains("倒下的书柜是否完全挡住了食尸鬼")
                .contains("工具是returnDirect")
                .doesNotContain("调用后的action只能说明“XXX决定离开了XX”");

        var resumed = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_TRPG_SCENE,
                        GroupChatConstant.ACTOR_CHARACTER,
                        9L,
                        72L,
                        "scene:1",
                        "场景",
                        1,
                        1,
                        com.me.galchat.service.impl
                                .TrpgStepInteractionService
                                .INVESTIGATOR_KP_INQUIRY),
                new GroupContextMaterial(List.of()));
        assertThat(resumed.prompt().getInstructions().getLast().getText())
                .contains("刚刚从向KP询问中恢复")
                .contains("问题不算正式行动")
                .contains("现在必须据此完成")
                .contains("不得重复询问已经回答的事实");

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
                .contains("与此前某名调查员相同的行动")
                .contains("只需用最短的自然语言说明当前调查员也加入该行动")
                .contains("只有行动需要KP分别处理")
                .contains("才算不同的行动")
                .contains("不得假设尚未经过KP裁定的行动已经成功");

        var clarificationResponse = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant
                                .ACTION_TRPG_INTERACTION_RESPONSE,
                        GroupChatConstant.ACTOR_CHARACTER,
                        9L,
                        72L,
                        "scene:1",
                        "场景",
                        1,
                        1,
                        TrpgStepInteractionService.KP_CLARIFICATION),
                new GroupContextMaterial(List.of()));
        assertThat(clarificationResponse.tools()).isEmpty();
        assertThat(clarificationResponse.prompt().getInstructions()
                .getLast().getText())
                .contains("回答KP刚刚公开提出的追问")
                .contains("最新回答将替代与之冲突的旧行动")
                .contains("不受普通行动50字限制");
    }

    @Test
    void kpCombatIntroDescribesVisibleBattlefieldBeforeActions() {
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
                mock(com.me.galchat.service.impl
                        .TrpgContextWindowService.class),
                mock(com.me.galchat.service.impl
                        .TrpgInvestigatorContextAssembler.class),
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
                        GroupChatConstant.ACTION_COMBAT_INTRO,
                        GroupChatConstant.ACTOR_KP,
                        null,
                        "combat:round:1",
                        "战斗第1轮",
                        1,
                        0),
                new GroupContextMaterial(List.of()));

        assertThat(invocation.tools()).isEmpty();
        assertThat(invocation.prompt().getInstructions().getFirst()
                .getText())
                .contains("当前阶段是战斗环境引入")
                .contains("不进行战斗裁定，也不掷骰");
        assertThat(invocation.prompt().getInstructions().getLast()
                .getText())
                .contains("独立的战斗环境快照步骤")
                .contains("地形", "掩体", "距离", "光照")
                .contains("已经明确的参战者位置")
                .contains("缺少的信息直接省略，不得自行补全")
                .contains("不得描述先攻顺序、战斗轮开始、攻击或防守选择")
                .contains("其他调查员、NPC和旁观者保持上一条公开消息中的状态")
                .contains("完成静态快照后立即结束回复")
                .contains("下一独立步骤");
    }

    @Test
    void investigatorCombatActionUsesDecisionActionProtocolWithoutTools() {
        ICharacterCardService cardService =
                mock(ICharacterCardService.class);
        when(cardService.listDiceCharacters(7L)).thenReturn(List.of(
                card(72L, "林登", 9L),
                combatNpcCard(81L, "参战食尸鬼"),
                combatNpcCard(82L, "场外邪教徒")));
        com.me.galchat.service.impl.TrpgInvestigatorContextAssembler
                investigatorAssembler = mock(com.me.galchat.service.impl
                .TrpgInvestigatorContextAssembler.class);
        when(investigatorAssembler.format(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn("<controlled-investigator>林登</controlled-investigator>");
        com.me.galchat.service.impl.TrpgCombatLifecycleService
                combatLifecycleService = mock(com.me.galchat.service.impl
                .TrpgCombatLifecycleService.class);
        var participants = JsonMapper.builder().build()
                .createArrayNode();
        participants.addObject().put("characterId", 72L);
        participants.addObject().put("characterId", 81L);
        when(combatLifecycleService.requireActiveCombat(
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new TrpgCombat()
                        .setCurrentRound(2)
                        .setParticipants(participants));
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
                investigatorAssembler,
                mock(com.me.galchat.tool.KpCombatTools.class),
                combatLifecycleService,
                mock(com.me.galchat.tool.KpChildSceneTools.class),
                mock(com.me.galchat.tool
                        .KpWaitingInvestigatorTools.class),
                mock(com.me.galchat.service.impl
                        .TrpgChildSceneCommandService.class));
        var inquiryTools = mock(
                com.me.galchat.tool.InvestigatorKpInquiryTools.class);
        policy.setInvestigatorKpInquiryTools(inquiryTools);
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
        String systemPrompt = invocation.prompt().getInstructions()
                .getFirst().getText();
        String npcOverview = systemPrompt.substring(
                systemPrompt.indexOf("<combat-npc-overview"),
                systemPrompt.indexOf("</combat-npc-overview>")
                        + "</combat-npc-overview>".length());
        assertThat(systemPrompt)
                .contains("<investigator-combat-reference>")
                .contains("近战", "射击", "每轮攻击次数", "瞄准")
                .contains("装填一发并立刻射击", "战技")
                .contains("寻找掩护", "闪避检定",
                        "失去自己的下一个主动行动位")
                .contains("被钳制不会自动跳过行动")
                .contains("脱离钳制", "惩罚骰", "奖励骰")
                .contains("<combat-npc-overview round=\"2\">")
                .contains("参战食尸鬼", "处于掩护", "被眩晕（剩余2回合）")
                .contains("被钳制（钳制者：林登）", "本轮已被近战攻击")
                .doesNotContain("requestFirearmAttack", "requestMeleeAttack",
                        "requestOpposedCheck", "updateCombatStates",
                        "rollDamage", "DSML", "tool_calls");
        assertThat(npcOverview)
                .doesNotContain("场外邪教徒")
                .doesNotContain("HP", "3/12", "DEX", "60", "护甲",
                        "体格", "技能", "斗殴", "临时疯狂", "剩余4小时");
        assertThat(invocation.prompt().getInstructions().getLast().getText())
                .contains("<decision>")
                .contains("<action>");

        var attackInvocation = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_COMBAT_ATTACK,
                        GroupChatConstant.ACTOR_CHARACTER,
                        9L,
                        72L,
                        "combat:1",
                        "战斗",
                        1,
                        1,
                        null),
                new GroupContextMaterial(List.of()));
        assertThat(attackInvocation.tools())
                .containsExactly(inquiryTools);
        assertThat(attackInvocation.prompt().getInstructions()
                .getLast().getText())
                .contains("【向KP询问：askKp】")
                .contains("距离、掩体、出口、位置关系")
                .contains("倒下的书柜是否完全挡住了食尸鬼");

        var defenseInvocation = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_COMBAT_DEFENSE,
                        GroupChatConstant.ACTOR_CHARACTER,
                        9L,
                        72L,
                        "combat:1",
                        "战斗",
                        1,
                        1,
                        null),
                new GroupContextMaterial(List.of()));
        assertThat(defenseInvocation.tools()).isEmpty();
    }

    private CocDiceCharacterVO combatNpcCard(Long id, String name) {
        return new CocDiceCharacterVO(
                id, "NPC", null, name,
                Map.of("DEX", 60, "斗殴", 80),
                3, 12, 0, 0, 50, 2, 4,
                true, false, false, false,
                true, "REAL_TIME", 4,
                true, true, 2, 72L, "林登", true);
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

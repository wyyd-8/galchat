package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.TrpgCombat;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import com.me.galchat.domain.vo.CharacterCardVO;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.groupchat.runtime.GroupAgentPolicy;
import com.me.galchat.groupchat.runtime.GroupContextMaterial;
import com.me.galchat.groupchat.runtime.GroupModelInvocation;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.service.impl.CharacterCardContextFormatter;
import com.me.galchat.service.impl.GroupContextAssembler;
import com.me.galchat.service.impl.TrpgContextWindowService;
import com.me.galchat.service.impl.TrpgChildSceneCommandService;
import com.me.galchat.service.impl.TrpgInvestigatorContextAssembler;
import com.me.galchat.service.impl.TrpgInvestigatorSuspensionService;
import com.me.galchat.tool.KpChildSceneTools;
import com.me.galchat.tool.KpClarificationTools;
import com.me.galchat.tool.KpDiceTools;
import com.me.galchat.tool.KpFirearmTools;
import com.me.galchat.tool.KpMeleeTools;
import com.me.galchat.tool.KpPushedCheckTools;
import com.me.galchat.tool.InvestigatorSceneTools;
import com.me.galchat.tool.InvestigatorKpInquiryTools;
import com.me.galchat.tool.KpInquiryLuckTools;
import com.me.galchat.tool.KpSceneTools;
import com.me.galchat.tool.KpRunTools;
import com.me.galchat.tool.KpWaitingInvestigatorTools;
import com.me.galchat.tool.KpInvestigatorSuspensionTools;
import com.me.galchat.tool.KpSuspendedInvestigatorRecoveryTools;
import com.me.galchat.tool.TrpgSceneSelectionTools;
import com.me.galchat.tool.KpSceneSelectionTools;
import com.me.galchat.tool.KpModuleTools;
import com.me.galchat.tool.KpSkillRuleTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class TrpgGroupAgentPolicy implements GroupAgentPolicy {

    private static final String INVESTIGATOR_KP_INQUIRY_RULES = """

            【向KP询问：askKp】
            当前步骤可能提供askKp。只有缺少一个会实质影响当前行动选择的必要事实时才调用；上下文已经足够时不要调用。
            可以询问：已经公开但有歧义的事实；调查员不移动、不搜索、不接触物体、不检定即可直接看见、听见或已知的信息。战斗主动攻击者还可询问直接可观察的距离、掩体、出口、位置关系和静态环境。
            不得询问：应采取什么策略或攻击谁；行动能否成功、命中率或NPC会如何反应；隐藏线索、关闭容器内部或必须通过移动、搜索、接触、交谈、检定才能获知的信息；与当前行动无关的背景知识。
            KP决定是否需要幸运检定。调查员不得要求KP掷幸运，也不得用询问制造武器、贵重资源或关键线索。
            调用时只提出一个具体问题，不得同时输出decision、action或其他工具调用。工具是returnDirect；询问本身不算正式行动，也不消耗本行动位。
            KP回答后系统会恢复同一个步骤。把最新问题与KP回答视为补充上下文，再完成本步骤的decision和action；不得把问题当成已完成的行动，不得重复或改写已经回答的事实。只有出现另一个独立且必要的阻碍时才可再次询问。

            示例：
            - 深夜街道且是否有车会改变行动时，可以调用askKp：{"inquiryType":"DESCRIBE_VISIBLE_INFORMATION","question":"从我现在的位置，能看见正在经过或停靠的空载出租车吗？"}。如果KP回答没有，再据此声明正式行动。
            - 不要询问关闭的抽屉里面有什么；那需要搜索或打开，应直接声明行动。
            - 不要询问酒保是否愿意回答；与NPC交谈及其反应属于行动。
            - 战斗主动攻击者可以问：{"inquiryType":"DESCRIBE_VISIBLE_INFORMATION","question":"倒下的书柜是否完全挡住了食尸鬼？"}
            - 不要问“我该攻击谁”“这一枪会成功吗”或“请掷幸运让我脚边有一把霰弹枪”。
            """;

    private static final String KP_INVESTIGATOR_INQUIRY_RESPONSE_RULES = """

            当前步骤只回答调查员最新提出的一个问题，使其能够返回原步骤声明行动；这是公开回答，但不是行动裁定、场景推进或战斗推进。
            询问者可能由用户直接操控，也可能由调查员Agent操控；两者适用完全相同的回答与幸运检定规则。
            按以下顺序处理：
            1. 已由模组、公开上下文或当前场景固定的事实，直接回答。
            2. 调查员原地即可直接感知的信息，直接描述；只给可见、可听或已知边界，不泄露隐藏信息。
            3. 显然存在、显然不存在或不可能的事物，直接回答，不掷骰。
            4. 必须通过移动、搜索、打开、接触、交谈或检定才能获知时，只说明当前无法直接确认，不替调查员执行行动。例如：“从抽屉外部看不见里面是否放着钥匙。”不得擅自替其打开抽屉或要求侦查检定。
            5. 只有尚未确定、成功与失败都合理的外部偶然事件，才调用requestInquiryLuck。例如深夜街道是否恰好有空载出租车经过。
            6. 对策略、行动成败、隐藏线索或NPC新反应的提问，只回答可确认的事实边界，不提供建议，不裁定尚未声明的行动。

            幸运工具限制：
            - requestInquiryLuck是本步骤唯一可用工具，一次回答最多调用一次，必须单独调用并立即结束本次响应。
            - favorableEvent必须写成检定成功时成立的具体、肯定、可观察事实。公共环境使用CURRENT_INVESTIGATOR_GROUP；仅询问者个人的偶然事件使用REQUESTER。
            - 不得用于固定/可见事实、隐藏线索、容器内部、行动或攻击成败、社交结果、NPC选择、武器、贵重资源或关键线索，也不得重掷同一事实。
            - 成功只确认有利事件存在，不保证后续利用成功；例如只确认空载出租车正在经过，不能决定出租车司机停车或同意请求。
            - 如果工具结果已经出现在上下文中，说明当前步骤由骰点恢复：不得再次调用。成功时确认favorableEvent，失败时说明该事物在当前场景或当前短时间窗口内不存在，不追加额外效果。

            示例：
            - 问“先前描述的侧门还开着吗？”：按公开事实直接回答，不掷骰。
            - 问“深夜街道现在有空载出租车吗？”且上下文未确定：调用requestInquiryLuck({"favorableEvent":"当前街道在短时间内有一辆可见的空载出租车经过","scope":"CURRENT_INVESTIGATOR_GROUP"})。成功只描述出租车驶近；失败描述当前街道没有空载出租车。
            - 问“关闭的抽屉里有钥匙吗？”：回答从抽屉外部看不见里面，不掷骰。
            - 问“我脚边恰好有霰弹枪吗？”：不合理且会生成高价值武器，直接否定，不掷骰。
            - 战斗中问书柜是否遮挡目标：直接描述静态视线与掩体，不判断命中率。
            - 问出租车司机会不会停车：这是NPC反应，不能决定出租车司机停车；最多只能确认车辆是否经过。

            只输出一个直接、简洁的自然语言答案，不向调查员反问，不附带后续行动建议。
            """;

    private static final String KP_EXPLORATION_OUTPUT_RULES = """

            公开回复只输出当前步骤允许的场景叙述或裁定结果。
            不得附加括号式或其他场外行动提示；不要建议调查员换一种查法、询问NPC、再次检索或检定、收手或离开，也不要用提问或备选项催促下一步。这些后续行动由调查员在下一轮自行决定。
            完成当前叙述后立即结束回复。
            """;

    private static final String KP_SUSPENSION_RULES = """

            suspendInvestigators是低频叙事镜头调度工具。先判断这组调查员眼下是否还有适合立即主持的遭遇、选择或反馈：有则继续主持，物理分离且立即有独立内容则使用子场景；只有其剧情线暂时不适合继续展开、应切换镜头到其他调查员时才悬置。
            不要仅因昏迷、受伤、受控或暂时无法行动而悬置；若其仍在当前现场并影响剧情，就继续保留在当前叙事中。
            """;

    private final ChatClient chatClient;
    private final GroupContextAssembler contextAssembler;
    private final ICharacterCardService characterCardService;
    private final CharacterCardContextFormatter characterCardFormatter;
    private final KpDiceTools kpDiceTools;
    private final KpPushedCheckTools kpPushedCheckTools;
    private final TrpgSceneSelectionTools sceneSelectionTools;
    private final KpSceneSelectionTools kpSceneSelectionTools;
    private final KpModuleTools kpModuleTools;
    private final KpSkillRuleTools kpSkillRuleTools;
    private final InvestigatorSceneTools investigatorSceneTools;
    private final KpSceneTools kpSceneTools;
    private final KpRunTools kpRunTools;
    private final TrpgContextWindowService contextWindowService;
    private final TrpgInvestigatorContextAssembler investigatorContextAssembler;
    private final com.me.galchat.tool.KpCombatTools kpCombatTools;
    private final com.me.galchat.service.impl.TrpgCombatLifecycleService
            combatLifecycleService;
    private final KpChildSceneTools kpChildSceneTools;
    private final KpWaitingInvestigatorTools kpWaitingInvestigatorTools;
    private final TrpgChildSceneCommandService childSceneCommandService;
    private KpClarificationTools kpClarificationTools;
    private InvestigatorKpInquiryTools investigatorKpInquiryTools;
    private KpInquiryLuckTools kpInquiryLuckTools;
    private KpFirearmTools kpFirearmTools;
    private KpMeleeTools kpMeleeTools;
    private KpInvestigatorSuspensionTools investigatorSuspensionTools;
    private KpSuspendedInvestigatorRecoveryTools
            suspendedInvestigatorRecoveryTools;
    private TrpgInvestigatorSuspensionService suspensionService;

    @Autowired
    public TrpgGroupAgentPolicy(@Qualifier("trpgGroupChatClient") ChatClient chatClient,
                                GroupContextAssembler contextAssembler,
                                ICharacterCardService characterCardService,
                                CharacterCardContextFormatter characterCardFormatter,
                                KpDiceTools kpDiceTools,
                                KpPushedCheckTools kpPushedCheckTools,
                                TrpgSceneSelectionTools sceneSelectionTools,
                                KpSceneSelectionTools
                                        kpSceneSelectionTools,
                                KpModuleTools kpModuleTools,
                                KpSkillRuleTools kpSkillRuleTools,
                                InvestigatorSceneTools investigatorSceneTools,
                                KpSceneTools kpSceneTools,
                                KpRunTools kpRunTools,
                                TrpgContextWindowService contextWindowService,
                                TrpgInvestigatorContextAssembler
                                        investigatorContextAssembler,
                                com.me.galchat.tool.KpCombatTools
                                        kpCombatTools,
                                com.me.galchat.service.impl
                                        .TrpgCombatLifecycleService
                                        combatLifecycleService,
                                KpChildSceneTools kpChildSceneTools,
                                KpWaitingInvestigatorTools
                                        kpWaitingInvestigatorTools,
                                TrpgChildSceneCommandService
                                        childSceneCommandService) {
        this.chatClient = chatClient;
        this.contextAssembler = contextAssembler;
        this.characterCardService = characterCardService;
        this.characterCardFormatter = characterCardFormatter;
        this.kpDiceTools = kpDiceTools;
        this.kpPushedCheckTools = kpPushedCheckTools;
        this.sceneSelectionTools = sceneSelectionTools;
        this.kpSceneSelectionTools = kpSceneSelectionTools;
        this.kpModuleTools = kpModuleTools;
        this.kpSkillRuleTools = kpSkillRuleTools;
        this.investigatorSceneTools = investigatorSceneTools;
        this.kpSceneTools = kpSceneTools;
        this.kpRunTools = kpRunTools;
        this.contextWindowService = contextWindowService;
        this.investigatorContextAssembler =
                investigatorContextAssembler;
        this.kpCombatTools = kpCombatTools;
        this.combatLifecycleService = combatLifecycleService;
        this.kpChildSceneTools = kpChildSceneTools;
        this.kpWaitingInvestigatorTools =
                kpWaitingInvestigatorTools;
        this.childSceneCommandService =
                childSceneCommandService;
    }

    @Autowired
    void setKpClarificationTools(
            KpClarificationTools kpClarificationTools) {
        this.kpClarificationTools = kpClarificationTools;
    }

    @Autowired
    void setInvestigatorKpInquiryTools(
            InvestigatorKpInquiryTools investigatorKpInquiryTools) {
        this.investigatorKpInquiryTools = investigatorKpInquiryTools;
    }

    @Autowired
    void setKpInquiryLuckTools(
            KpInquiryLuckTools kpInquiryLuckTools) {
        this.kpInquiryLuckTools = kpInquiryLuckTools;
    }

    @Autowired
    void setKpFirearmTools(KpFirearmTools kpFirearmTools) {
        this.kpFirearmTools = kpFirearmTools;
    }

    @Autowired
    void setKpMeleeTools(KpMeleeTools kpMeleeTools) {
        this.kpMeleeTools = kpMeleeTools;
    }

    @Autowired
    void setInvestigatorSuspensionTools(
            KpInvestigatorSuspensionTools investigatorSuspensionTools,
            KpSuspendedInvestigatorRecoveryTools
                    suspendedInvestigatorRecoveryTools,
            TrpgInvestigatorSuspensionService suspensionService) {
        this.investigatorSuspensionTools = investigatorSuspensionTools;
        this.suspendedInvestigatorRecoveryTools =
                suspendedInvestigatorRecoveryTools;
        this.suspensionService = suspensionService;
    }

    @Override
    public GroupModelInvocation prepare(GroupConversation conversation, GroupActionSpec action,
                                        GroupContextMaterial context) {
        GroupActorRef actor = action.actor();
        String agentName = actorName(conversation.getUserWorldId(), actor);
        boolean selectionPhase = GroupChatConstant.ACTION_TRPG_SCENE_SELECTION
                .equals(action.actionType());
        boolean scenePhase = GroupChatConstant.ACTION_TRPG_SCENE
                .equals(action.actionType());
        boolean sceneIntro = GroupChatConstant.ACTION_TRPG_SCENE_INTRO
                .equals(action.actionType());
        boolean combatIntro = GroupChatConstant.ACTION_COMBAT_INTRO
                .equals(action.actionType());
        boolean postCombatTransition = GroupChatConstant
                .ACTION_TRPG_POST_COMBAT_TRANSITION
                .equals(action.actionType());
        boolean activeChildScene = scenePhase
                && GroupChatConstant.ACTOR_KP.equals(actor.type())
                && childSceneCommandService.isActiveChildScene(
                        conversation);
        boolean proposalLead = scenePhase
                && !GroupChatConstant.ACTOR_KP.equals(actor.type())
                && Integer.valueOf(1).equals(action.itemOrder());
        boolean combatAttack = GroupChatConstant.ACTION_COMBAT_ATTACK
                .equals(action.actionType());
        boolean combatDefense = GroupChatConstant.ACTION_COMBAT_DEFENSE
                .equals(action.actionType());
        boolean combatAdjudicate =
                GroupChatConstant.ACTION_COMBAT_ADJUDICATE
                        .equals(action.actionType());
        boolean combatRoute =
                GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE
                        .equals(action.actionType());
        boolean interactionResponse = GroupChatConstant
                .ACTION_TRPG_INTERACTION_RESPONSE
                .equals(action.actionType());
        boolean investigatorKpInquiry = interactionResponse
                && GroupChatConstant.ACTOR_KP.equals(actor.type())
                && com.me.galchat.service.impl.TrpgStepInteractionService
                .INVESTIGATOR_KP_INQUIRY.equals(
                        action.interactionType());
        boolean canAskKp = GroupChatConstant.ACTOR_CHARACTER.equals(
                actor.type()) && (scenePhase || combatAttack);
        boolean resumedFromKpInquiry = canAskKp
                && com.me.galchat.service.impl.TrpgStepInteractionService
                .INVESTIGATOR_KP_INQUIRY.equals(
                        action.interactionType());
        boolean combatPhase = combatIntro || combatAttack || combatDefense
                || combatAdjudicate || combatRoute
                || GroupChatConstant.ACTION_TRPG_COMBAT
                .equals(action.actionType());
        String phase = selectionPhase ? "选景"
                : sceneIntro ? "场景引入"
                : combatIntro ? "战斗环境引入"
                : combatAttack ? "战斗攻击"
                : combatDefense ? "战斗防守"
                : combatRoute ? "战斗反应路由"
                : combatAdjudicate ? "战斗裁定"
                : postCombatTransition ? "战斗结束后的叙事过渡"
                : interactionResponse ? "追问回答"
                : GroupChatConstant.ACTION_TRPG_COMBAT.equals(action.actionType())
                ? "战斗" : "场景探索";
        String kpPhaseExecutionRules = combatIntro
                ? "当前步骤只描述战斗环境，不进行战斗裁定，也不掷骰。"
                + "当前步骤没有可调用的工具；不得替任何参战者决定行动。"
                + "每个回复只能完成当前阶段指定的工作，不得预演、顺带执行或描述后续阶段。"
                : postCombatTransition
                ? "当前步骤发生在战斗结果确认之后、所有调查员的下一次行动之前。"
                + "只处理战斗后各调查员所在位置和叙事焦点，不执行调查员行动，也不掷骰。"
                + "先判断各调查员的剧情现在适合继续主持，还是应切换镜头到其他调查员；"
                + "不要仅因昏迷、受伤、受控或暂时无法行动而悬置仍处于当前剧情中的角色。"
                : combatAttack || combatDefense
                ? "当前步骤只负责行动声明，不进行战斗裁定，也不掷骰。"
                + "当前步骤没有可调用的工具；不得调用或模拟任何工具调用协议。"
                + "战斗工具只由后续战斗裁定步骤使用；不得替用户决定调查员行动。"
                + "只有当前行动绑定的人物卡可以产生新行动；其他调查员、NPC和旁观者保持上一条公开消息中的状态。"
                : investigatorKpInquiry
                ? "当前步骤只回答调查员的公开询问，不裁定行动或推进场景。"
                + "只有确需判断外部偶然事件时才能调用requestInquiryLuck；不得调用其他工具。"
                + "工具返回后本次响应会暂停；恢复同一步骤时根据骰点结果直接回答，不得再次调用。"
                : combatRoute
                ? "当前步骤只进行战斗反应路由，不裁定成败，也不掷骰。"
                + "除缺少关键信息时使用公开追问工具外，不调用其他工具。"
                : "你负责描述场景、裁定规则并在需要时发起掷骰；不得替用户决定调查员行动。\n"
                + "每次响应最多调用一个会改变状态的掷骰工具，且不得与其他工具并行调用。\n"
                + "调用掷骰工具后本次响应会暂停；稍后恢复同一步骤时，再根据骰点结果继续裁定。";
        List<CocDiceCharacterVO> cards = characterCardService.listDiceCharacters(
                conversation.getId());
        List<CocDiceCharacterVO> investigatorCards = cards.stream()
                .filter(card -> "PLAYER".equals(card.actorType())
                        || "BOT".equals(card.actorType()))
                .toList();
        String investigatorName = GroupChatConstant.ACTOR_KP.equals(
                actor.type()) ? agentName
                : controlledInvestigatorName(investigatorCards, action);
        String reentryBridge = !scenePhase || suspensionService == null
                || action.subjectCharacterId() == null
                ? "" : suspensionService.reentryPrompt(
                        conversation.getId(),
                        action.subjectCharacterId(),
                        conversation.getActiveReplyPlanId());
        List<Message> messages = new ArrayList<>();
        if (GroupChatConstant.ACTOR_KP.equals(actor.type())) {
            List<CocDiceCharacterVO> weaponOwnerCards = scenePhase
                    ? investigatorCards.stream()
                            .filter(card -> context
                                    .currentSceneInvestigatorIds()
                                    .contains(card.cardId()))
                            .toList()
                    : investigatorCards;
            List<CharacterCardVO> investigatorFullCards =
                    scenePhase || combatPhase
                            ? weaponOwnerCards.stream()
                                    .map(card -> characterCardService.getById(
                                            card.cardId()))
                                    .filter(java.util.Objects::nonNull)
                                    .toList()
                            : List.of();
            String investigatorCardContext =
                    characterCardFormatter.format(investigatorCards);
            String investigatorWeaponContext = combatPhase
                    ? characterCardFormatter.formatInvestigatorWeaponStates(
                    investigatorFullCards)
                    : "";
            String abnormalWeaponRules = scenePhase
                    ? characterCardFormatter.formatAbnormalWeaponRules(
                    investigatorFullCards)
                    : "";
            List<CocDiceCharacterVO> npcCards = cards.stream()
                    .filter(card -> "NPC".equals(card.actorType()))
                    .toList();
            List<CharacterCardVO> activeNpcCards = npcCards.stream()
                    .filter(card -> context.relevantCharacterIds()
                            .contains(card.cardId()))
                    .map(card -> characterCardService.getById(card.cardId()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            messages.add(new SystemMessage(contextAssembler.baseSystemPrompt(conversation, actor) + "\n"
                    + investigatorCardContext + "\n"
                    + investigatorWeaponContext + "\n"
                    + abnormalWeaponRules + "\n"
                    + characterCardFormatter.formatNpcs(npcCards)
                    + "\n" + characterCardFormatter.formatActiveNpcs(
                            activeNpcCards)
                    + (combatPhase
                    ? TrpgRulePrompts.combatActionReference() : "")
                    + TrpgRulePrompts.residentRules()
                    + TrpgRulePrompts.skillIndex()
                    + (combatAdjudicate
                    ? TrpgRulePrompts.combatRules() : "")
                    + """

                    你是当前 TRPG 群聊唯一的KP，当前阶段是%s。KP不是可见的调查员。
                    %s
                    不得输出隐藏思考过程。
                    """.formatted(phase, kpPhaseExecutionRules)));
        } else {
            List<CharacterCardVO> otherInvestigatorCards =
                    investigatorCards.stream()
                            .filter(card -> !isControlledInvestigator(
                                    card, action))
                            .map(card -> characterCardService.getById(
                                    card.cardId()))
                            .filter(java.util.Objects::nonNull)
                            .toList();
            String combatNpcOverview = combatPhase
                    ? investigatorCombatNpcOverview(conversation, cards)
                    : "";
            messages.add(new SystemMessage(
                    investigatorContextAssembler.format(
                            conversation, action) + "\n"
                    + characterCardFormatter.formatOtherInvestigators(
                            otherInvestigatorCards)
                    + (combatPhase
                    ? TrpgRulePrompts.investigatorCombatReference() : "")
                    + combatNpcOverview
                    + TrpgRulePrompts.investigatorResidentRules()
                    + """

                    你是调查员操控 Agent。Agent身份名是“%s”，操控的调查员名是“%s”。
                    “%s”不是调查员姓名，只提供性格和决策倾向；你正在 TRPG 群聊中扮演“%s”。
                    对外发言、自称和行动一律使用“%s”，不得使用Agent身份名代替。当前阶段是%s。
                    不得输出隐藏思考过程。
                    """.formatted(
                            agentName, investigatorName, agentName,
                            investigatorName, investigatorName, phase)
                    + TrpgRulePrompts.investigatorThinkingModeRules()));
        }
        messages.addAll(context.messages());
        if (GroupChatConstant.ACTOR_KP.equals(actor.type())) {
            if (selectionPhase) {
                messages.add(new UserMessage("""
                        现在轮到KP开始选景。先读取current-game-time，并根据上一批场景判断是否需要推进时间。
                        首次选景必须同时提供targetDay和targetPeriod来初始化当前时间；后续选景可以同时省略它们以保持当前时间，或同时提供任意严格晚于当前时间的目标值。
                        根据最终采用的时间、地点标题索引、模组时间线、NPC作息和当前剧情，在内部判断此刻合理开放的地点。
                        调用publishExplorationScenes提交当天能够探索的准确地点名称列表和可选目标时间；不得输出地点ID，不要输出时间推进原因、内部判断或额外自然语言。
                        不强制限制地点数量或探索时长。
                        工具是returnDirect；调用后立即结束响应，不要再输出自然语言或JSON。
                        如果模组已经完整结束，可调用finishRun；系统会在本轮后自动生成每位调查员的人物后传，KP不要自行输出后传。
                        """));
            } else if (sceneIntro) {
                String kpReentry = suspensionService == null ? ""
                        : suspensionService.sceneReentryPrompt(
                                conversation.getId(),
                                conversation.getActiveReplyPlanId());
                messages.add(new UserMessage("""
                        这是当前SCENE Plan第一次进入行动轮。
                        当前SCENE计划名称：“%s”。
                        必须以 <current-scene-runtime> 指定的当前场景和参与者为唯一准则。
                        <context-summary status="completed"> 只提供过去已经公开的事实和获得的线索；不得继续或重新引入其中已经结束的场景，也不要使用其中的参与者代替当前参与者。
                        先公开引入当前地点：只描述调查员刚进入时能够观察到的事实，不替调查员决定行动，不泄露未公开真相。
                        """.formatted(action.groupName())
                        + kpReentry
                        + KP_EXPLORATION_OUTPUT_RULES));
            } else if (combatIntro) {
                messages.add(new UserMessage("""
                        这是独立的战斗环境快照步骤。只输出一个自然语言段落，描述已经明确存在的静态战场事实：会影响战术判断的地形、掩体、距离、光照，以及上下文已经明确的参战者位置。
                        只陈述参战者此刻能够观察到且已由公开上下文确认的事实，不泄露隐藏信息。缺少的信息直接省略，不得自行补全。
                        使用“位于、相距、摆放着、可以看见”等静态表达。不得描述任何角色在本步骤中新发生的移动、攻击、防守、拔出武器或姿态变化；不得根据人物性格、装备或合理性补写未发生的动作。
                        其他调查员、NPC和旁观者保持上一条公开消息中的状态；除非其位置已经公开且与战场观察直接相关，否则不要提及，更不得替其决定退开、旁观、逃跑或协助。
                        不得描述先攻顺序、战斗轮开始、攻击或防守选择，不得询问闪避、反击或下一步行动，也不得裁定攻击、伤害或状态。
                        完成静态快照后立即结束回复。系统会在下一独立步骤处理首位角色的行动。
                        """));
            } else if (postCombatTransition) {
                messages.add(new UserMessage("""
                        现在进行战斗结束后的叙事过渡。这一步位于战斗结果确认之后、所有调查员的下一次行动之前。
                        根据已经公开的战斗结局，简洁交代现场余波和各调查员此刻的位置。重点判断每组调查员的剧情现在是否适合继续主持，还是应切换镜头到其他调查员。
                        只有战斗结局使某些调查员在叙事上离开当前剧情线时才调用suspendInvestigators；不要仅因昏迷、受伤、受控或暂时无法行动而调用。
                        调用工具后，在同一公开回复中自然交代停镜位置并完成镜头切换。不得替调查员选择后续行动，不掷骰，不调用其他工具。
                        """));
            } else if (combatRoute) {
                messages.add(new UserMessage("""
                        读取紧邻的行动。攻击、阻拦、急救等涉及另一名参战者的行动使用TARGETED；装填等只影响行动者或其装备的行动使用SELF_OR_UTILITY。
                        TARGETED必须识别准确目标，并判断目标是否需要获得寻找掩护、闪避、反击或其他即时防守行动。枪械行动在一回合声明多个目标时，必须按声明顺序一次列出全部目标。
                        目标已死亡、濒死、昏迷或被眩晕（剩余眩晕回合数大于0）时，必须令insertDefense=false且defenseOptions为空；不得为其插入寻找掩护、闪避或反击。
                        只输出一个JSON对象，不要Markdown，不要叙事：
                        {"actionKind":"TARGETED","targetName":"准确人物卡名称","insertDefense":true,"defenseOptions":["闪避","反击"],"reason":"简短原因"}
                        多目标枪械攻击使用：
                        {"actionKind":"TARGETED","targets":[{"targetName":"目标甲","insertDefense":true,"defenseOptions":["寻找掩护"]},{"targetName":"目标乙","insertDefense":false,"defenseOptions":[]}],"reason":"简短原因"}
                        或：
                        {"actionKind":"SELF_OR_UTILITY","insertDefense":false,"defenseOptions":[],"reason":"装填等简短原因"}
                        TARGETED的目标缺失、歧义、不在参战者中或行动不合法时不要猜测，改为：
                        {"error":"明确说明问题"}
                        如果缺少的信息会实质改变行动路由，调用askForClarification公开追问一个问题；无法唯一确定时调用askForClarification，不要输出error。
                        工具是returnDirect，调用后立即结束响应。仅在确有必要时追问；不确定是否需要追问时不要调用。
                        """));
            } else if (interactionResponse) {
                if (investigatorKpInquiry) {
                    messages.add(new UserMessage(
                            KP_INVESTIGATOR_INQUIRY_RESPONSE_RULES));
                } else {
                    messages.add(new UserMessage(
                            "当前KP交互回答类型不受支持。只说明无法回答，不调用工具。"));
                }
            } else {
                String subjectName = action.subjectCharacterId() == null
                        ? null : cards.stream()
                        .filter(card -> action.subjectCharacterId()
                                .equals(card.cardId()))
                        .map(CocDiceCharacterVO::name)
                        .findFirst().orElse(
                                "人物卡#" + action.subjectCharacterId());
                String subjectHint = subjectName == null
                        ? "" : " 当前行动绑定人物卡“"
                        + subjectName + "”，只能代表该人物行动。";
                messages.add(new UserMessage("现在轮到KP推进当前" + phase
                        + "。" + subjectHint
                        + (combatAttack || combatDefense
                        ? (combatAttack
                        ? "攻击或影响他人时选择公开上下文中的准确目标；装填等只影响自身或装备的行动可以不选择他人目标。不要在此步骤裁定成败，也不要掷骰。"
                        : "选择公开上下文中的目标并描述行动；不要在此步骤裁定成败，也不要掷骰。")
                        + (combatAttack
                        ? " 在规则允许的范围内，不要尝试攻击昏迷/濒死的调查员。"
                        : "")
                        + " 只输出一段简短自然语言行动声明，必须写明行动者、核心行动和目标，使用武器时写明武器。"
                        + "当前步骤没有可调用的工具；不得输出或模拟工具调用协议，包括combatAttack、combatDefense、tool_calls、DSML、XML、JSON、参数标签或Markdown代码块。"
                        : scenePhase
                        ? "把上一次KP公开回复后的全部调查员发言视为一个共同场景提案；"
                        + "识别其中的联合行动、协助、兼容行动和冲突意图，以整个场景为单位统一裁定，"
                        + "不要按调查员逐条机械回复。需要掷骰时只调用一个对应工具；"
                        + "历史消息的speaker是规范调查员姓名；正文省略姓名或主语时仍按speaker归属行动和掷骰。"
                        + "“也跟上去”“也一起去”“也留下来”等引用式行动，继承其明确指向的此前调查员行动，"
                        + "表示该调查员参加同一行动，不是新的独立行动目标；统一处理，不得要求其重新说明。"
                        + "恢复同一步骤后继续处理共同提案中尚未裁定的部分。"
                        + "只有缺失信息会实质改变裁定时才调用askForClarification，一次只问一名调查员一个公开问题；"
                        + "不确定是否需要追问时不要调用。调查员已经明确理解重大风险时不得重复确认；"
                        + "团队问题只向真人玩家确认。追问回答可以改变、补充或放弃原行动，最新回答覆盖冲突的旧行动。"
                        + "发起战斗时，已有准确人物卡的角色放入participantNames；未有人物卡的临时 NPC 放入quickNpcs，"
                        + "只填写唯一名称、强度WEAK、MEDIUM、STRONG之一，以及武器UNARMED、LARGE_CLUB、MEDIUM_KNIFE、PISTOL、SMALL_RIFLE、HUNTING_RIFLE之一。"
                        + "调用startCombat后，当前步骤仍是战斗前的场景步骤，战斗尚未激活；"
                        + "公开消息只能确认被登记的参战者，不得描述先攻顺序、战斗轮或任何角色的新行动，"
                        + "也不得替未参战角色决定移动、旁观、逃跑或协助。确认参战者后立即结束回复，"
                        + "战斗环境和首个行动留给后续独立步骤。"
                        : "根据公开上下文裁定并行动；需要掷骰时只调用一个对应工具。")
                        + (scenePhase
                        ? """
                         结束当前主场景或子场景时必须调用finishSceneExploration。
                        结束子场景不会影响父场景。
                        调用后的公开消息只能说明“XXX决定离开了XX”，不得加入后续前往场景的任何内容。
                        当一名或多名调查员声明希望前往当前场景的不同地区时，必须调用startChildScene，并由你根据其目的地为动态子场景命名；
                        动态子场景不会加载更多模组信息，而是继承当前大场景的全部模组上下文。
                        当前回复对相应调查员只能说明“调查员甲、调查员乙前往某地”，不得涉及新场景的具体内容。
                        若调查员分别前往不同场景，须针对每个不同场景分别调用一次；同一回复允许且推荐根据不同场景多次调用startChildScene。

                        【子场景工具流程示例】
                        1. 主场景中，林恩声明去钟楼。调用startChildScene({"childSceneName":"钟楼","investigatorNames":["林恩"]})，本次回复只说“林恩前往钟楼”，不描写钟楼内部。
                        2. 本次回复完成后，系统自动切换到钟楼子场景。下一轮直接进行钟楼子场景，根据调查员行动正常描述与裁定，无须再次调用startChildScene。
                        3. 钟楼子场景应结束时，调用finishSceneExploration，本次回复只说“林恩决定离开了钟楼”。
                        4. 系统随后结束该子场景并返回父场景；父场景不受影响，再按当前上下文继续推进。
                        """
                        + (activeChildScene
                        ? """
                        当前已经处于子场景；此时未提供startChildScene是正常流程。请直接进行当前子场景，不得寻找、虚构或重试startChildScene；需要结束时调用finishSceneExploration。
                        """
                        : "")
                        : combatAdjudicate
                        ? combatLifecycleService.adjudicationPrompt(
                                conversation.getId(), action)
                        + " 在没有剩余检定或掷骰需求、且应结束战斗时调用markCombatFinished；调用后继续输出完整公开裁定和收束。"
                        : "")
                        + (scenePhase
                        ? KP_SUSPENSION_RULES
                        + KP_EXPLORATION_OUTPUT_RULES
                        : "")));
            }
        } else {
            if (selectionPhase) {
                messages.add(new UserMessage("现在轮到" + investigatorName
                        + "选景。查看KP给出的编号Map和先前调查员的选择结果。"
                        + "在仍有地点未被选择时，推荐优先选择不同地点，但可按角色性格作出不同决定。"
                        + "调用selectExplorationScene并且只传地点编号；工具是returnDirect，"
                        + "调用后立即结束响应，不要再输出自然语言、地点名或JSON。"));
            } else if (interactionResponse) {
                messages.add(new UserMessage(
                        "现在回答KP刚刚公开提出的追问。"
                        + "你可以补足细节、重新判断、改变行动或放弃原行动；"
                        + "最新回答将替代与之冲突的旧行动。"
                        + "严格使用以下格式，标签外不得输出正文：\n"
                        + "<decision>说明你如何根据追问重新判断；如果无需改变，也说明理由</decision>\n"
                        + "<action>用调查员口吻直接回答KP，信息足够完整，不受普通行动50字限制</action>"));
            } else {
                String sceneParticipation = !scenePhase ? ""
                        : proposalLead
                        ? "你是本轮首位提案者。只声明当前调查员接下来要做什么，明确行动的对象、去向或直接目标即可；"
                        + "不要展开行动方法、检查项目、步骤、站位或风险预案。"
                        + "你只有先发言权，不能替其他调查员决定是否参加或如何行动。"
                        : "你是本轮后续调查员。阅读本轮此前所有调查员已经声明的行动；"
                        + "如果当前调查员准备采取与此前某名调查员相同的行动，不要换一种说法复述该行动，"
                        + "只需用最短的自然语言说明当前调查员也加入该行动；有多个可能指向时写出所跟随的调查员。"
                        + "同一去向、对象和即时目标视为相同行动；措辞、站位、观察角度、关注细节或谨慎程度不同，不构成新的行动。"
                        + "只有行动需要KP分别处理，或者指向不同地点、对象或结果时，才算不同的行动，此时才重新说明自己的行动目标。"
                        + "不要因为职业、技能或性格不同，就把同一行动改写成不同的观察方式；"
                        + "不得假设尚未经过KP裁定的行动已经成功。";
                String sceneActionExamples = !scenePhase ? "" : """
                        场景探索的action只需明确行动对象、去向或直接目标，不要把decision中的分析、理由和观察清单展开到action。
                        判断后续行动是否相同时，同一去向、对象和即时目标视为相同行动；不要换一种说法复述该行动，也不要因为职业、技能或性格不同而改写同一行动。
                        成组示例：
                        首位：<action>查理沿偏北的血迹追过去。</action>
                        相同行动：<action>威尔也跟上去。</action>
                        不同行动：<action>埃莉诺留在岔口做记号。</action>
                        如果此前有多个不同的行动而“跟上去”可能产生歧义，应写出所跟随的调查员，例如<action>威尔也跟查理一起。</action>，仍不要复述查理的行动。
                        错误：查理已经声明沿血迹追踪后，威尔又声明“沿血迹一侧往北走，留意手印和新折枝”；这只是换一种说法重复同一行动。
                        """;
                messages.add(new UserMessage("现在轮到" + investigatorName + "执行当前" + phase
                        + "行动。" + reentryBridge + sceneParticipation
                        + "决策必须先于行动，并严格使用以下格式，标签外不得输出正文：\n"
                        + "<decision>一个完整自然语言段落，说明重要观察、线索联系、判断和本轮行动意图</decision>\n"
                        + "<action>像群聊里的简短口语行动，不写成小说段落或规则说明。action通常只写一句，最多50个汉字，"
                        + "只保留一个核心行动，以及完成它必需的对象、目标或一句协调。"
                        + "主语优先使用受控调查员姓名，或在语义清楚时直接省略。"
                        + "多段姓名（由“·”、空格等分隔）默认只保留其中一段常用名或姓，并在后续保持一致。"
                        + "例如“埃莉诺·哈珀”写作“埃莉诺”或“哈珀”，不要写完整姓名。"
                        + "即使出现重名，也先改用另一段称呼；仍有歧义时只增加最少必要片段，避免还原完整姓名。"
                        + "无法判断常用段时优先沿用上下文已有称呼，不自行编造昵称。"
                        + "只有真在说台词且自然需要第一人称时才偶尔使用“我”，不要让多轮action频繁以“我”开头。"
                        + "默认在直接说的话与动作声明之间二选一；两者表达同一意图时不得并写。"
                        + "用口语化的短动词，如“去、看看、翻翻、问问、守着、跟上”；少用“进行、展开、仔细观察”等书面表达。"
                        + "不拆解姿势、步伐、视线、呼吸、语气或多种感官，不重复decision的观察和理由；"
                        + "询问时只留完成意图所需的最少问题。不输出发言者标签</action>\n"
                        + sceneActionExamples
                        + "错误：先写台词，再用第三人称重述同一行动；或把“向前侦察”展开成连续的镜头描写。\n"
                        + "action必须落实decision中的意图，不得重新选择目标；不得宣布未知事实、"
                        + "决定其他角色或NPC反应，也不得自行声明检定成功。"
                        + (combatDefense
                        ? combatLifecycleService.defensePrompt(action)
                        : "")
                        + (combatAttack
                        ? "装填是合法的完整主动位行动；明确说出要装填的武器。"
                        : "")
                        + (canAskKp
                        ? INVESTIGATOR_KP_INQUIRY_RULES
                        : "")
                        + (resumedFromKpInquiry
                        ? """

                        当前步骤刚刚从向KP询问中恢复。你提出的问题不算正式行动。
                        读取最新KP回答，现在必须据此完成当前调查员的<decision>和<action>。
                        不得重复询问已经回答的事实；不得把KP回答直接复述成action。
                        """
                        : "")
                        + (scenePhase
                        ? "确定不再执行当前场景行动时可调用endSceneExploration；"
                        + "如需调用，必须先完成工具调用，再一次性输出上述decision和action。"
                        : "")));
            }
        }
        List<Object> tools;
        if (GroupChatConstant.ACTOR_KP.equals(actor.type())) {
            List<Object> sceneTools = tools(
                    kpDiceTools, kpPushedCheckTools, kpModuleTools,
                    kpSkillRuleTools, kpSceneTools,
                    kpRunTools, kpCombatTools,
                    kpClarificationTools);
            if (scenePhase) {
                List<Object> dynamicTools = new ArrayList<>(sceneTools);
                if (investigatorSuspensionTools != null) {
                    dynamicTools.add(investigatorSuspensionTools);
                }
                if (suspensionService != null
                        && suspendedInvestigatorRecoveryTools != null
                        && suspensionService.hasSuspendedInvestigators(
                                conversation.getId())) {
                    dynamicTools.add(suspendedInvestigatorRecoveryTools);
                }
                if (childSceneCommandService.canStartChildScene(
                        conversation)) {
                    dynamicTools.add(kpChildSceneTools);
                }
                if (childSceneCommandService
                        .hasWaitingInvestigators(conversation)) {
                    dynamicTools.add(kpWaitingInvestigatorTools);
                }
                sceneTools = List.copyOf(dynamicTools);
            }
            tools = selectionPhase
                    ? List.of(
                            kpSceneSelectionTools,
                            kpModuleTools, kpRunTools)
                    : postCombatTransition
                    ? tools(investigatorSuspensionTools)
                    : scenePhase
                    ? sceneTools
                    : combatAdjudicate
                    ? tools(kpDiceTools, kpFirearmTools, kpMeleeTools,
                            kpModuleTools, kpSkillRuleTools,
                            kpRunTools, kpCombatTools)
                    : combatRoute
                    ? tools(kpClarificationTools)
                    : investigatorKpInquiry
                    ? tools(kpInquiryLuckTools)
                    : combatIntro || combatAttack || combatDefense
                    ? List.of()
                    : tools(kpDiceTools, kpModuleTools, kpSkillRuleTools,
                            kpRunTools);
        } else {
            tools = selectionPhase
                    ? List.of(sceneSelectionTools)
                    : scenePhase
                    ? tools(investigatorSceneTools,
                            investigatorKpInquiryTools)
                    : combatAttack
                    ? tools(investigatorKpInquiryTools)
                    : List.of();
        }
        Prompt prompt = new Prompt(messages);
        if (GroupChatConstant.ACTOR_KP.equals(actor.type())) {
            contextWindowService.recordPrompt(
                    conversation.getId(), prompt.getInstructions());
        }
        return new GroupModelInvocation(chatClient, prompt, tools);
    }

    private List<Object> tools(Object... candidates) {
        List<Object> result = new ArrayList<>();
        for (Object candidate : candidates) {
            if (candidate != null) {
                result.add(candidate);
            }
        }
        return List.copyOf(result);
    }

    private String investigatorCombatNpcOverview(
            GroupConversation conversation,
            List<CocDiceCharacterVO> cards) {
        TrpgCombat combat;
        try {
            combat = combatLifecycleService.requireActiveCombat(
                    conversation);
        } catch (com.me.galchat.exception.UserRequestException ignored) {
            return "";
        }
        if (combat == null || combat.getParticipants() == null
                || !combat.getParticipants().isArray()) {
            return "";
        }
        Set<Long> participantIds = new HashSet<>();
        combat.getParticipants().forEach(node -> {
            if (node.get("characterId") != null) {
                participantIds.add(node.get("characterId").asLong());
            }
        });
        List<CocDiceCharacterVO> participatingNpcs = cards.stream()
                .filter(java.util.Objects::nonNull)
                .filter(card -> "NPC".equals(card.actorType()))
                .filter(card -> participantIds.contains(card.cardId()))
                .toList();
        return "\n" + characterCardFormatter.formatCombatNpcOverview(
                combat.getCurrentRound(), participatingNpcs) + "\n";
    }

    private boolean isControlledInvestigator(
            CocDiceCharacterVO card, GroupActionSpec action) {
        if (GroupChatConstant.ACTOR_USER.equals(action.actorType())) {
            return "PLAYER".equals(card.actorType())
                    && java.util.Objects.equals(
                    card.cardId(), action.actorId());
        }
        return GroupChatConstant.ACTOR_CHARACTER.equals(action.actorType())
                && java.util.Objects.equals(
                card.participantId(), action.actorId());
    }

    private String controlledInvestigatorName(
            List<CocDiceCharacterVO> investigatorCards,
            GroupActionSpec action) {
        return investigatorCards.stream()
                .filter(card -> isControlledInvestigator(card, action))
                .map(CocDiceCharacterVO::name)
                .filter(org.springframework.util.StringUtils::hasText)
                .findFirst()
                .orElse("当前绑定调查员");
    }

    @Override
    public String actorName(Long userWorldId, GroupActorRef actor) {
        if (GroupChatConstant.ACTOR_KP.equals(actor.type())) {
            return "KP";
        }
        return contextAssembler.actorName(userWorldId, actor);
    }
}

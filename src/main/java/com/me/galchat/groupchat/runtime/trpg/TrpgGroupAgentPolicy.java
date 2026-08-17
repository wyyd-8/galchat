package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
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
import com.me.galchat.tool.KpChildSceneTools;
import com.me.galchat.tool.KpClarificationTools;
import com.me.galchat.tool.KpDiceTools;
import com.me.galchat.tool.KpFirearmTools;
import com.me.galchat.tool.KpPushedCheckTools;
import com.me.galchat.tool.InvestigatorSceneTools;
import com.me.galchat.tool.KpSceneTools;
import com.me.galchat.tool.KpRunTools;
import com.me.galchat.tool.KpWaitingInvestigatorTools;
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
import java.util.List;

@Component
public class TrpgGroupAgentPolicy implements GroupAgentPolicy {

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
    private KpFirearmTools kpFirearmTools;

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
    void setKpFirearmTools(KpFirearmTools kpFirearmTools) {
        this.kpFirearmTools = kpFirearmTools;
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
        boolean combatPhase = combatAttack || combatDefense
                || combatAdjudicate || combatRoute
                || GroupChatConstant.ACTION_TRPG_COMBAT
                .equals(action.actionType());
        String phase = selectionPhase ? "选景"
                : sceneIntro ? "场景引入"
                : combatAttack ? "战斗攻击"
                : combatDefense ? "战斗防守"
                : combatRoute ? "战斗反应路由"
                : combatAdjudicate ? "战斗裁定"
                : interactionResponse ? "追问回答"
                : GroupChatConstant.ACTION_TRPG_COMBAT.equals(action.actionType())
                ? "战斗" : "场景探索";
        List<CocDiceCharacterVO> cards = characterCardService.listDiceCharacters(
                conversation.getId());
        List<CocDiceCharacterVO> investigatorCards = cards.stream()
                .filter(card -> "PLAYER".equals(card.actorType())
                        || "BOT".equals(card.actorType()))
                .toList();
        String investigatorName = GroupChatConstant.ACTOR_KP.equals(
                actor.type()) ? agentName
                : controlledInvestigatorName(investigatorCards, action);
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
                    + TrpgRulePrompts.residentRules()
                    + TrpgRulePrompts.skillIndex()
                    + (combatPhase ? TrpgRulePrompts.combatRules() : "")
                    + """

                    你是当前 TRPG 群聊唯一的KP，当前阶段是%s。KP不是可见的调查员。
                    你负责描述场景、裁定规则并在需要时发起掷骰；不得替用户决定调查员行动。
                    每次响应最多调用一个会改变状态的掷骰工具，且不得与其他工具并行调用。
                    调用掷骰工具后本次响应会暂停；稍后恢复同一步骤时，再根据骰点结果继续裁定。
                    不得输出隐藏思考过程。
                    """.formatted(phase)));
        } else {
            List<CharacterCardVO> otherInvestigatorCards =
                    investigatorCards.stream()
                            .filter(card -> !isControlledInvestigator(
                                    card, action))
                            .map(card -> characterCardService.getById(
                                    card.cardId()))
                            .filter(java.util.Objects::nonNull)
                            .toList();
            messages.add(new SystemMessage(
                    investigatorContextAssembler.format(
                            conversation, action) + "\n"
                    + characterCardFormatter.formatOtherInvestigators(
                            otherInvestigatorCards)
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
                        如果模组已经完整结束，可调用finishRun并继续输出最终公开收束消息。
                        """));
            } else if (sceneIntro) {
                messages.add(new UserMessage("""
                        这是当前SCENE Plan第一次进入行动轮。先公开引入当前地点：只描述调查员刚进入时能够观察到的事实，不替调查员决定行动，不泄露未公开真相。
                        """));
            } else if (combatRoute) {
                messages.add(new UserMessage("""
                        读取紧邻的行动。攻击、阻拦、急救等涉及另一名参战者的行动使用TARGETED；装填等只影响行动者或其装备的行动使用SELF_OR_UTILITY。
                        TARGETED必须识别准确目标，并判断目标是否需要获得寻找掩护、闪避、反击或其他即时防守行动。枪械行动在一回合声明多个目标时，必须按声明顺序一次列出全部目标。
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
                messages.add(new UserMessage("现在回答KP刚刚公开提出的追问。"
                        + "你可以补足细节、重新判断、改变行动或放弃原行动；"
                        + "最新回答将替代与之冲突的旧行动。"
                        + "严格使用以下格式，标签外不得输出正文：\n"
                        + "<decision>说明你如何根据追问重新判断；如果无需改变，也说明理由</decision>\n"
                        + "<action>用调查员口吻直接回答KP，信息足够完整，不受普通行动50字限制</action>"));
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
                        : scenePhase
                        ? "把上一次KP公开回复后的全部调查员发言视为一个共同场景提案；"
                        + "识别其中的联合行动、协助、兼容行动和冲突意图，以整个场景为单位统一裁定，"
                        + "不要按调查员逐条机械回复。需要掷骰时只调用一个对应工具；"
                        + "恢复同一步骤后继续处理共同提案中尚未裁定的部分。"
                        + "只有缺失信息会实质改变裁定时才调用askForClarification，一次只问一名调查员一个公开问题；"
                        + "不确定是否需要追问时不要调用。调查员已经明确理解重大风险时不得重复确认；"
                        + "团队问题只向真人玩家确认。追问回答可以改变、补充或放弃原行动，最新回答覆盖冲突的旧行动。"
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
                        : "")));
            }
        } else {
            if (selectionPhase) {
                messages.add(new UserMessage("现在轮到" + investigatorName
                        + "选景。查看KP给出的编号Map和先前调查员的选择结果。"
                        + "在仍有地点未被选择时，推荐优先选择不同地点，但可按角色性格作出不同决定。"
                        + "调用selectExplorationScene并且只传地点编号；工具是returnDirect，"
                        + "调用后立即结束响应，不要再输出自然语言、地点名或JSON。"));
            } else {
                String sceneParticipation = !scenePhase ? ""
                        : proposalLead
                        ? "你是本轮首位提案者。根据当前可见局面首先提出一个具体可执行的计划；"
                        + "你只有先发言权，不能替其他调查员决定是否参加或如何行动。"
                        : "你是本轮后续调查员。阅读本轮此前的提案，可以支持、补充、修改、反对或提出替代计划，"
                        + "也可以只表达认同，或提出能够同时进行的其他行动；"
                        + "不得假设尚未经过KP裁定的行动已经成功。";
                messages.add(new UserMessage("现在轮到" + investigatorName + "执行当前" + phase
                        + "行动。" + sceneParticipation
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
                        + "参考《克苏鲁的呼唤》示例中的桌边声明节奏：\n"
                        + "若受控调查员名为“埃莉诺·哈珀”：<action>埃莉诺去书房翻翻，看看有没有账本。</action>\n"
                        + "<action>先在门外瞧一眼，没动静再进去。</action>\n"
                        + "当前场景的推荐缩写：<action>威尔贴右边往前探探，看看血点和拖痕；大家拉开几步。</action>\n"
                        + "错误：先写台词，再用第三人称重述同一行动；或把“向前侦察”展开成连续的镜头描写。\n"
                        + "action必须落实decision中的意图，不得重新选择目标；不得宣布未知事实、"
                        + "决定其他角色或NPC反应，也不得自行声明检定成功。"
                        + (combatDefense
                        ? combatLifecycleService.defensePrompt(action)
                        : "")
                        + (combatAttack
                        ? "装填是合法的完整主动位行动；明确说出要装填的武器。"
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
                    : scenePhase
                    ? sceneTools
                    : combatAdjudicate
                    ? tools(kpDiceTools, kpFirearmTools,
                            kpModuleTools, kpSkillRuleTools,
                            kpRunTools, kpCombatTools)
                    : combatRoute
                    ? tools(kpClarificationTools)
                    : combatAttack || combatDefense
                    ? List.of()
                    : tools(kpDiceTools, kpModuleTools, kpSkillRuleTools,
                            kpRunTools);
        } else {
            tools = selectionPhase
                    ? List.of(sceneSelectionTools)
                    : scenePhase
                    ? List.of(investigatorSceneTools)
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

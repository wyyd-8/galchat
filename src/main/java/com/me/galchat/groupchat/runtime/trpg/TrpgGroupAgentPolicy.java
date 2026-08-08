package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
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
import com.me.galchat.tool.KpDiceTools;
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

    @Override
    public GroupModelInvocation prepare(GroupConversation conversation, GroupActionSpec action,
                                        GroupContextMaterial context) {
        GroupActorRef actor = action.actor();
        String name = actorName(conversation.getUserWorldId(), actor);
        boolean selectionPhase = GroupChatConstant.ACTION_TRPG_SCENE_SELECTION
                .equals(action.actionType());
        boolean scenePhase = GroupChatConstant.ACTION_TRPG_SCENE
                .equals(action.actionType());
        boolean sceneIntro = GroupChatConstant.ACTION_TRPG_SCENE_INTRO
                .equals(action.actionType());
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
                : GroupChatConstant.ACTION_TRPG_COMBAT.equals(action.actionType())
                ? "战斗" : "场景探索";
        List<CocDiceCharacterVO> cards = characterCardService.listDiceCharacters(
                conversation.getId());
        List<CocDiceCharacterVO> investigatorCards = cards.stream()
                .filter(card -> "PLAYER".equals(card.actorType())
                        || "BOT".equals(card.actorType()))
                .toList();
        String investigatorCardContext =
                characterCardFormatter.format(investigatorCards);
        List<Message> messages = new ArrayList<>();
        if (GroupChatConstant.ACTOR_KP.equals(actor.type())) {
            List<CocDiceCharacterVO> npcCards = cards.stream()
                    .filter(card -> "NPC".equals(card.actorType()))
                    .toList();
            messages.add(new SystemMessage(contextAssembler.baseSystemPrompt(conversation, actor) + "\n"
                    + investigatorCardContext + "\n"
                    + characterCardFormatter.formatNpcs(npcCards)
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
            messages.add(new SystemMessage(
                    investigatorContextAssembler.format(
                            conversation, action) + "\n"
                    + investigatorCardContext
                    + TrpgRulePrompts.investigatorResidentRules()
                    + """

                    你正在 TRPG 群聊中扮演%s，当前阶段是%s。
                    不得输出隐藏思考过程。
                    """.formatted(name, phase)));
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
                        读取紧邻的攻击行动，识别攻击者选择的准确目标，并判断目标是否需要获得闪避、反击或其他即时防守行动。
                        只输出一个JSON对象，不要Markdown，不要叙事：
                        {"targetName":"准确人物卡名称","insertDefense":true,"defenseOptions":["闪避","反击"],"reason":"简短原因"}
                        目标缺失、歧义、不在参战者中或行动不合法时不要猜测，改为：
                        {"error":"明确说明问题"}
                        """));
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
                        ? "选择公开上下文中的目标并描述行动；不要在此步骤裁定成败，也不要掷骰。"
                        + (combatAttack
                        ? " 在规则允许的范围内，不要尝试攻击昏迷/濒死的调查员。"
                        : "")
                        : scenePhase
                        ? "把上一次KP公开回复后的全部调查员发言视为一个共同场景提案；"
                        + "识别其中的联合行动、协助、兼容行动和冲突意图，以整个场景为单位统一裁定，"
                        + "不要按调查员逐条机械回复。需要掷骰时只调用一个对应工具；"
                        + "恢复同一步骤后继续处理共同提案中尚未裁定的部分。"
                        : "根据公开上下文裁定并行动；需要掷骰时只调用一个对应工具。")
                        + (scenePhase
                        ? """
                         确认当前场景应当结算时可调用finishSceneExploration，调用后仍要输出公开收束消息。
                        子场景仅用于调查员决定分头行动，且目的地在原模组中有实际描述的情况；
                        子场景不会加载更多模组信息，当前主场景及其全部子项始终已经包含在模组上下文中。
                        调查员一起行动时应保持在主场景；即使全员进入同一子场景也不会被拒绝，但不推荐这样做。
                        创建子场景的本次回复须在原有内容基础上明确说明哪些调查员去了哪里。
                        """
                        : combatAdjudicate
                        ? combatLifecycleService.adjudicationPrompt(
                                conversation.getId(), action)
                        + " 在没有剩余检定或掷骰需求、且应结束战斗时调用markCombatFinished；调用后继续输出完整公开裁定和收束。"
                        : "")));
            }
        } else {
            if (selectionPhase) {
                messages.add(new UserMessage("现在轮到" + name
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
                messages.add(new UserMessage("现在轮到" + name + "执行当前" + phase
                        + "行动。" + sceneParticipation
                        + "决策必须先于行动，并严格使用以下格式，标签外不得输出正文：\n"
                        + "<decision>一个完整自然语言段落，说明重要观察、线索联系、判断和本轮行动意图</decision>\n"
                        + "<action>该角色公开说出的话和采取的行动，不要输出发言者标签。"
                        + "公开行动通常只用一至两句；询问信息时直接说清对象和关键问题，"
                        + "问题数量压到完成当前意图所需的最少；不追加无关的动作描写、语气渲染、"
                        + "履历、自我评价、能力说明、重复理由或后续计划</action>\n"
                        + "action必须落实decision中的意图，不得重新选择目标；不得宣布未知事实、"
                        + "决定其他角色或NPC反应，也不得自行声明检定成功。"
                        + (combatDefense
                        ? combatLifecycleService.defensePrompt(action)
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
                    kpRunTools, kpCombatTools);
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
                    ? tools(kpDiceTools, kpModuleTools, kpSkillRuleTools,
                            kpRunTools, kpCombatTools)
                    : combatAttack || combatDefense || combatRoute
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

    @Override
    public String actorName(Long userWorldId, GroupActorRef actor) {
        if (GroupChatConstant.ACTOR_KP.equals(actor.type())) {
            return "KP";
        }
        return contextAssembler.actorName(userWorldId, actor);
    }
}

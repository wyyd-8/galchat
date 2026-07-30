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
import com.me.galchat.service.impl.TrpgInvestigatorContextAssembler;
import com.me.galchat.tool.KpDiceTools;
import com.me.galchat.tool.InvestigatorSceneTools;
import com.me.galchat.tool.KpSceneTools;
import com.me.galchat.tool.KpRunTools;
import com.me.galchat.tool.TrpgSceneSelectionTools;
import com.me.galchat.tool.KpSceneSelectionTools;
import com.me.galchat.tool.KpModuleTools;
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
    private final TrpgSceneSelectionTools sceneSelectionTools;
    private final KpSceneSelectionTools kpSceneSelectionTools;
    private final KpModuleTools kpModuleTools;
    private final InvestigatorSceneTools investigatorSceneTools;
    private final KpSceneTools kpSceneTools;
    private final KpRunTools kpRunTools;
    private final TrpgContextWindowService contextWindowService;
    private final TrpgInvestigatorContextAssembler investigatorContextAssembler;
    @Autowired
    private com.me.galchat.tool.KpCombatTools kpCombatTools;
    @Autowired
    private com.me.galchat.service.impl.TrpgCombatLifecycleService
            combatLifecycleService;

    public TrpgGroupAgentPolicy(@Qualifier("trpgGroupChatClient") ChatClient chatClient,
                                GroupContextAssembler contextAssembler,
                                ICharacterCardService characterCardService,
                                CharacterCardContextFormatter characterCardFormatter,
                                KpDiceTools kpDiceTools,
                                TrpgSceneSelectionTools sceneSelectionTools,
                                KpSceneSelectionTools
                                        kpSceneSelectionTools,
                                KpModuleTools kpModuleTools,
                                InvestigatorSceneTools investigatorSceneTools,
                                KpSceneTools kpSceneTools,
                                KpRunTools kpRunTools,
                                TrpgContextWindowService contextWindowService,
                                TrpgInvestigatorContextAssembler
                                        investigatorContextAssembler) {
        this.chatClient = chatClient;
        this.contextAssembler = contextAssembler;
        this.characterCardService = characterCardService;
        this.characterCardFormatter = characterCardFormatter;
        this.kpDiceTools = kpDiceTools;
        this.sceneSelectionTools = sceneSelectionTools;
        this.kpSceneSelectionTools = kpSceneSelectionTools;
        this.kpModuleTools = kpModuleTools;
        this.investigatorSceneTools = investigatorSceneTools;
        this.kpSceneTools = kpSceneTools;
        this.kpRunTools = kpRunTools;
        this.contextWindowService = contextWindowService;
        this.investigatorContextAssembler =
                investigatorContextAssembler;
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
        List<Message> messages = new ArrayList<>();
        if (GroupChatConstant.ACTOR_KP.equals(actor.type())) {
            String cardContext = characterCardFormatter.format(cards);
            messages.add(new SystemMessage(contextAssembler.baseSystemPrompt(conversation, actor) + "\n"
                    + cardContext + """

                    你是当前 TRPG 群聊唯一的KP，当前阶段是%s。KP不是可见的调查员。
                    你负责描述场景、裁定规则并在需要时发起掷骰；不得替用户决定调查员行动。
                    每次响应最多调用一个会改变状态的掷骰工具，且不得与其他工具并行调用。
                    调用掷骰工具后本次响应会暂停；稍后恢复同一步骤时，再根据骰点结果继续裁定。
                    不得输出隐藏思考过程。
                    """.formatted(phase)));
        } else {
            messages.add(new SystemMessage(
                    investigatorContextAssembler.format(
                            conversation, action) + """

                    你正在 TRPG 群聊中扮演%s，当前阶段是%s。
                    只能基于可见场景事实行动；不得替其他角色或用户决定行动，不得把推测写成已确认事实。
                    不得输出隐藏思考过程。
                    """.formatted(name, phase)));
        }
        messages.addAll(context.messages());
        if (GroupChatConstant.ACTOR_KP.equals(actor.type())) {
            if (selectionPhase) {
                messages.add(new UserMessage("""
                        现在轮到KP开始选景。根据地点标题索引和当前剧情，调用publishExplorationScenes提交当天能够探索的准确地点名称列表。
                        只提交此刻合理开放的地点，不强制限制地点数量或探索时长；不得输出地点ID。
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
                        : "根据公开上下文裁定并行动；需要掷骰时只调用一个对应工具。")
                        + (scenePhase
                        ? "确认当前场景应当结算时可调用finishSceneExploration，调用后仍要输出公开收束消息。"
                        : combatAdjudicate
                        ? (combatLifecycleService == null ? ""
                        : combatLifecycleService.adjudicationPrompt(
                                conversation.getId(), action))
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
                messages.add(new UserMessage("现在轮到" + name + "执行当前" + phase
                        + "行动。决策必须先于行动，并严格使用以下格式，标签外不得输出正文：\n"
                        + "<decision>一个完整自然语言段落，说明重要观察、线索联系、判断和本轮行动意图</decision>\n"
                        + "<action>该角色公开说出的话和采取的行动，不要输出发言者标签</action>\n"
                        + "action必须落实decision中的意图，不得重新选择目标；不得宣布未知事实、"
                        + "决定其他角色或NPC反应，也不得自行声明检定成功。"
                        + (combatDefense
                        && combatLifecycleService != null
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
            tools = selectionPhase
                    ? List.of(
                            kpSceneSelectionTools,
                            kpModuleTools, kpRunTools)
                    : scenePhase
                    ? tools(kpDiceTools, kpModuleTools, kpSceneTools,
                            kpRunTools, kpCombatTools)
                    : combatAdjudicate
                    ? tools(kpDiceTools, kpModuleTools, kpRunTools,
                            kpCombatTools)
                    : combatAttack || combatDefense || combatRoute
                    ? List.of()
                    : tools(kpDiceTools, kpModuleTools, kpRunTools);
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

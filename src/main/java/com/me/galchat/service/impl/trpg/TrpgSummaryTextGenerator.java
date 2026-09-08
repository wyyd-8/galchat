package com.me.galchat.service.impl.trpg;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class TrpgSummaryTextGenerator {

    private final ChatClient summaryClient;

    public TrpgSummaryTextGenerator(
            @Qualifier("groupNonThinkingChatClient")
            ChatClient summaryClient) {
        this.summaryClient = summaryClient;
    }

    public String summarize(String history) {
        return summaryClient.prompt(new Prompt(List.of(
                        new SystemMessage("""
                                你负责总结刚结束的一段COC场景探索。
                                输入中可能包含已经生成的子场景摘要；把它们与主场景记录合并为一份连贯摘要。
                                只记录已发生的重要行动、公开发现、已展示材料、人物状态变化和未解决问题。
                                保留子场景“剧情经过”中已确认事件的因果关系、实际结果、物品交接和已达成的约定。
                                必须区分行动意图、行动尝试与实际结果；未执行的移动计划直接省略，不得把失败或未确定结果写成成功。
                                调查员声明“我要去某地”不代表已经抵达；只有KP明确确认实际发生的移动、抵达或会合才能写成历史事件。
                                约定只代表已作出承诺，不代表承诺的行动已经执行。KP转述的意图、猜测以及材料中的记载也不得写成当前已发生事件。
                                所有经过均属于已结束场景的历史，不得据此推断当前地点或当前参与者；当前状态以系统运行时数据为准。
                                不得加入模组隐藏真相或尚未公开的信息，不输出标题和分析过程。
                                """),
                        new UserMessage(history))))
                .call()
                .content();
    }

    public String summarizeChildClues(String evidence) {
        return summaryClient.prompt(new Prompt(List.of(
                        new SystemMessage("""
                                你负责从刚结束的COC子场景候选证据中只提取可用线索。
                                线索必须是输入中KP已经公开描述或已经展示的材料内容。
                                不得记录人物行动、移动、抵达、离开、会合、购买、检定过程、对话过程或后续计划。
                                不得推断输入之外的信息，不得把意图、猜测或计划写成事实。
                                每条线索使用“- ”开头；没有可用线索时只输出“无”。
                                不输出参与者、时间、地点、标题或分析过程。
                                """),
                        new UserMessage(evidence))))
                .call()
                .content();
    }

    public String summarizeChildPlot(String evidence) {
        return summaryClient.prompt(new Prompt(List.of(
                        new SystemMessage("""
                                你负责从刚结束的COC子场景候选证据中提取剧情经过。
                                只记录输入中KP已经公开确认发生的关键事件，保留因果关系、实际结果、物品交接和已达成的约定。
                                输入可能包含同一场景中的战斗结果和战后叙述，必须保留战斗最终结果、获救者、伤势、装备去向和人员分散状态。
                                战斗结果汇总与逐条叙述重复时合并去重，按时间顺序保留关键转折及最终结果，不逐次复述每次攻击。
                                必须区分行动意图、行动尝试与实际结果；未执行的移动计划直接省略，不得把失败或未确定结果写成成功。
                                KP的“决定离开”仍只表示决定，不能据此写成已经离开、已经抵达或已经会合。
                                调查员声明“我要去某地”不代表已经抵达；即使KP转述了这句话，也不得记为移动、抵达或会合。
                                只有KP明确确认实际发生的移动、抵达或会合才能写成历史事件。约定只代表已作出承诺，不代表承诺的行动已经执行。
                                例如：KP描述“艾琳说要去仓库，但仍在钟楼”，不得写成“艾琳抵达仓库”；“说服守卫失败”不得写成“说服了守卫”。
                                材料中的记载只属于材料内容，不得当作调查员在本场景实际经历的事件。
                                所有经过均属于已结束场景的历史，不得据此推断当前地点或当前参与者；当前状态以系统运行时数据为准。
                                不得推断输入之外的信息，不得把意图、猜测或计划写成事实，不得加入模组隐藏真相或尚未公开的信息。
                                每条经过使用“- ”开头；没有已确认的关键事件时只输出“无”。不输出参与者、时间、地点、标题或分析过程。
                                """),
                        new UserMessage(evidence))))
                .call()
                .content();
    }
}

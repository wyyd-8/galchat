package com.me.galchat.service.impl;

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
}

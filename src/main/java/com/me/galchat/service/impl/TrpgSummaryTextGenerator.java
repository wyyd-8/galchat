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
}

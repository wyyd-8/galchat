package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.dto.TrpgCompletionModels.*;
import com.me.galchat.exception.UserRequestException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.HashSet;

@Component
public class TrpgCompletionGenerator {
    private final ChatClient client;
    private final ObjectMapper mapper;

    public TrpgCompletionGenerator(@Qualifier("groupNonThinkingChatClient") ChatClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public Overview generate(Materials materials) {
        StringBuilder sources = new StringBuilder();
        for (int i = 0; i < materials.sources().size(); i++) {
            sources.append("[sourceIndex=").append(i).append("]\n")
                    .append(materials.sources().get(i).text()).append("\n\n");
        }
        String response = client.prompt().system("""
                你为一场已经完成的COC跑团撰写完成报告。只依据输入的全部主场景公开摘要。
                summary是适合长期检索的最终概要，包含起因、重要行动、结果、状态变化、未解决问题。
                ending是面向玩家的团队结局，约100至180字，交代这群调查员共同经历与最终结果，有收束感。
                不评判玩家贡献，不编造事件，不揭示未发现的幕后真相，不把猜测或未知命运写成确定事实。
                journey从摘要中按时间顺序选3至5个不同来源；不足3个时全部使用，无来源时输出空数组。
                sourceIndex必须使用给定编号。title是不超过16字的阶段标题。
                excerpt必须逐字连续摘录该来源中的80至160字，短来源可更短，不得拼接、改写或添加省略号。
                只输出JSON：{"summary":"最终概要","ending":"团队结局","journey":[{"sourceIndex":0,"title":"阶段标题","excerpt":"摘要原文节选"}]}
                """).user("跑团：" + materials.title() + "\n公开摘要：\n" + sources
                        ).call().content();
        try {
            if (response == null) throw invalid();
            int start = response.indexOf('{'), end = response.lastIndexOf('}');
            if (start < 0 || end <= start) throw invalid();
            return validate(materials, mapper.readValue(response.substring(start, end + 1), Overview.class));
        } catch (tools.jackson.core.JacksonException exception) {
            throw invalid();
        }
    }

    Overview validate(Materials materials, Overview overview) {
        if (overview == null || !StringUtils.hasText(overview.summary())
                || !StringUtils.hasText(overview.ending()) || overview.journey() == null
                || overview.journey().size() < Math.min(3, materials.sources().size())
                || overview.journey().size() > 5) throw invalid();
        var seen = new HashSet<Integer>();
        for (Chapter chapter : overview.journey()) {
            if (chapter == null || chapter.sourceIndex() < 0 || chapter.sourceIndex() >= materials.sources().size()
                    || !seen.add(chapter.sourceIndex()) || !StringUtils.hasText(chapter.title())
                    || !StringUtils.hasText(chapter.excerpt())
                    || !materials.sources().get(chapter.sourceIndex()).text().contains(chapter.excerpt())) throw invalid();
        }
        return new Overview(overview.summary().trim(), overview.ending().trim(), overview.journey().stream()
                .sorted(Comparator.comparingInt(Chapter::sourceIndex)).toList());
    }

    private UserRequestException invalid() {
        return new UserRequestException("完成报告生成失败，请重试收尾");
    }
}

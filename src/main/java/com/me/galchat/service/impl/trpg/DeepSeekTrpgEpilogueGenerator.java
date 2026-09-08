package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.dto.TrpgEpilogueModels;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserRequestException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;

@Component
public class DeepSeekTrpgEpilogueGenerator
        implements TrpgEpilogueGenerator {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public DeepSeekTrpgEpilogueGenerator(
            @Qualifier("groupNonThinkingChatClient") ChatClient chatClient,
            ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public TrpgEpilogueModels.Response generate(
            GroupConversation conversation,
            List<TrpgEpilogueModels.Subject> subjects,
            String publicHistory) {
        String response = chatClient.prompt()
                .system("""
                        你是COC跑团的KP，负责在正篇结束后为每位调查员撰写独立的人物后传。
                        每位调查员必须恰好输出一段，使用第三人称。只写该角色此后的经历，或者其离场后直接围绕该角色发生的事情。
                        不总结模组，不单独描述世界、地点或NPC的结局，不揭示未发现的幕后真相，不评价玩家表现，不安排新的行动、检定或冒险。
                        必须依据已经公开发生的事实、人物最终状态、背景和实际选择；不得把猜测写成事实，不得改写已经确认的命运。
                        对存活角色，可写生活、关系、职业和心理变化。对死亡角色，可写葬礼、遗物去向、亲友记忆和身后影响，但不得写成其本人的主观经历；除非公开事实已确认死后意识，否则不得让死者继续感知或行动。
                        对失踪或命运不明的角色，只写已确认的最后踪迹、寻找过程或传闻，不擅自确认生死或真实去向。
                        其他人和地点只能在与当前角色的后传直接相关时出现。每段简洁、完整，有明确收束感。另为每人输出一句不超过60字的lead，概括该人物后传。
                        只输出一个JSON对象，不要Markdown或解释。格式严格为：
                        {"entries":[{"characterId":1,"investigatorName":"调查员姓名","lead":"后传短句","content":"人物后传"}]}
                        characterId必须与输入一致，不得遗漏、重复或添加调查员。
                        """)
                .user("""
                        跑团标题：%s
                        调查员最终资料：
                        %s
                        已公开的跑团记录：
                        %s
                        """.formatted(
                        conversation == null ? "" : conversation.getTitle(),
                        subjects, publicHistory == null ? "" : publicHistory))
                .call()
                .content();
        return parse(response);
    }

    private TrpgEpilogueModels.Response parse(String response) {
        if (response == null || response.isBlank()) {
            throw generationFailure();
        }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw generationFailure();
        }
        try {
            TrpgEpilogueModels.Response parsed = objectMapper.readValue(
                    response.substring(start, end + 1),
                    TrpgEpilogueModels.Response.class);
            if (parsed == null) {
                throw generationFailure();
            }
            return parsed;
        } catch (JacksonException exception) {
            throw generationFailure();
        }
    }

    private UserRequestException generationFailure() {
        return new UserRequestException("AI生成人物后传失败，请重试");
    }
}

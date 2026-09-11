package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.domain.dto.TrpgRunMemoryModels;
import com.me.galchat.service.impl.trpg.TrpgRunMemoryService;
import com.me.galchat.utils.TypeConvertUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TrpgRunMemoryTools {

    private final TrpgRunMemoryService memoryService;

    @Tool(description = """
            查看当前角色在当前用户世界中参与过的全部跑团简要信息。
            names 按“角色名:调查员名”返回，其中“用户:调查员名”明确表示当前对话用户控制的调查员。在单聊和普通群聊中，默认将其称为“你”或用户指定称呼，其他调查员使用对应角色实际名称；用户明确要求使用调查员名称时除外。本工具不返回会话标题，也不读取KP私密信息。
            返回内容只代表调用时的当前快照；读档或回撤后，历史与状态可能改变，后续引用前应重新调用。
            """)
    public TrpgRunMemoryModels.RunListResult listTrpgRuns(
            ToolContext context) {
        ContextIds ids = contextIds(context);
        return memoryService.listRuns(ids.userWorldId(), ids.characterId());
    }

    @Tool(description = """
            查看当前角色参与的一个指定跑团的当前完整公开状态，包括自己控制的调查员简要卡、非基准技能、自己的检定骰运统计和场景信息。
            只有已结束跑团才返回结束时间与跑团总结；已结束跑团不返回最近一条公开消息。本工具不返回标题、消息数量、骰点数量或KP私密信息。
            骰运统计只按当前角色自己的骰点等级汇总大成功、成功、失败、大失败，不表示群体检定是否通过或对抗检定是否获胜。
            返回内容只代表调用时的当前快照；读档或回撤后，历史、人物卡、骰运统计与场景状态可能改变，后续引用前应重新调用。
            """)
    public TrpgRunMemoryModels.RunDetails getTrpgRunDetails(
            @ToolParam(description = "跑团id") Long runId,
            ToolContext context) {
        ContextIds ids = contextIds(context);
        return memoryService.getRunDetails(
                ids.userWorldId(), ids.characterId(), runId);
    }

    @Tool(description = """
            在当前角色参与的一个指定跑团中按关键词检索完整公开聊天轮次，最多返回三轮。
            先按轮次向量召回，再以90%语义相关度加10%时间新近度排序；不使用重排模型。结果只包含公开聊天与公开掷骰展示，不读取KP私密信息。
            返回内容只代表调用时的当前快照；读档或回撤会改变可检索历史，后续引用前应重新调用。
            """)
    public TrpgRunMemoryModels.ChatSearchResult searchTrpgChatRounds(
            @ToolParam(description = "跑团id") Long runId,
            @ToolParam(description = "要检索的关键词或短语") String keyword,
            ToolContext context) {
        ContextIds ids = contextIds(context);
        return memoryService.searchChatRounds(
                ids.userWorldId(), ids.characterId(), runId, keyword);
    }

    private ContextIds contextIds(ToolContext context) {
        Map<String, Object> values = context == null
                || context.getContext() == null
                ? Map.of() : context.getContext();
        return new ContextIds(
                TypeConvertUtils.asLong(values.get(
                        ChatToolContextConstant.USER_WORLD_ID_KEY)),
                TypeConvertUtils.asLong(values.get(
                        ChatToolContextConstant.CHARACTER_ID_KEY)));
    }

    private record ContextIds(Long userWorldId, Long characterId) {
    }
}

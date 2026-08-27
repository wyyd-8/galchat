package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.service.impl.TrpgInvestigatorSuspensionService;
import com.me.galchat.utils.TypeConvertUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class KpSuspendedInvestigatorRecoveryTools {

    private final TrpgInvestigatorSuspensionService suspensionService;

    @Tool(name = "resumeSuspendedInvestigators", description = """
            当被停镜的调查员剧情现在适合重新主持时调用。恢复说明必须能够跨越历史压缩：结合工具保存的停镜前状态，用reentryContext明确写出停镜期间实际经历、当前状态以及重新入场的位置；没有发生其他事情时也要明确说明。不得补写未实际发生的个人行动。
            CURRENT_SCENE表示在本次回复中叙述其重新入场，并从下一轮加入当前场景；INDEPENDENT_SCENE表示为其排入独立恢复场景，必须提供sceneName。
            """)
    public String resumeSuspendedInvestigators(
            @ToolParam(description = "要恢复剧情线的准确调查员名称列表")
            List<String> investigatorNames,
            @ToolParam(description = "CURRENT_SCENE或INDEPENDENT_SCENE")
            String resumeMode,
            @ToolParam(description = "停镜期间实际经历、当前状态和重新入场位置")
            String reentryContext,
            @ToolParam(description = "仅INDEPENDENT_SCENE必填的场景名称")
            String sceneName,
            ToolContext context) {
        Map<String, Object> values =
                KpInvestigatorSuspensionTools.requireKpContext(context);
        return suspensionService.resumeSuspendedInvestigators(
                TypeConvertUtils.asLong(values.get(
                        ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY)),
                TypeConvertUtils.asLong(values.get(
                        ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY)),
                investigatorNames, resumeMode, reentryContext, sceneName);
    }
}

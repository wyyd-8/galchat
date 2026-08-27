package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
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
public class KpInvestigatorSuspensionTools {

    private final TrpgInvestigatorSuspensionService suspensionService;

    @Tool(name = "suspendInvestigators", description = """
            这是一个不常用的叙事镜头调度工具。核心判断是：这组调查员眼下是否还有适合立即主持的遭遇、选择或反馈？
            如果有，继续主持；物理分离但马上有独立行动时应使用子场景。只有其剧情线暂时没有适合展开的内容，且需要把叙事焦点切换镜头至其他调查员时，才调用本工具。
            适合示例：调查员被绑架并带往暂不揭示的地点；坍塌后被隔在另一侧且短期没有选择；战斗结束后被敌人带走；昏迷者被留在医院而队伍继续调查。
            不适合示例：昏迷者仍由队友背着并影响当前处境；受控角色仍是当前冲突焦点；分离者马上有可主持的遭遇或选择；只是已经行动过或想跳过一轮。
            不要仅因昏迷、受伤、受控或本轮无法行动而调用。调用后必须在本次公开叙述中交代停镜位置，再转向其他调查员；不得虚构停镜期间的个人行动。
            """)
    public String suspendInvestigators(
            @ToolParam(description = "暂时停止主持其剧情线的准确调查员名称列表")
            List<String> investigatorNames,
            @ToolParam(description = "这些调查员如何离开当前剧情线，以及镜头停下时的最后处境")
            String suspensionContext,
            ToolContext context) {
        Map<String, Object> values = requireKpContext(context);
        return suspensionService.suspendInvestigators(
                TypeConvertUtils.asLong(values.get(
                        ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY)),
                TypeConvertUtils.asLong(values.get(
                        ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY)),
                investigatorNames, suspensionContext);
    }

    static Map<String, Object> requireKpContext(ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("KP调查员悬置工具上下文不存在");
        }
        Map<String, Object> values = context.getContext();
        if (!GroupChatConstant.ACTOR_KP.equals(
                TypeConvertUtils.asString(values.get(
                        ChatToolContextConstant.ACTOR_TYPE_KEY)))) {
            throw new UserAuthException("只有KP可以管理调查员剧情悬置");
        }
        return values;
    }
}

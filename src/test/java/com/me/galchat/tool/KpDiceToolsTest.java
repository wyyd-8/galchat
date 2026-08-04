package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.KpDiceRequestDTOs;
import com.me.galchat.service.ICocDiceOrchestrationService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KpDiceToolsTest {

    @Test
    void exposesSixCombatSafeReturnDirectDiceTools() {
        List<Method> toolMethods = Arrays.stream(KpDiceTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .toList();

        assertThat(toolMethods)
                .extracting(method -> method.getAnnotation(Tool.class).name())
                .containsExactlyInAnyOrder(
                        DiceRollConstant.TOOL_REQUEST_CHECK,
                        DiceRollConstant.TOOL_REQUEST_OPPOSED_CHECK,
                        DiceRollConstant.TOOL_REQUEST_SAN_CHECK,
                        "rollSanLoss",
                        "rollDamage",
                        "rollHealing");
        assertThat(toolMethods)
                .allSatisfy(method -> assertThat(
                        method.getAnnotation(Tool.class).returnDirect()).isTrue());
    }

    @Test
    void damageAndHealingToolsExposeSimplifiedTableRules() {
        Tool damage = tool("rollDamage");
        Tool healing = tool("rollHealing");

        assertThat(damage.description())
                .contains("KP手动")
                .contains("护甲")
                .contains("不会自动扣除");
        assertThat(healing.description())
                .contains("急救", "解除昏迷", "解除重伤")
                .contains("医学", "解除重伤")
                .contains("一个大场景")
                .contains("每种恢复生命方法", "一次");
    }

    @Test
    void pushedCheckIsAnIndependentExplorationOnlyTool() {
        List<Method> toolMethods = Arrays.stream(
                        KpPushedCheckTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .toList();

        assertThat(toolMethods).singleElement().satisfies(method -> {
            Tool tool = method.getAnnotation(Tool.class);
            assertThat(tool.name()).isEqualTo(
                    DiceRollConstant.TOOL_REQUEST_PUSHED_CHECK);
            assertThat(tool.returnDirect()).isTrue();
        });
    }

    @Test
    void rejectsNonKpToolContext() {
        KpDiceTools tools = new KpDiceTools(mock(ICocDiceOrchestrationService.class));
        ToolContext context = new ToolContext(Map.of(
                ChatToolContextConstant.ACTOR_TYPE_KEY,
                GroupChatConstant.ACTOR_CHARACTER,
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY,
                7L,
                ChatToolContextConstant.USER_WORLD_ID_KEY,
                5L,
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY,
                41L));

        assertThatThrownBy(() -> tools.requestSanCheck(
                new KpDiceRequestDTOs.SanCheck("目睹尸体", List.of("林恩")),
                context))
                .hasMessageContaining("KP");
    }

    @Test
    void readsConversationAndRunOnlyFromValidatedContext() {
        ICocDiceOrchestrationService orchestration =
                mock(ICocDiceOrchestrationService.class);
        KpDiceTools tools = new KpDiceTools(orchestration);
        ToolContext context = new ToolContext(Map.of(
                ChatToolContextConstant.ACTOR_TYPE_KEY,
                GroupChatConstant.ACTOR_KP,
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY,
                7L,
                ChatToolContextConstant.USER_WORLD_ID_KEY,
                5L,
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY,
                41L));
        KpDiceRequestDTOs.SanCheck request =
                new KpDiceRequestDTOs.SanCheck("目睹尸体", List.of("林恩"));

        tools.requestSanCheck(request, context);

        verify(orchestration).requestSanCheck(7L, 7L, request);
    }

    private Tool tool(String name) {
        return Arrays.stream(KpDiceTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Tool.class))
                .filter(java.util.Objects::nonNull)
                .filter(tool -> name.equals(tool.name()))
                .findFirst()
                .orElseThrow();
    }
}

package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.CocPercentileModifier;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.constant.FirearmFiringMode;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.KpFirearmRequestDTOs;
import com.me.galchat.service.ICocDiceOrchestrationService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KpFirearmToolsTest {

    @Test
    void schemaExposesOptionalShotgunDistanceBands() {
        String schema = ToolCallbacks.from(
                        new KpFirearmTools(null))[0]
                .getToolDefinition().inputSchema();

        assertThat(schema)
                .contains("\"distance\"")
                .contains("NEAR", "MEDIUM", "FAR")
                .contains("\"required\" : [ \"bulletCount\", \"targetCharacterName\" ]");
    }

    @Test
    void schemaAsksKpToExplainTargetBaseModifiers() {
        String schema = ToolCallbacks.from(
                        new KpFirearmTools(null))[0]
                .getToolDefinition().inputSchema();

        assertThat(schema).contains("\"baseModifierReason\"");
    }

    @Test
    void exposesDedicatedReturnDirectFirearmToolAndForwardsKpContext() {
        ICocDiceOrchestrationService orchestration =
                mock(ICocDiceOrchestrationService.class);
        KpFirearmTools tools = new KpFirearmTools(orchestration);
        KpFirearmRequestDTOs.Attack request =
                new KpFirearmRequestDTOs.Attack(
                        "手枪连射",
                        "林恩",
                        "左轮手枪",
                        FirearmFiringMode.HANDGUN_MULTIPLE,
                        false,
                        List.of(new KpFirearmRequestDTOs.Target(
                                "邪教徒", 3,
                                CocPercentileModifier.NORMAL)));
        ToolContext context = new ToolContext(Map.of(
                ChatToolContextConstant.ACTOR_TYPE_KEY,
                GroupChatConstant.ACTOR_KP,
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY,
                7L,
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY,
                41L));

        tools.requestFirearmAttack(request, context);

        Tool annotation = java.util.Arrays.stream(
                        KpFirearmTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Tool.class))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow();
        assertThat(annotation.name()).isEqualTo(
                DiceRollConstant.TOOL_REQUEST_FIREARM_ATTACK);
        assertThat(annotation.returnDirect()).isTrue();
        verify(orchestration).requestFirearmAttack(7L, 7L, request);
    }
}

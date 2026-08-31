package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.CocPercentileModifier;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.constant.MeleeDefenseMode;
import com.me.galchat.domain.dto.KpMeleeRequestDTOs;
import com.me.galchat.service.ICocDiceOrchestrationService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KpMeleeToolsTest {

    @Test
    void schemaAsksKpToExplainAttackerAndDefenderModifiers() {
        String schema = ToolCallbacks.from(
                        new KpMeleeTools(null))[0]
                .getToolDefinition().inputSchema();

        assertThat(schema).contains("\"modifierReason\"");
    }

    @Test
    void exposesDedicatedReturnDirectMeleeToolAndForwardsKpContext() {
        ICocDiceOrchestrationService orchestration =
                mock(ICocDiceOrchestrationService.class);
        KpMeleeTools tools = new KpMeleeTools(orchestration);
        KpMeleeRequestDTOs.Attack request = new KpMeleeRequestDTOs.Attack(
                "用折刀刺击",
                new KpMeleeRequestDTOs.Attacker(
                        "林恩", "小型刀具", CocPercentileModifier.BONUS_1),
                new KpMeleeRequestDTOs.Defender(
                        "邪教徒", MeleeDefenseMode.DODGE, null,
                        CocPercentileModifier.NORMAL));
        ToolContext context = new ToolContext(Map.of(
                ChatToolContextConstant.ACTOR_TYPE_KEY,
                GroupChatConstant.ACTOR_KP,
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY,
                7L,
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY,
                41L));

        tools.requestMeleeAttack(request, context);

        Tool annotation = java.util.Arrays.stream(
                        KpMeleeTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Tool.class))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow();
        assertThat(annotation.name()).isEqualTo(
                DiceRollConstant.TOOL_REQUEST_MELEE_ATTACK);
        assertThat(annotation.returnDirect()).isTrue();
        verify(orchestration).requestMeleeAttack(7L, 7L, request);
    }
}

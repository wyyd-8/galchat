package com.me.galchat.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;

class KpInvestigatorSuspensionToolsTest {

    @Test
    void suspensionPromptFramesTheToolAsAChangeOfNarrativeFocus() {
        Tool tool = tool(KpInvestigatorSuspensionTools.class,
                "suspendInvestigators");

        assertThat(tool.description())
                .contains("是否还有适合立即主持的遭遇、选择或反馈")
                .contains("切换镜头")
                .contains("绑架")
                .contains("子场景")
                .contains("不要仅因昏迷、受伤、受控或本轮无法行动");
    }

    @Test
    void recoveryPromptRequiresACompressionSafeNarrativeBridge() {
        Tool tool = tool(KpSuspendedInvestigatorRecoveryTools.class,
                "resumeSuspendedInvestigators");

        assertThat(tool.description())
                .contains("停镜前")
                .contains("停镜期间实际经历")
                .contains("重新入场")
                .doesNotContain("哪些事实已经公开")
                .doesNotContain("被其本人知道");
    }

    private Tool tool(Class<?> type, String methodName) {
        return java.util.Arrays.stream(type.getMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .orElseThrow()
                .getAnnotation(Tool.class);
    }
}

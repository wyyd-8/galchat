package com.me.galchat.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;

class KpChildSceneToolsTest {

    @Test
    void descriptionRequiresRoutingWithoutNarratingDestinationContent() {
        Tool tool = startChildSceneTool();

        assertThat(tool.description())
                .contains("声明希望前往当前场景的不同地区时，必须调用")
                .contains("只能出现")
                .contains("前往")
                .contains("不能涉及新场景具体内容");
    }

    @Test
    void descriptionEncouragesOneCallForEachDifferentDestination() {
        Tool tool = startChildSceneTool();

        assertThat(tool.description())
                .contains("允许且推荐")
                .contains("不同场景")
                .contains("多次调用");
    }

    private Tool startChildSceneTool() {
        try {
            return KpChildSceneTools.class
                    .getMethod("startChildScene", String.class,
                            java.util.List.class,
                            org.springframework.ai.chat.model.ToolContext.class)
                    .getAnnotation(Tool.class);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }
}

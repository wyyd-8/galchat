package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.service.impl.TrpgSceneSelectionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TrpgSceneSelectionToolsTest {

    @Test
    void investigatorSelectionToolIsDirectAndPassesOnlyTheNumber() throws Exception {
        TrpgSceneSelectionService service =
                mock(TrpgSceneSelectionService.class);
        TrpgSceneSelectionTools tools =
                new TrpgSceneSelectionTools(service);
        ToolContext context = new ToolContext(Map.of(
                ChatToolContextConstant.ACTOR_TYPE_KEY,
                GroupChatConstant.ACTOR_CHARACTER,
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY,
                7L,
                ChatToolContextConstant.GROUP_TURN_ID_KEY,
                30L,
                ChatToolContextConstant.CHARACTER_ID_KEY,
                9L));

        tools.selectExplorationScene("2", context);

        verify(service).selectOption(
                7L, 30L,
                new com.me.galchat.groupchat.runtime.GroupActorRef(
                        GroupChatConstant.ACTOR_CHARACTER, 9L),
                "2");
        Tool annotation = TrpgSceneSelectionTools.class
                .getMethod("selectExplorationScene",
                        String.class, ToolContext.class)
                .getAnnotation(Tool.class);
        assertThat(annotation.returnDirect()).isTrue();
    }

    @Test
    void kpPublishesLocationNamesThroughADirectTool() throws Exception {
        TrpgSceneSelectionService service =
                mock(TrpgSceneSelectionService.class);
        KpSceneSelectionTools tools =
                new KpSceneSelectionTools(service);
        ToolContext context = new ToolContext(Map.of(
                ChatToolContextConstant.ACTOR_TYPE_KEY,
                GroupChatConstant.ACTOR_KP,
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY,
                7L,
                ChatToolContextConstant.GROUP_TURN_ID_KEY,
                30L,
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY,
                31L));

        tools.publishExplorationScenes(
                List.of("餐厅", "后院"),
                2, "AFTERNOON", context);

        verify(service).publishOptions(
                7L, 30L, 31L,
                List.of("餐厅", "后院"),
                2, "AFTERNOON");
        Tool annotation = KpSceneSelectionTools.class
                .getMethod("publishExplorationScenes",
                        List.class, Integer.class,
                        String.class, ToolContext.class)
                .getAnnotation(Tool.class);
        assertThat(annotation.returnDirect()).isTrue();
    }
}

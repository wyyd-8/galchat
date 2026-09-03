package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.domain.dto.TrpgRunMemoryModels;
import com.me.galchat.service.impl.TrpgRunMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgRunMemoryToolsTest {

    @Test
    void forwardsTheCurrentWorldAndCharacterToEveryMemoryOperation() {
        TrpgRunMemoryService service = mock(TrpgRunMemoryService.class);
        TrpgRunMemoryTools tools = new TrpgRunMemoryTools(service);
        ToolContext context = new ToolContext(Map.of(
                ChatToolContextConstant.USER_WORLD_ID_KEY, 5L,
                ChatToolContextConstant.CHARACTER_ID_KEY, 9L));
        var list = new TrpgRunMemoryModels.RunListResult(
                LocalDateTime.now(), "快照", List.of());
        var details = mock(TrpgRunMemoryModels.RunDetails.class);
        var search = new TrpgRunMemoryModels.ChatSearchResult(
                71L, "仓库", LocalDateTime.now(), "快照", List.of());
        when(service.listRuns(5L, 9L)).thenReturn(list);
        when(service.getRunDetails(5L, 9L, 71L)).thenReturn(details);
        when(service.searchChatRounds(5L, 9L, 71L, "仓库"))
                .thenReturn(search);

        assertThat(tools.listTrpgRuns(context)).isSameAs(list);
        assertThat(tools.getTrpgRunDetails(71L, context)).isSameAs(details);
        assertThat(tools.searchTrpgChatRounds(71L, "仓库", context))
                .isSameAs(search);
        verify(service).listRuns(5L, 9L);
        verify(service).getRunDetails(5L, 9L, 71L);
        verify(service).searchChatRounds(5L, 9L, 71L, "仓库");
    }

    @Test
    void toolPromptWarnsThatResultsAreCurrentSnapshots() throws Exception {
        for (String methodName : List.of(
                "listTrpgRuns", "getTrpgRunDetails",
                "searchTrpgChatRounds")) {
            Method method = java.util.Arrays.stream(
                            TrpgRunMemoryTools.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst().orElseThrow();
            assertThat(method.getAnnotation(Tool.class).description())
                    .contains("当前快照", "读档", "回撤");
        }
    }
}

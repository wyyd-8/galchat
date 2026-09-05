package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.dto.TrpgEpilogueModels;
import com.me.galchat.domain.po.GroupConversation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeepSeekTrpgEpilogueGeneratorTest {

    @Test
    void generatesOnlyCharacterFocusedAfterstoriesForLivingAndDeadInvestigators() {
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        when(chatModel.getOptions()).thenReturn(DeepSeekChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"entries":[
                  {"characterId":11,"investigatorName":"林恩","content":"林恩重新回到了报社。"},
                  {"characterId":12,"investigatorName":"威廉","content":"威廉的笔记被妹妹保存了下来。"}
                ]}
                """));
        DeepSeekTrpgEpilogueGenerator generator =
                new DeepSeekTrpgEpilogueGenerator(
                        ChatClient.builder(chatModel).build(),
                        JsonMapper.builder().build());

        TrpgEpilogueModels.Response result = generator.generate(
                new GroupConversation().setId(7L).setTitle("古树之中"),
                List.of(subject(11L, "林恩", false),
                        subject(12L, "威廉", true)),
                "[kp] 威廉没能离开燃烧的庄园。");

        assertThat(result.entries())
                .extracting(TrpgEpilogueModels.Entry::characterId)
                .containsExactly(11L, 12L);
        Prompt prompt = capturedPrompt(chatModel);
        String systemPrompt = prompt.getInstructions().getFirst().getText();
        assertThat(systemPrompt)
                .contains("每位调查员", "人物后传", "第三人称")
                .contains("不总结模组", "不揭示未发现的幕后真相")
                .contains("死亡角色", "不得写成其本人的主观经历")
                .contains("失踪", "不擅自确认生死")
                .contains("只输出一个JSON对象");
        String userPrompt = prompt.getInstructions().getLast().getText();
        assertThat(userPrompt)
                .contains("林恩", "威廉", "dead=true")
                .contains("威廉没能离开燃烧的庄园");
    }

    private TrpgEpilogueModels.Subject subject(
            Long id, String name, boolean dead) {
        return new TrpgEpilogueModels.Subject(
                id, name, "记者", dead ? 0 : 7, 10, 42, 70,
                dead, false, false, false, false,
                null, null, "真相应当被记录",
                "妹妹", null, "随身笔记", "谨慎", null);
    }

    private Prompt capturedPrompt(DeepSeekChatModel model) {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(captor.capture());
        return captor.getValue();
    }

    private ChatResponse response(String json) {
        return new ChatResponse(List.of(new Generation(
                new org.springframework.ai.chat.messages.AssistantMessage(json))));
    }
}

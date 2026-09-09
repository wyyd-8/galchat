package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.dto.TrpgCompletionModels.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class TrpgCompletionGeneratorTest {
    final TrpgCompletionGenerator generator = new TrpgCompletionGenerator( JsonMapper.builder().build());
    final Materials materials = new Materials("灯塔", null, 42, 3,
            List.of(new Source(1, 10, "旅馆里找到信件。"), new Source(11, 42, "共同走出迷雾。")), List.of(), List.of(), List.of());

    @Test
    void sendsReportPromptToTheSuppliedKpClient() {
        var model = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        org.mockito.Mockito.when(model.getOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().build());
        org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage("""
                        {"summary":"概要","ending":"结局","journey":[
                          {"sourceIndex":0,"title":"来信","excerpt":"找到信件"},
                          {"sourceIndex":1,"title":"离开","excerpt":"走出迷雾"}]}
                        """)))));
        var result = generator.generate(org.springframework.ai.chat.client.ChatClient.builder(model).build(), materials);
        assertThat(result.ending()).isEqualTo("结局");
        org.mockito.Mockito.verify(model).call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test
    void realExcerptsAreSortedChronologicallyAndShortRunsKeepAvailableChapters() {
        var result = generator.validate(materials, new Overview("概要", "结局", List.of(
                new Chapter(1, "离开", "走出迷雾"), new Chapter(0, "来信", "找到信件"))));
        assertThat(result.journey()).extracting(Chapter::sourceIndex).containsExactly(0, 1);
    }

    @Test
    void fabricatedExcerptUnknownSourceAndDuplicateSourceAreRejected() {
        for (Chapter bad : List.of(new Chapter(0, "来信", "编造的调查过程"), new Chapter(2, "来信", "找到信件"),
                new Chapter(1, "重复", "共同走出迷雾。"))) {
            assertThatThrownBy(() -> generator.validate(materials, new Overview("概要", "结局", List.of(
                    new Chapter(1, "离开", "走出迷雾"), bad)))).hasMessageContaining("完成报告生成失败");
        }
    }
}

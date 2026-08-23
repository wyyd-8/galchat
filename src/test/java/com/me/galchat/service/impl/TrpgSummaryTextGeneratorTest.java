package com.me.galchat.service.impl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgSummaryTextGeneratorTest {

    @Test
    void childClueRequestForbidsActionsAndUsesOnlyProvidedEvidence() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt(any(Prompt.class)).call().content())
                .thenReturn("- 门锁上有划痕");
        clearInvocations(client);
        TrpgSummaryTextGenerator generator =
                new TrpgSummaryTextGenerator(client);

        String result = generator.summarizeChildClues(
                "[KP叙述]\n门锁上有划痕");

        assertThat(result).isEqualTo("- 门锁上有划痕");
        ArgumentCaptor<Prompt> prompt =
                ArgumentCaptor.forClass(Prompt.class);
        verify(client).prompt(prompt.capture());
        assertThat(prompt.getValue().getInstructions())
                .hasSize(2)
                .anySatisfy(message -> assertThat(message.getText())
                        .contains("只提取可用线索")
                        .contains("不得记录人物行动")
                        .contains("不得推断输入之外的信息"))
                .anySatisfy(message -> assertThat(message.getText())
                        .isEqualTo("[KP叙述]\n门锁上有划痕"));
    }
}

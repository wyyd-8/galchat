package com.me.galchat.service.impl;

import com.me.galchat.constant.CocWeaponCatalogConstant;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterWeapon;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeepSeekImportedWeaponAuditModelTest {

    @Test
    void matchesOnlyByOriginalNameAndReturnsCatalogCode() {
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"weapons":[{"weaponId":81,"catalogCode":"PISTOL_22_AUTO"}]}
                """));
        DeepSeekImportedWeaponAuditModel model =
                new DeepSeekImportedWeaponAuditModel(
                        ChatClient.builder(chatModel).build(),
                        new CharacterCardGenerationResponseParser(
                                JsonMapper.builder().build()));

        var result = model.review(
                new CocCharacter().setName("林恩").setEra("1920s")
                        .setOccupation("记者"),
                List.of(new CocCharacterWeapon().setId(81L)
                        .setName("袖珍手枪").setDamage("4D10")
                        .setRange("15m")),
                List.of(
                        CocWeaponCatalogConstant.require("PISTOL_22_AUTO"),
                        CocWeaponCatalogConstant.require("REVOLVER_38_9MM")),
                Map.of("手枪", "PISTOL_38_9MM"));

        assertThat(result.weapons()).singleElement().satisfies(review -> {
            assertThat(review.weaponId()).isEqualTo(81L);
            assertThat(review.catalogCode()).isEqualTo("PISTOL_22_AUTO");
        });
        Prompt prompt = capturedPrompt(chatModel);
        String systemPrompt = prompt.getInstructions().getFirst().getText();
        assertThat(systemPrompt)
                .contains("只根据", "武器名称", "catalogCode", "保留原名称")
                .contains("宽泛类型", "固定默认映射")
                .contains("无法可靠匹配", "JSON的null", "不要返回字符串\"null\"")
                .contains("LARGE_CLUB", "未识别武器")
                .doesNotContain("abnormal", "riskTags", "skillName");
        assertThat(prompt.getInstructions().getLast().getText())
                .contains("1920s", "袖珍手枪", "PISTOL_22_AUTO", ".22自动手枪")
                .contains("手枪=PISTOL_38_9MM")
                .doesNotContain("4D10", "15m", "damage", "range");
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

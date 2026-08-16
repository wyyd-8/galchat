package com.me.galchat.service.impl;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeepSeekImportedWeaponAuditModelTest {

    @Test
    void usesSimpleDamageBandsWithoutExemptingRulebookWeapons() {
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"weapons":[{"weaponId":81,"abnormal":true,
                "riskTags":["伤害异常","显眼","高噪声"]}]}
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
                        .setName("袖珍手枪").setSkillName("射击:手枪")
                        .setDamage("4D10").setRange("15m")));

        assertThat(result.weapons()).singleElement().satisfies(review -> {
            assertThat(review.weaponId()).isEqualTo(81L);
            assertThat(review.abnormal()).isTrue();
            assertThat(review.riskTags()).contains("伤害异常");
        });
        Prompt prompt = capturedPrompt(chatModel);
        String systemPrompt = prompt.getInstructions().getFirst().getText();
        assertThat(systemPrompt)
                .contains("枪械", "伤害公式明显高于", "abnormal=true", "伤害异常");
        assertThat(systemPrompt)
                .contains("常规近战、弓弩与投掷武器", "通常为1D3至1D8", "达到2D8")
                .contains("手枪", "通常为1D6至1D10+2")
                .contains("步枪、突击步枪与机枪", "通常为2D6至2D6+4")
                .contains("霰弹枪", "近距离通常为2D6至4D6", "随距离递减")
                .contains("冲锋枪", "通常为1D8至1D10+2")
                .contains("爆炸物与重武器", "约为2D6至4D10", "6D10及以上")
                .contains("规则书中正式列出的高伤害武器也必须判定为异常")
                .doesNotContain("特殊例外", "本身不是伤害异常", "不属于“伤害异常”");
        assertThat(prompt.getInstructions().getLast().getText())
                .contains("1920s", "记者", "袖珍手枪", "4D10");
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

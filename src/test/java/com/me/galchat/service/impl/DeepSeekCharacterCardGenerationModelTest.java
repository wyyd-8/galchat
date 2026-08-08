package com.me.galchat.service.impl;

import com.me.galchat.domain.dto.CharacterCardGenerationModels;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.vo.CharacterCardVO;
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

class DeepSeekCharacterCardGenerationModelTest {

    @Test
    void buildGenerationUsesOnlyPersonalityPrototypeAndCreatesANewInvestigatorIdentity() {
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"name":"埃莉诺·克劳福德","age":30,"sex":"女","birthplace":"波士顿",
                 "residence":"阿卡姆","occupation":"记者",
                 "attributeOrder":["INT","EDU","POW","APP","DEX","CON","SIZ","STR"],
                 "occupationSkillOrder":["图书馆使用","侦查","心理学","母语","历史","摄影","说服","神秘学","信用评级"],
                 "interestSkillOrder":["潜行","急救","汽车驾驶","斗殴","聆听","锁匠"],
                 "explanations":[]}
                """));
        DeepSeekCharacterCardGenerationModel generator = generator(chatModel);

        CharacterCardGenerationModels.BuildPlan result = generator.generateBuild(
                sourceTemplate(), module(), List.of("信用评级", "图书馆使用", "侦查"));

        assertThat(result.name()).isEqualTo("埃莉诺·克劳福德");
        Prompt prompt = capturedPrompt(chatModel);
        String systemPrompt = prompt.getInstructions().getFirst().getText();
        assertThat(systemPrompt)
                .contains("第1项=70", "第2至3项=60", "第4至6项=50", "第7至9项=40")
                .contains("财富、生活水平与社会地位", "40属于标准", "50、60、70属于小康")
                .contains("可以不进入本职技能排序", "保底值10");
        String userPrompt = prompt.getInstructions().getLast().getText();
        assertThat(userPrompt)
                .contains("冷静、谨慎、追求真相", "偏好调查与交涉")
                .doesNotContain("星际骑士伊莎贝尔", "曾在银河帝国担任皇家骑士");
    }

    @Test
    void backgroundGenerationUsesGeneratedInvestigatorInsteadOfSourceWorldIdentity() {
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"appearance":"总戴着一顶旧呢帽","ideology":"真相值得冒险",
                 "significantPeople":"编辑玛格丽特","meaningfulLocations":"波士顿公共图书馆",
                 "treasuredPossessions":"父亲的钢笔","traits":"冷静而执着",
                 "keyConnectionCategory":"SIGNIFICANT_PEOPLE","keyConnectionText":"编辑玛格丽特",
                 "weaponCode":null,"equipment":[]}
                """));
        DeepSeekCharacterCardGenerationModel generator = generator(chatModel);
        CocCharacter character = new CocCharacter()
                .setName("埃莉诺·克劳福德").setAge(30).setSex("女")
                .setOccupation("记者").setBirthplace("波士顿").setResidence("阿卡姆")
                .setStr(40).setCon(50).setSiz(50).setDex(50)
                .setApp(60).setIntValue(80).setPow(60).setEdu(70);
        CharacterCardVO card = new CharacterCardVO(
                character, List.of(), List.of(), new CocCharacterProfile());
        var rolls = new CharacterCardGenerationModels.BackgroundRolls(
                1, 2, 3, 4, 5, 6, Map.of("ideology", "真相值得冒险"));

        generator.generateBackground(sourceTemplate(), module(), card, rolls, List.of());

        Prompt prompt = capturedPrompt(chatModel);
        String userPrompt = prompt.getInstructions().getLast().getText();
        assertThat(userPrompt)
                .contains("埃莉诺·克劳福德", "冷静、谨慎、追求真相")
                .doesNotContain("星际骑士伊莎贝尔", "曾在银河帝国担任皇家骑士");
    }

    private DeepSeekCharacterCardGenerationModel generator(DeepSeekChatModel model) {
        return new DeepSeekCharacterCardGenerationModel(
                ChatClient.builder(model).build(),
                new CharacterCardGenerationResponseParser(JsonMapper.builder().build()));
    }

    private CharacterTemplate sourceTemplate() {
        return new CharacterTemplate()
                .setName("星际骑士伊莎贝尔")
                .setBackground("曾在银河帝国担任皇家骑士")
                .setPersonality("冷静、谨慎、追求真相")
                .setCocPlayStyle("偏好调查与交涉");
    }

    private CocModule module() {
        return new CocModule().setName("雾中来客").setEra("1920s")
                .setIntroduction("调查阿卡姆的连续失踪案")
                .setInvestigatorCreation("调查员需有接触档案的合理身份");
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

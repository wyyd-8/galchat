package com.me.galchat.service.impl.character;

import com.me.galchat.domain.dto.CharacterCardGenerationModels;
import com.me.galchat.exception.UserRequestException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CharacterCardGenerationResponseParserTest {

    private final CharacterCardGenerationResponseParser parser =
            new CharacterCardGenerationResponseParser(JsonMapper.builder().build());

    @Test
    void parsesJsonInsideMarkdownFence() {
        String response = """
                ```json
                {"name":"埃莉诺·克劳福德","age":30,"sex":"女","birthplace":"波士顿","residence":"阿卡姆",
                 "occupation":"记者","attributeOrder":["INT","EDU","POW","APP","DEX","CON","SIZ","STR"],
                 "occupationSkillOrder":["图书馆使用"],"interestSkillOrder":["潜行"],"explanations":[]}
                ```
                """;

        CharacterCardGenerationModels.BuildPlan result = parser.read(
                response, CharacterCardGenerationModels.BuildPlan.class);

        assertThat(result.occupation()).isEqualTo("记者");
        assertThat(result.name()).isEqualTo("埃莉诺·克劳福德");
        assertThat(result.attributeOrder()).hasSize(8);
    }

    @Test
    void rejectsMissingOrMalformedJson() {
        assertThatThrownBy(() -> parser.read(
                "我无法生成", CharacterCardGenerationModels.BuildPlan.class))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("AI生成人物卡失败");
    }

    @Test
    void flattensStructuredBackgroundEntriesReturnedByTheModel() {
        String response = """
                {
                  "appearance":"身材高大",
                  "ideology":"劳动者应该彼此扶持",
                  "significantPeople":{
                    "person":"莱维·科尔森",
                    "reason":"教会他在森林里辨认方向",
                    "isNPC":true
                  },
                  "meaningfulLocations":{
                    "location":"落日岩",
                    "why":"能俯瞰整片枫树林"
                  },
                  "treasuredPossessions":{
                    "item":"旧伐木楔",
                    "description":"导师留下的遗物"
                  },
                  "traits":"慷慨大方",
                  "keyConnectionCategory":"SIGNIFICANT_PEOPLE",
                  "keyConnectionText":"莱维·科尔森",
                  "weaponCode":"HAND_AXE",
                  "equipment":[]
                }
                """;

        CharacterCardGenerationModels.BackgroundPlan result = parser.read(
                response, CharacterCardGenerationModels.BackgroundPlan.class);

        assertThat(result.significantPeople())
                .isEqualTo("莱维·科尔森：教会他在森林里辨认方向");
        assertThat(result.meaningfulLocations())
                .isEqualTo("落日岩：能俯瞰整片枫树林");
        assertThat(result.treasuredPossessions())
                .isEqualTo("旧伐木楔：导师留下的遗物");
    }
}

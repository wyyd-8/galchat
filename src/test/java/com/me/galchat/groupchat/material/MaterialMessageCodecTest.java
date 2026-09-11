package com.me.galchat.groupchat.material;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialMessageCodecTest {

    @Test
    void agentSemanticTextKeepsMeaningAndDropsImageUrl() {
        MaterialMessageCodec codec = new MaterialMessageCodec(
                JsonMapper.builder().build());

        String result = codec.toAgentText("""
                {"schemaVersion":1,"materialId":31,
                 "title":"玛德琳的信","description":"信中提到酒店",
                 "imageUrl":"https://oss.example/image.jpg"}
                """);

        assertThat(result)
                .contains("<shown-material")
                .contains("玛德琳的信")
                .contains("信中提到酒店")
                .doesNotContain("materialId")
                .doesNotContain("https://oss.example/image.jpg");
    }
}

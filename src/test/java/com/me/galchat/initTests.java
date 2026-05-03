package com.me.galchat;

import com.me.galchat.controller.ChatController;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
public class initTests {
    @Autowired
    private EmbeddingModel embeddingModel;
    @Autowired
    private ChatController chatController;

    @Test
    public void testEmbedding() {
        EmbeddingResponse embeddingResponse = this.embeddingModel.embedForResponse(List.of("Tell me a joke"));
        System.out.println(embeddingResponse.getResult().getOutput().length);
    }

    @Test
    public void testSendMessage() {
        System.out.println(chatController.chat("你好", "1"));
    }
}

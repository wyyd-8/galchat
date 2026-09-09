package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.dto.TrpgEpilogueModels;
import com.me.galchat.domain.po.GroupConversation;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;

public interface TrpgEpilogueGenerator {

    TrpgEpilogueModels.Response generate(
            ChatClient chatClient,
            GroupConversation conversation,
            List<TrpgEpilogueModels.Subject> subjects,
            String publicHistory);
}

package com.me.galchat.controller;

import com.me.galchat.service.IUserChatHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SingleChatWithdrawalControllerTest {
    @Test
    void requiresAnExplicitTargetAndForwardsItToTheService() throws Exception {
        var histories = mock(IUserChatHistoryService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new UserChatHistoryController(histories)).build();

        mvc.perform(post("/history/withdraw").param("userworldid", "3").param("characterid", "7"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(histories);

        mvc.perform(post("/history/withdraw").param("userworldid", "3").param("characterid", "7")
                        .param("expectedMessageId", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
        verify(histories).withdrawLatestUserMessage(3L, 7L, 20L);
    }
}

package com.me.galchat.controller;

import com.me.galchat.service.impl.group.GroupChatWithdrawalService;
import com.me.galchat.service.impl.group.GroupGenerationStreamRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GroupChatWithdrawalControllerTest {
    @Test
    void requiresAnExplicitTargetAndForwardsItToTheService() throws Exception {
        var withdrawals = mock(GroupChatWithdrawalService.class);
        var generations = mock(GroupGenerationStreamRegistry.class);
        var controller = new GroupChatController(null, null, null, generations, withdrawals,
                null, null, null, null, null);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/group-chat/conversations/7/withdraw"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(withdrawals, generations);

        mvc.perform(post("/group-chat/conversations/7/withdraw").param("expectedTurnId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
        verify(withdrawals).withdrawLatestTurn(7L, 42L);
        verify(generations).evict(7L);
    }
}

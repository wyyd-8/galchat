package com.me.galchat.controller;

import com.me.galchat.domain.vo.SingleChatRuntimeVO;
import com.me.galchat.service.ICharacterTemplateService;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.impl.chat.SingleChatRuntimeService;
import com.me.galchat.utils.CurrentHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserCharacterControllerTest {

    @AfterEach
    void tearDown() {
        CurrentHolder.remove();
    }

    @Test
    void bindsTheSelectedSingleChatModelToTheCurrentUsersCharacter() throws Exception {
        SingleChatRuntimeService runtimeService = mock(SingleChatRuntimeService.class);
        when(runtimeService.saveModel(7L, 5L, 12L, 44L))
                .thenReturn(new SingleChatRuntimeVO(44L, "主模型", true));
        UserCharacterController controller = new UserCharacterController(
                mock(IUserCharacterInfoService.class),
                mock(ICharacterTemplateService.class),
                mock(IUserWorldPrefixService.class),
                runtimeService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        CurrentHolder.setCurrentId(7);

        mvc.perform(put("/character/5/12/model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelApiId\":44}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelApiId").value(44))
                .andExpect(jsonPath("$.data.modelApiName").value("主模型"));

        verify(runtimeService).saveModel(7L, 5L, 12L, 44L);
    }
}

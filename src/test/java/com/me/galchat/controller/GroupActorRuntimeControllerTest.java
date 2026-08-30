package com.me.galchat.controller;

import com.me.galchat.domain.dto.GroupActorRuntimeSaveDTO;
import com.me.galchat.domain.vo.GroupActorRuntimeVO;
import com.me.galchat.service.impl.GroupActorRuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GroupActorRuntimeControllerTest {

    @Test
    void exposesConversationScopedListAndSaveRoutes() throws Exception {
        GroupActorRuntimeService service = mock(GroupActorRuntimeService.class);
        GroupActorRuntimeVO value = new GroupActorRuntimeVO(
                "character", 12L, "MANUAL", 44L, "主模型", true);
        when(service.list(9L)).thenReturn(List.of(value));
        when(service.save(
                org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(value);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new GroupActorRuntimeController(service)).build();

        mvc.perform(get("/group-chat/conversations/9/actor-runtimes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].controlMode")
                        .value("MANUAL"));

        mvc.perform(put("/group-chat/conversations/9/actor-runtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorType":"character","actorId":12,
                                 "controlMode":"MODEL","modelApiId":44}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelApiId").value(44));

        verify(service).save(
                org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.argThat(
                        (GroupActorRuntimeSaveDTO dto) ->
                                "MODEL".equals(dto.getControlMode())
                                        && Long.valueOf(12L).equals(
                                        dto.getActorId())));
    }
}

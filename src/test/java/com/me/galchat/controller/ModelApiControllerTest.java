package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.ModelApiSaveDTO;
import com.me.galchat.domain.vo.ModelApiVO;
import com.me.galchat.mapper.UserModelApiMapper;
import com.me.galchat.modelapi.ModelApiProbeService;
import com.me.galchat.modelapi.ModelApiService;
import com.me.galchat.modelapi.PublicHttpsUrlValidator;
import com.me.galchat.utils.CurrentHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ModelApiControllerTest {

    @AfterEach
    void tearDown() {
        CurrentHolder.remove();
    }

    @Test
    void routesAllOperationsThroughCurrentUser() {
        StubService service = new StubService();
        ModelApiController controller = new ModelApiController(service);
        CurrentHolder.setCurrentId(7);
        ModelApiSaveDTO dto = new ModelApiSaveDTO().setName("主模型");

        Result listed = controller.list();
        Result created = controller.create(dto);
        Result updated = controller.update(41L, dto);
        Result tested = controller.test(41L);
        Result deleted = controller.delete(41L);

        assertThat(listed.getData()).isEqualTo(List.of(service.value));
        assertThat(created.getData()).isSameAs(service.value);
        assertThat(updated.getData()).isSameAs(service.value);
        assertThat(tested.getData()).isSameAs(service.value);
        assertThat(deleted.getCode()).isEqualTo(1);
        assertThat(service.calls).containsExactly(
                "list:7", "create:7", "update:7:41", "test:7:41", "delete:7:41");
    }

    @Test
    void bindsJsonBodyAndPathIdThroughRegisteredHttpRoutes() throws Exception {
        StubService service = new StubService();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new ModelApiController(service)).build();
        CurrentHolder.setCurrentId(7);

        mvc.perform(post("/model-apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"主模型","baseUrl":"https://models.example.com/v1",
                                "modelName":"model-a","apiKey":"sk-secret",
                                "requestOverrides":{"thinking":{"type":"enabled"},"reasoning_effort":"high"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
        assertThat(service.lastDto.getModelName()).isEqualTo("model-a");
        assertThat(service.lastDto.getRequestOverrides())
                .containsEntry("reasoning_effort", "high");

        mvc.perform(put("/model-apis/41")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"备用模型","baseUrl":"https://models.example.com/v1",
                                "modelName":"model-b"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
        assertThat(service.lastDto.getModelName()).isEqualTo("model-b");
        assertThat(service.calls).contains("update:7:41");
    }

    private static class StubService extends ModelApiService {
        private final ModelApiVO value = new ModelApiVO().setId(41L);
        private final java.util.ArrayList<String> calls = new java.util.ArrayList<>();
        private ModelApiSaveDTO lastDto;

        private StubService() {
            super((UserModelApiMapper) null, null,
                    (PublicHttpsUrlValidator) null,
                    null,
                    (ModelApiProbeService) null);
        }

        @Override
        public List<ModelApiVO> list(Long userId) {
            calls.add("list:" + userId);
            return List.of(value);
        }

        @Override
        public ModelApiVO create(Long userId, ModelApiSaveDTO dto) {
            calls.add("create:" + userId);
            lastDto = dto;
            return value;
        }

        @Override
        public ModelApiVO update(Long userId, Long id, ModelApiSaveDTO dto) {
            calls.add("update:" + userId + ":" + id);
            lastDto = dto;
            return value;
        }

        @Override
        public ModelApiVO test(Long userId, Long id) {
            calls.add("test:" + userId + ":" + id);
            return value;
        }

        @Override
        public void delete(Long userId, Long id) {
            calls.add("delete:" + userId + ":" + id);
        }
    }
}

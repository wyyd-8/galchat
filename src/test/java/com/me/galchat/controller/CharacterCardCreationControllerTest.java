package com.me.galchat.controller;

import com.me.galchat.domain.dto.CharacterCardGenerationModels;
import com.me.galchat.service.impl.CharacterCardCreationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CharacterCardCreationControllerTest {

    @Test
    void exposesAutoCreationAndBackgroundRewrite() {
        CharacterCardCreationService service = mock(CharacterCardCreationService.class);
        CharacterCardCreationController controller =
                new CharacterCardCreationController(service);
        var create = new CharacterCardGenerationModels.CreateRequest(1L, 2L, "c-1");
        var action = new CharacterCardGenerationModels.ActionRequest("r-1", 1);
        var view = new CharacterCardGenerationModels.DraftView(
                3L, "AUTO_QUICK_START", "PREVIEW_READY", "PREVIEW",
                "CONFIRM", 2, null);
        when(service.createAuto(create)).thenReturn(view);
        when(service.rewriteBackground(3L, action)).thenReturn(view);
        when(service.getActive(1L, 2L)).thenReturn(view);

        assertThat(controller.createAuto(create).getData()).isSameAs(view);
        assertThat(controller.rewriteBackground(3L, action).getData()).isSameAs(view);
        assertThat(controller.getActive(1L, 2L).getData()).isSameAs(view);
        verify(service).createAuto(create);
        verify(service).rewriteBackground(3L, action);
        verify(service).getActive(1L, 2L);
    }
}

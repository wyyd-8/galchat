package com.me.galchat.controller;

import com.me.galchat.domain.po.CocModule;
import com.me.galchat.service.impl.CocModuleService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CocModuleControllerTest {

    @Test
    void listReturnsVisibleModulesFromService() {
        CocModuleService service = mock(CocModuleService.class);
        CocModule module = new CocModule().setId(3L).setName("闹鬼");
        when(service.listVisible()).thenReturn(List.of(module));
        CocModuleController controller = new CocModuleController(service);

        var result = controller.list();

        assertThat(result.getData()).isEqualTo(List.of(module));
        verify(service).listVisible();
    }

    @Test
    void detailReturnsVisibleModuleFromService() {
        CocModuleService service = mock(CocModuleService.class);
        CocModule module = new CocModule().setId(3L).setName("闹鬼");
        when(service.getVisible(3L)).thenReturn(module);
        CocModuleController controller = new CocModuleController(service);

        var result = controller.detail(3L);

        assertThat(result.getData()).isSameAs(module);
        verify(service).getVisible(3L);
    }
}

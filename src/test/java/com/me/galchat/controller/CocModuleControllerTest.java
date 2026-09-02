package com.me.galchat.controller;

import com.me.galchat.domain.dto.CocModuleArchiveDTO;
import com.me.galchat.domain.dto.CocModuleContentUpdateDTO;
import com.me.galchat.domain.dto.CocModuleCreateDTO;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.vo.CocModuleDetailVO;
import com.me.galchat.service.impl.CocModuleRuntimeService;
import com.me.galchat.service.impl.CocModuleService;
import com.me.galchat.utils.CurrentHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CocModuleControllerTest {

    @BeforeEach
    void setCurrentUser() {
        CurrentHolder.setCurrentId(7);
    }

    @AfterEach
    void clearCurrentUser() {
        CurrentHolder.remove();
    }

    @Test
    void listReturnsVisibleModulesFromService() {
        CocModuleService service = mock(CocModuleService.class);
        CocModule module = new CocModule().setId(3L).setName("闹鬼");
        when(service.listVisible(7L)).thenReturn(List.of(module));
        CocModuleController controller = new CocModuleController(
                service, mock(CocModuleRuntimeService.class));

        var result = controller.list();

        assertThat(result.getData()).isEqualTo(List.of(module));
        verify(service).listVisible(7L);
    }

    @Test
    void detailReturnsVisibleModuleFromService() {
        CocModuleService service = mock(CocModuleService.class);
        CocModule module = new CocModule().setId(3L).setName("闹鬼");
        when(service.getVisible(7L, 3L)).thenReturn(module);
        CocModuleController controller = new CocModuleController(
                service, mock(CocModuleRuntimeService.class));

        var result = controller.detail(3L);

        assertThat(result.getData()).isSameAs(module);
        verify(service).getVisible(7L, 3L);
    }

    @Test
    void createAssignsTheAuthenticatedUserAsOwner() {
        CocModuleService service = mock(CocModuleService.class);
        CocModuleCreateDTO request = new CocModuleCreateDTO();
        CocModule created = new CocModule().setId(3L).setOwnerUserId(7L);
        when(service.createOwned(7L, request)).thenReturn(created);
        CocModuleController controller = new CocModuleController(
                service, mock(CocModuleRuntimeService.class));

        var result = controller.create(request);

        assertThat(result.getData()).isSameAs(created);
        verify(service).createOwned(7L, request);
    }

    @Test
    void exportReturnsJsonDownloadResponse() {
        CocModuleService service = mock(CocModuleService.class);
        CocModuleArchiveDTO archive = new CocModuleArchiveDTO()
                .setFormatVersion(1);
        when(service.exportReadable(7L, 3L)).thenReturn(archive);
        CocModuleController controller = new CocModuleController(
                service, mock(CocModuleRuntimeService.class));

        var response = controller.exportModule(3L);

        assertThat(response.getBody()).isSameAs(archive);
        assertThat(response.getHeaders().getContentDisposition()
                .getFilename()).isEqualTo("galchat-coc-module-3.json");
        verify(service).exportReadable(7L, 3L);
    }

    @Test
    void manageReturnsReadableDetailForDefaultOrOwnedModule() {
        CocModuleService service = mock(CocModuleService.class);
        CocModuleDetailVO detail = new CocModuleDetailVO();
        when(service.getReadableDetail(7L, 3L)).thenReturn(detail);
        CocModuleController controller = new CocModuleController(
                service, mock(CocModuleRuntimeService.class));

        var result = controller.manage(3L);

        assertThat(result.getData()).isSameAs(detail);
        verify(service).getReadableDetail(7L, 3L);
    }

    @Test
    void contentOnlyEndpointPassesOnlyTheNewContent() {
        CocModuleService service = mock(CocModuleService.class);
        CocModuleController controller = new CocModuleController(
                service, mock(CocModuleRuntimeService.class));
        CocModuleContentUpdateDTO request =
                new CocModuleContentUpdateDTO().setContent("新正文");

        controller.updateLocationContent(3L, 11L, request);

        verify(service).updateLocationContent(7L, 3L, 11L, "新正文");
    }

    @Test
    void unlockDelegatesToRuntimeResetWorkflow() {
        CocModuleRuntimeService runtimeService =
                mock(CocModuleRuntimeService.class);
        CocModuleController controller = new CocModuleController(
                mock(CocModuleService.class), runtimeService);

        controller.unlock(3L);

        verify(runtimeService).unlock(7L, 3L);
    }
}

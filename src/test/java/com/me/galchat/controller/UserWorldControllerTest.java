package com.me.galchat.controller;

import com.me.galchat.domain.dto.WorldArchiveDTO;
import com.me.galchat.domain.dto.WorldArchiveReplaceResultDTO;
import com.me.galchat.domain.dto.WorldTemplateUsageDTO;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.IWorldArchiveService;
import com.me.galchat.service.IWorldDetailService;
import com.me.galchat.service.IWorldTemplateService;
import com.me.galchat.utils.CurrentHolder;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserWorldControllerTest {

    @Test
    void worldListRejectsAnotherUserBeforeReadingTheirData() {
        IUserWorldPrefixService worlds = mock(IUserWorldPrefixService.class);
        UserWorldController controller = worldListController(worlds);
        CurrentHolder.setCurrentId(7);

        assertThatThrownBy(() -> controller.listUserWorldBaseInfo(8L))
                .isInstanceOf(UserAuthException.class);
        verifyNoInteractions(worlds);
    }

    @Test
    void worldListRequiresLogin() {
        IUserWorldPrefixService worlds = mock(IUserWorldPrefixService.class);
        UserWorldController controller = worldListController(worlds);

        assertThatThrownBy(() -> controller.listUserWorldBaseInfo(7L))
                .isInstanceOf(UserRequestException.class);
        verifyNoInteractions(worlds);
    }

    @Test
    void worldListReturnsTheCurrentUsersWorlds() {
        IUserWorldPrefixService worlds = mock(IUserWorldPrefixService.class);
        var expected = java.util.List.of(new UserWorldPrefix().setId(12L).setUserId(7L));
        when(worlds.listBaseInfoByUserId(7L)).thenReturn(expected);
        CurrentHolder.setCurrentId(7);

        assertThat(worldListController(worlds).listUserWorldBaseInfo(7L).getData())
                .isEqualTo(expected);
    }

    private UserWorldController worldListController(IUserWorldPrefixService worlds) {
        return new UserWorldController(worlds, mock(IWorldTemplateService.class),
                mock(IWorldDetailService.class), mock(IWorldArchiveService.class), mock(ObjectMapper.class));
    }

    @AfterEach
    void clearCurrentUser() {
        CurrentHolder.remove();
    }

    @Test
    void usageUsesTemplateIdAndCurrentAuthor() {
        IWorldArchiveService archiveService = mock(IWorldArchiveService.class);
        UserWorldController controller = controller(archiveService);
        WorldTemplateUsageDTO usage = new WorldTemplateUsageDTO()
                .setAssociatedWorldCount(0)
                .setDeletable(true);
        when(archiveService.getWorldTemplateUsage(7L, 20L)).thenReturn(usage);
        CurrentHolder.setCurrentId(7);

        var result = controller.getWorldTemplateUsage(20L);

        assertThat(result.getData()).isSameAs(usage);
        verify(archiveService).getWorldTemplateUsage(7L, 20L);
    }

    @Test
    void replacementPassesExplicitLowMatchConfirmation() {
        IWorldArchiveService archiveService = mock(IWorldArchiveService.class);
        UserWorldController controller = controller(archiveService);
        WorldArchiveDTO archive = new WorldArchiveDTO().setFormatVersion(1);
        WorldArchiveReplaceResultDTO replacement = new WorldArchiveReplaceResultDTO().setReplaced(true);
        when(archiveService.replaceWorldTemplate(7L, 20L, archive, true)).thenReturn(replacement);
        CurrentHolder.setCurrentId(7);

        var result = controller.replaceWorldTemplate(20L, true, archive);

        assertThat(result.getData()).isSameAs(replacement);
        verify(archiveService).replaceWorldTemplate(7L, 20L, archive, true);
    }

    @Test
    void deletionUsesTemplateIdAndCurrentAuthor() {
        IWorldArchiveService archiveService = mock(IWorldArchiveService.class);
        UserWorldController controller = controller(archiveService);
        CurrentHolder.setCurrentId(7);

        controller.deleteWorldTemplate(20L);

        verify(archiveService).deleteWorldTemplate(7L, 20L);
    }

    private UserWorldController controller(IWorldArchiveService archiveService) {
        return new UserWorldController(
                mock(IUserWorldPrefixService.class),
                mock(IWorldTemplateService.class),
                mock(IWorldDetailService.class),
                archiveService,
                mock(ObjectMapper.class));
    }
}

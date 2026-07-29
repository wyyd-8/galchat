package com.me.galchat.service.impl;

import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.service.IWorldTemplateService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class CharacterTemplateServiceImplTest {

    @Test
    void createPreservesCocPlayStyle() {
        IWorldTemplateService worldTemplateService =
                mock(IWorldTemplateService.class);
        CharacterTemplateServiceImpl service = spy(
                new CharacterTemplateServiceImpl(
                        worldTemplateService));
        when(worldTemplateService.getById(20L)).thenReturn(
                new WorldTemplate().setId(20L).setAuthorId(1L));
        AtomicReference<CharacterTemplate> saved =
                new AtomicReference<>();
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return true;
        }).when(service).save(any(CharacterTemplate.class));

        service.createCharacterTemplate(
                1L, 20L,
                new CharacterTemplate()
                        .setName("角色")
                        .setCocPlayStyle("偏好稳健调查"));

        assertThat(saved.get().getCocPlayStyle())
                .isEqualTo("偏好稳健调查");
    }

    @Test
    void updatePreservesCocPlayStyle() {
        IWorldTemplateService worldTemplateService =
                mock(IWorldTemplateService.class);
        CharacterTemplateServiceImpl service = spy(
                new CharacterTemplateServiceImpl(
                        worldTemplateService));
        when(worldTemplateService.getById(20L)).thenReturn(
                new WorldTemplate().setId(20L).setAuthorId(1L));
        doReturn(new CharacterTemplate().setId(9L))
                .when(service)
                .getCharacterTemplateByWorldId(20L, 9L);
        AtomicReference<CharacterTemplate> updated =
                new AtomicReference<>();
        doAnswer(invocation -> {
            updated.set(invocation.getArgument(0));
            return true;
        }).when(service).updateById(any(CharacterTemplate.class));

        service.updateCharacterTemplate(
                1L, 20L, 9L,
                new CharacterTemplate()
                        .setName("角色")
                        .setCocPlayStyle("偏好主动承担风险"));

        assertThat(updated.get().getCocPlayStyle())
                .isEqualTo("偏好主动承担风险");
    }
}

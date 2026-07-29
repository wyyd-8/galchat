package com.me.galchat.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.me.galchat.domain.dto.WorldArchiveDTO;
import com.me.galchat.domain.dto.WorldArchiveImportResultDTO;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.po.WorldDetail;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.service.impl.WorldArchiveServiceImpl;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorldArchiveServiceImplTest {

    @Test
    void exportMyWorldBuildsPortableArchiveWithoutDatabaseIds() {
        IUserWorldPrefixService userWorldPrefixService = mock(IUserWorldPrefixService.class);
        IWorldTemplateService worldTemplateService = mock(IWorldTemplateService.class);
        IWorldDetailService worldDetailService = mock(IWorldDetailService.class);
        ICharacterTemplateService characterTemplateService = mock(ICharacterTemplateService.class);
        WorldArchiveServiceImpl archiveService = new WorldArchiveServiceImpl(userWorldPrefixService,
                worldTemplateService, worldDetailService, characterTemplateService);

        UserWorldPrefix userWorld = new UserWorldPrefix()
                .setId(10L)
                .setWorldId(20L)
                .setName("我的世界")
                .setAcitvePushStatus(true)
                .setDailyCompanionMode(false)
                .setFavorSystemStatus("NORMAL")
                .setEotDetectionStatus(false)
                .setThinkStatus(true)
                .setAddSpecialPrompt(false)
                .setMyWorld(true);
        WorldTemplate worldTemplate = new WorldTemplate()
                .setId(20L)
                .setName("世界")
                .setImage("")
                .setAuthor("作者")
                .setAuthorId(1L)
                .setBackground("背景")
                .setVisible(false);
        WorldDetail detail = new WorldDetail()
                .setId(30L)
                .setWorldId(20L)
                .setAbout("地点")
                .setDetails("详情");
        CharacterTemplate character = new CharacterTemplate()
                .setId(40L)
                .setWorldId(20L)
                .setName("角色")
                .setImage("")
                .setBackground("经历")
                .setPersonality("性格")
                .setCocPlayStyle("谨慎调查并优先保护同伴")
                .setFavorability(Map.of("友好", "10"))
                .setInitFavor(0);

        when(userWorldPrefixService.checkUserWorldAuth(1L, 10L, true)).thenReturn(userWorld);
        when(worldTemplateService.getOwnWorldTemplate(1L, 20L)).thenReturn(worldTemplate);
        when(worldDetailService.listWorldDetails(1L, 20L)).thenReturn(List.of(detail));
        when(characterTemplateService.list(any(Wrapper.class))).thenReturn(List.of(character));

        WorldArchiveDTO archive = archiveService.exportMyWorld(1L, 10L);

        assertThat(archive.getFormatVersion()).isEqualTo(1);
        assertThat(archive.getWorld().getName()).isEqualTo("世界");
        assertThat(archive.getWorld().getVisible()).isFalse();
        assertThat(archive.getDetails()).singleElement()
                .satisfies(item -> assertThat(item.getDetails()).isEqualTo("详情"));
        assertThat(archive.getCharacters()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getName()).isEqualTo("角色");
                    assertThat(item.getCocPlayStyle())
                            .isEqualTo("谨慎调查并优先保护同伴");
                    assertThat(item.getFavorability()).containsEntry("友好", "10");
                });
    }

    @Test
    void importWorldCreatesTemplateOnlyAndRestoresDetailsAndCharacters() {
        IUserWorldPrefixService userWorldPrefixService = mock(IUserWorldPrefixService.class);
        IWorldTemplateService worldTemplateService = mock(IWorldTemplateService.class);
        IWorldDetailService worldDetailService = mock(IWorldDetailService.class);
        ICharacterTemplateService characterTemplateService = mock(ICharacterTemplateService.class);
        WorldArchiveServiceImpl archiveService = new WorldArchiveServiceImpl(userWorldPrefixService,
                worldTemplateService, worldDetailService, characterTemplateService);

        when(worldTemplateService.save(any(WorldTemplate.class))).thenAnswer(invocation -> {
            WorldTemplate worldTemplate = invocation.getArgument(0);
            worldTemplate.setId(200L);
            return true;
        });

        WorldArchiveDTO archive = new WorldArchiveDTO()
                .setFormatVersion(1)
                .setWorld(new WorldArchiveDTO.WorldArchive()
                        .setName(" 世界 ")
                        .setImage("")
                        .setAuthor("作者")
                        .setBackground("背景")
                        .setVisible(false))
                .setDetails(List.of(new WorldArchiveDTO.WorldDetailArchive()
                        .setAbout("地点")
                        .setDetails("详情")))
                .setCharacters(List.of(new WorldArchiveDTO.CharacterArchive()
                        .setName(" 角色 ")
                        .setImage("")
                        .setBackground("经历")
                        .setPersonality("性格")
                        .setCocPlayStyle("偏好通过交涉获取线索")
                        .setFavorability(Map.of("友好", "10"))
                        .setInitFavor(5)));

        WorldArchiveImportResultDTO result = archiveService.importWorld(1L, archive);

        ArgumentCaptor<WorldTemplate> worldCaptor = ArgumentCaptor.forClass(WorldTemplate.class);
        ArgumentCaptor<CharacterTemplate> characterCaptor = ArgumentCaptor.forClass(CharacterTemplate.class);

        verify(worldTemplateService).save(worldCaptor.capture());
        verify(userWorldPrefixService, never()).save(any(UserWorldPrefix.class));
        verify(worldDetailService).createWorldDetail(eq(1L), eq(200L), any(WorldDetail.class));
        verify(characterTemplateService).createCharacterTemplate(eq(1L), eq(200L), characterCaptor.capture());

        assertThat(worldCaptor.getValue().getName()).isEqualTo("世界");
        assertThat(worldCaptor.getValue().getAuthorId()).isEqualTo(1L);
        assertThat(worldCaptor.getValue().getVisible()).isFalse();
        assertThat(characterCaptor.getValue().getName()).isEqualTo("角色");
        assertThat(characterCaptor.getValue().getCocPlayStyle())
                .isEqualTo("偏好通过交涉获取线索");
        assertThat(result.getWorldId()).isEqualTo(200L);
        assertThat(result.getName()).isEqualTo("世界");
        assertThat(result.getDetailCount()).isEqualTo(1);
        assertThat(result.getCharacterCount()).isEqualTo(1);
    }
}

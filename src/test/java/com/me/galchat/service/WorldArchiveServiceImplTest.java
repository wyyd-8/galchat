package com.me.galchat.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.me.galchat.domain.dto.WorldArchiveDTO;
import com.me.galchat.domain.dto.WorldArchiveImportResultDTO;
import com.me.galchat.domain.dto.WorldArchiveReplaceResultDTO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.VectorStoreCleanupMapper;
import com.me.galchat.mapper.WorldTemplateMapper;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.po.WorldDetail;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.service.impl.world.WorldArchiveServiceImpl;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cache.CacheManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorldArchiveServiceImplTest {

    @Test
    void exportMyWorldBuildsPortableArchiveWithoutDatabaseIds() {
        IUserWorldPrefixService userWorldPrefixService = mock(IUserWorldPrefixService.class);
        IWorldTemplateService worldTemplateService = mock(IWorldTemplateService.class);
        IWorldDetailService worldDetailService = mock(IWorldDetailService.class);
        ICharacterTemplateService characterTemplateService = mock(ICharacterTemplateService.class);
        WorldArchiveServiceImpl archiveService = service(userWorldPrefixService, worldTemplateService,
                worldDetailService, characterTemplateService);

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
        WorldArchiveServiceImpl archiveService = service(userWorldPrefixService, worldTemplateService,
                worldDetailService, characterTemplateService);

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

    @Test
    void deleteTemplateRejectsAnyAssociatedUserWorldBeforeRemovingChildren() {
        IUserWorldPrefixService userWorldPrefixService = mock(IUserWorldPrefixService.class);
        IWorldTemplateService worldTemplateService = mock(IWorldTemplateService.class);
        IWorldDetailService worldDetailService = mock(IWorldDetailService.class);
        ICharacterTemplateService characterTemplateService = mock(ICharacterTemplateService.class);
        WorldTemplateMapper worldTemplateMapper = mock(WorldTemplateMapper.class);
        VectorStoreCleanupMapper vectorStoreCleanupMapper = mock(VectorStoreCleanupMapper.class);
        WorldArchiveServiceImpl archiveService = service(userWorldPrefixService, worldTemplateService,
                worldDetailService, characterTemplateService, worldTemplateMapper, vectorStoreCleanupMapper);

        when(worldTemplateService.getOwnWorldTemplate(1L, 20L)).thenReturn(
                new WorldTemplate().setId(20L).setAuthorId(1L));
        when(userWorldPrefixService.count(any(Wrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> archiveService.deleteWorldTemplate(1L, 20L))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("当前模板仍有关联世界，不能删除");

        verify(vectorStoreCleanupMapper, never()).deleteWorldDetailsByWorldId(20L);
        verify(worldDetailService, never()).remove(any(Wrapper.class));
        verify(characterTemplateService, never()).remove(any(Wrapper.class));
        verify(worldTemplateMapper, never()).deleteOwnedTemplateIfUnused(20L, 1L);
    }

    @Test
    void deleteTemplateRemovesVectorDetailsCharactersAndTemplate() {
        IUserWorldPrefixService userWorldPrefixService = mock(IUserWorldPrefixService.class);
        IWorldTemplateService worldTemplateService = mock(IWorldTemplateService.class);
        IWorldDetailService worldDetailService = mock(IWorldDetailService.class);
        ICharacterTemplateService characterTemplateService = mock(ICharacterTemplateService.class);
        WorldTemplateMapper worldTemplateMapper = mock(WorldTemplateMapper.class);
        VectorStoreCleanupMapper vectorStoreCleanupMapper = mock(VectorStoreCleanupMapper.class);
        WorldArchiveServiceImpl archiveService = service(userWorldPrefixService, worldTemplateService,
                worldDetailService, characterTemplateService, worldTemplateMapper, vectorStoreCleanupMapper);

        when(worldTemplateService.getOwnWorldTemplate(1L, 20L)).thenReturn(
                new WorldTemplate().setId(20L).setAuthorId(1L));
        when(userWorldPrefixService.count(any(Wrapper.class))).thenReturn(0L);
        when(characterTemplateService.list(any(Wrapper.class))).thenReturn(List.of(
                new CharacterTemplate().setId(31L).setWorldId(20L).setName("甲"),
                new CharacterTemplate().setId(32L).setWorldId(20L).setName("乙")));
        when(worldTemplateMapper.deleteOwnedTemplateIfUnused(20L, 1L)).thenReturn(1);

        archiveService.deleteWorldTemplate(1L, 20L);

        verify(vectorStoreCleanupMapper).deleteWorldDetailsByWorldId(20L);
        verify(worldDetailService).remove(any(Wrapper.class));
        verify(characterTemplateService).remove(any(Wrapper.class));
        verify(worldTemplateMapper).deleteOwnedTemplateIfUnused(20L, 1L);
    }

    @Test
    void replaceTemplateBelowHalfReturnsConfirmationWithoutWriting() {
        IUserWorldPrefixService userWorldPrefixService = mock(IUserWorldPrefixService.class);
        IWorldTemplateService worldTemplateService = mock(IWorldTemplateService.class);
        IWorldDetailService worldDetailService = mock(IWorldDetailService.class);
        ICharacterTemplateService characterTemplateService = mock(ICharacterTemplateService.class);
        VectorStoreCleanupMapper vectorStoreCleanupMapper = mock(VectorStoreCleanupMapper.class);
        WorldArchiveServiceImpl archiveService = service(userWorldPrefixService, worldTemplateService,
                worldDetailService, characterTemplateService, mock(WorldTemplateMapper.class), vectorStoreCleanupMapper);

        when(worldTemplateService.getOwnWorldTemplate(1L, 20L)).thenReturn(
                new WorldTemplate().setId(20L).setName("旧世界").setAuthorId(1L));
        when(characterTemplateService.list(any(Wrapper.class))).thenReturn(List.of(
                character(31L, "Alice"), character(32L, "Bob"), character(33L, "Carol")));

        WorldArchiveReplaceResultDTO result = archiveService.replaceWorldTemplate(
                1L, 20L, archive("新世界", List.of(archiveCharacter(" alice "), archiveCharacter("Dana"))), false);

        assertThat(result.isConfirmationRequired()).isTrue();
        assertThat(result.isReplaced()).isFalse();
        assertThat(result.getMatchRate()).isCloseTo(1.0 / 3.0, within(0.0001));
        assertThat(result.getMatchedCharacterNames()).containsExactly("Alice");
        assertThat(result.getAddedCharacterNames()).containsExactly("Dana");
        assertThat(result.getUnchangedCharacterNames()).containsExactly("Bob", "Carol");
        verify(worldTemplateService, never()).updateWorldTemplate(any(), any(), any());
        verify(vectorStoreCleanupMapper, never()).deleteWorldDetailsByWorldId(any());
        verify(worldDetailService, never()).remove(any(Wrapper.class));
        verify(characterTemplateService, never()).updateCharacterTemplate(any(), any(), any(), any());
        verify(characterTemplateService, never()).createCharacterTemplate(any(), any(), any());
    }

    @Test
    void confirmedReplacementPreservesMatchedIdsAddsExtrasAndKeepsMissingCharacters() {
        IUserWorldPrefixService userWorldPrefixService = mock(IUserWorldPrefixService.class);
        IWorldTemplateService worldTemplateService = mock(IWorldTemplateService.class);
        IWorldDetailService worldDetailService = mock(IWorldDetailService.class);
        ICharacterTemplateService characterTemplateService = mock(ICharacterTemplateService.class);
        VectorStoreCleanupMapper vectorStoreCleanupMapper = mock(VectorStoreCleanupMapper.class);
        WorldArchiveServiceImpl archiveService = service(userWorldPrefixService, worldTemplateService,
                worldDetailService, characterTemplateService, mock(WorldTemplateMapper.class), vectorStoreCleanupMapper);

        when(worldTemplateService.getOwnWorldTemplate(1L, 20L)).thenReturn(
                new WorldTemplate().setId(20L).setName("旧世界").setAuthorId(1L));
        when(characterTemplateService.list(any(Wrapper.class))).thenReturn(List.of(
                character(31L, "Alice"), character(32L, "Bob"), character(33L, "Carol")));
        WorldArchiveDTO archive = archive("新世界", List.of(
                archiveCharacter(" alice ").setPersonality("new personality"), archiveCharacter("Dana")));
        archive.setDetails(List.of(new WorldArchiveDTO.WorldDetailArchive()
                .setAbout("新地点").setDetails("新详情")));

        WorldArchiveReplaceResultDTO result = archiveService.replaceWorldTemplate(1L, 20L, archive, true);

        assertThat(result.isConfirmationRequired()).isFalse();
        assertThat(result.isReplaced()).isTrue();
        ArgumentCaptor<WorldTemplate> world = ArgumentCaptor.forClass(WorldTemplate.class);
        verify(worldTemplateService).updateWorldTemplate(eq(1L), eq(20L), world.capture());
        assertThat(world.getValue().getVisible()).isNull();
        verify(vectorStoreCleanupMapper).deleteWorldDetailsByWorldId(20L);
        verify(worldDetailService).remove(any(Wrapper.class));
        verify(worldDetailService).createWorldDetail(eq(1L), eq(20L), any(WorldDetail.class));
        ArgumentCaptor<CharacterTemplate> matched = ArgumentCaptor.forClass(CharacterTemplate.class);
        verify(characterTemplateService).updateCharacterTemplate(eq(1L), eq(20L), eq(31L), matched.capture());
        assertThat(matched.getValue().getName()).isEqualTo("alice");
        assertThat(matched.getValue().getPersonality()).isEqualTo("new personality");
        ArgumentCaptor<CharacterTemplate> added = ArgumentCaptor.forClass(CharacterTemplate.class);
        verify(characterTemplateService).createCharacterTemplate(eq(1L), eq(20L), added.capture());
        assertThat(added.getValue().getName()).isEqualTo("Dana");
        verify(characterTemplateService, times(1)).updateCharacterTemplate(any(), any(), any(), any());
    }

    @Test
    void replaceTemplateAtExactlyHalfDoesNotRequireConfirmation() {
        IWorldTemplateService worldTemplateService = mock(IWorldTemplateService.class);
        ICharacterTemplateService characterTemplateService = mock(ICharacterTemplateService.class);
        WorldArchiveServiceImpl archiveService = service(mock(IUserWorldPrefixService.class), worldTemplateService,
                mock(IWorldDetailService.class), characterTemplateService);

        when(worldTemplateService.getOwnWorldTemplate(1L, 20L)).thenReturn(
                new WorldTemplate().setId(20L).setName("旧世界").setAuthorId(1L));
        when(characterTemplateService.list(any(Wrapper.class))).thenReturn(List.of(
                character(31L, "Alice"), character(32L, "Bob")));

        WorldArchiveReplaceResultDTO result = archiveService.replaceWorldTemplate(
                1L, 20L, archive("新世界", List.of(archiveCharacter("Alice"))), false);

        assertThat(result.isReplaced()).isTrue();
        assertThat(result.isConfirmationRequired()).isFalse();
        assertThat(result.getMatchRate()).isEqualTo(0.5);
    }

    private static WorldArchiveServiceImpl service(IUserWorldPrefixService userWorldPrefixService,
                                                   IWorldTemplateService worldTemplateService,
                                                   IWorldDetailService worldDetailService,
                                                   ICharacterTemplateService characterTemplateService) {
        return service(userWorldPrefixService, worldTemplateService, worldDetailService, characterTemplateService,
                mock(WorldTemplateMapper.class), mock(VectorStoreCleanupMapper.class));
    }

    private static WorldArchiveServiceImpl service(IUserWorldPrefixService userWorldPrefixService,
                                                   IWorldTemplateService worldTemplateService,
                                                   IWorldDetailService worldDetailService,
                                                   ICharacterTemplateService characterTemplateService,
                                                   WorldTemplateMapper worldTemplateMapper,
                                                   VectorStoreCleanupMapper vectorStoreCleanupMapper) {
        return new WorldArchiveServiceImpl(userWorldPrefixService, worldTemplateService, worldDetailService,
                characterTemplateService, worldTemplateMapper, vectorStoreCleanupMapper, mock(CacheManager.class));
    }

    private static CharacterTemplate character(Long id, String name) {
        return new CharacterTemplate().setId(id).setWorldId(20L).setName(name);
    }

    private static WorldArchiveDTO.CharacterArchive archiveCharacter(String name) {
        return new WorldArchiveDTO.CharacterArchive().setName(name);
    }

    private static WorldArchiveDTO archive(String name, List<WorldArchiveDTO.CharacterArchive> characters) {
        return new WorldArchiveDTO()
                .setFormatVersion(1)
                .setWorld(new WorldArchiveDTO.WorldArchive().setName(name).setBackground("新背景"))
                .setCharacters(characters);
    }
}

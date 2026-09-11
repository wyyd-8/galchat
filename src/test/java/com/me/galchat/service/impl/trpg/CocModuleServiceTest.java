package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.dto.CocModuleCreateDTO;
import com.me.galchat.domain.dto.CocModuleArchiveDTO;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.CocModuleCharacter;
import com.me.galchat.domain.po.CocModuleClue;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.vo.CharacterCardVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocModuleClueMapper;
import com.me.galchat.mapper.CocModuleContextMapper;
import com.me.galchat.mapper.CocModuleLocationMapper;
import com.me.galchat.mapper.CocModuleMapper;
import com.me.galchat.mapper.CocModuleCharacterMapper;
import com.me.galchat.mapper.CocModuleMaterialMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import org.redisson.api.RLock;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CocModuleServiceTest {

    @Test
    void listVisibleReturnsOnlySelectableModules() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleService service = serviceWith(moduleMapper);
        CocModule visible = new CocModule()
                .setId(3L)
                .setName("闹鬼")
                .setVisible(true);
        CocModule hidden = new CocModule()
                .setId(2L)
                .setName("未公开模组")
                .setVisible(false);
        when(moduleMapper.selectList(any())).thenReturn(
                java.util.List.of(visible, hidden));

        assertThat(service.listVisible()).containsExactly(visible);
    }

    @Test
    void getVisibleRejectsHiddenModule() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleService service = serviceWith(moduleMapper);
        when(moduleMapper.selectById(3L)).thenReturn(
                new CocModule().setId(3L).setVisible(false));

        assertThatThrownBy(() -> service.getVisible(3L))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("模组不存在或不可选");
    }

    @Test
    void listVisibleDoesNotExposeAnotherUsersPrivateModule() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleService service = serviceWith(moduleMapper);
        CocModule publicModule = new CocModule()
                .setId(1L).setVisible(true);
        CocModule ownModule = new CocModule()
                .setId(2L).setVisible(true).setOwnerUserId(7L);
        CocModule anotherUsersModule = new CocModule()
                .setId(3L).setVisible(true).setOwnerUserId(8L);
        when(moduleMapper.selectList(any())).thenReturn(java.util.List.of(
                publicModule, ownModule, anotherUsersModule));

        assertThat(service.listVisible(7L))
                .containsExactly(publicModule, ownModule);
    }

    @Test
    void createOwnedSetsOwnerAndStartsUnlocked() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleService service = serviceWith(moduleMapper);
        CocModuleCreateDTO request = new CocModuleCreateDTO();
        request.setName("自建模组");
        request.setIntroduction("调查简介");

        CocModule result = service.createOwned(7L, request);

        assertThat(result.getOwnerUserId()).isEqualTo(7L);
        assertThat(result.getEditLocked()).isFalse();
        verify(moduleMapper).insert(result);
    }

    @Test
    void updateOwnedRejectsFullReplacementWhileLocked() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleService service = serviceWith(moduleMapper);
        when(moduleMapper.selectById(3L)).thenReturn(new CocModule()
                .setId(3L).setOwnerUserId(7L).setEditLocked(true));
        CocModuleCreateDTO request = new CocModuleCreateDTO();
        request.setName("修改后的名称");
        request.setIntroduction("修改后的简介");

        assertThatThrownBy(() -> service.updateOwned(7L, 3L, request))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("受限编辑");

        verify(moduleMapper, never()).updateById(any(CocModule.class));
    }

    @Test
    void updateOwnedRejectsWhileAnActionTurnIsLockingTheModule() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleLockService lockService = mock(CocModuleLockService.class);
        CocModuleService service = new CocModuleService(
                moduleMapper,
                mock(CocModuleContextMapper.class),
                mock(CocModuleLocationMapper.class),
                mock(CocModuleClueMapper.class),
                mock(CocModuleMaterialMapper.class),
                mock(GroupConversationMapper.class), lockService,
                mock(CocModuleCharacterMapper.class));
        when(moduleMapper.selectById(3L)).thenReturn(new CocModule()
                .setId(3L).setOwnerUserId(7L).setEditLocked(false));
        when(lockService.tryWriteLock(3L)).thenReturn(null);
        CocModuleCreateDTO request = new CocModuleCreateDTO();
        request.setName("修改后的名称");
        request.setIntroduction("修改后的简介");

        assertThatThrownBy(() -> service.updateOwned(7L, 3L, request))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("正在变更");

        verify(moduleMapper, never()).update(any());
    }

    @Test
    void lockedModuleStillAllowsLocationContentUpdate() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        CocModuleService service = new CocModuleService(
                moduleMapper,
                mock(CocModuleContextMapper.class),
                locationMapper,
                mock(CocModuleClueMapper.class),
                mock(CocModuleMaterialMapper.class),
                mock(GroupConversationMapper.class),
                mock(CocModuleLockService.class),
                mock(CocModuleCharacterMapper.class));
        when(moduleMapper.selectById(3L)).thenReturn(new CocModule()
                .setId(3L).setOwnerUserId(7L).setEditLocked(true));
        when(locationMapper.selectById(11L)).thenReturn(
                new CocModuleLocation().setId(11L).setModuleId(3L)
                        .setName("老宅").setSummary("调查现场")
                        .setContent("旧正文"));

        CocModuleLocation updated = service.updateLocationContent(
                7L, 3L, 11L, "  新正文  ");

        assertThat(updated.getContent()).isEqualTo("新正文");
        verify(locationMapper).updateById(updated);
        verify(moduleMapper).updateById(org.mockito.ArgumentMatchers
                .<CocModule>argThat(
                module -> module.getId().equals(3L)
                        && module.getUpdatedAt() != null));
    }

    @Test
    void lockedModuleStillAllowsAddingAClue() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleClueMapper clueMapper = mock(CocModuleClueMapper.class);
        CocModuleService service = new CocModuleService(
                moduleMapper,
                mock(CocModuleContextMapper.class),
                mock(CocModuleLocationMapper.class),
                clueMapper,
                mock(CocModuleMaterialMapper.class),
                mock(GroupConversationMapper.class),
                mock(CocModuleLockService.class),
                mock(CocModuleCharacterMapper.class));
        when(moduleMapper.selectById(3L)).thenReturn(new CocModule()
                .setId(3L).setOwnerUserId(7L).setEditLocked(true));
        CocModuleCreateDTO.Clue request = new CocModuleCreateDTO.Clue();
        request.setTitle("新线索");
        request.setContent("线索正文");
        request.setImportant(true);

        CocModuleClue created = service.addClue(7L, 3L, request);

        assertThat(created.getModuleId()).isEqualTo(3L);
        assertThat(created.getTitle()).isEqualTo("新线索");
        assertThat(created.getContent()).isEqualTo("线索正文");
        verify(clueMapper).insert(created);
    }

    @Test
    void exportReadableProducesPortableArchiveWithoutOwnershipState() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleContextMapper contextMapper =
                mock(CocModuleContextMapper.class);
        CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        CocModuleService service = new CocModuleService(
                moduleMapper, contextMapper, locationMapper,
                mock(CocModuleClueMapper.class),
                mock(CocModuleMaterialMapper.class),
                mock(GroupConversationMapper.class),
                mock(CocModuleLockService.class),
                mock(CocModuleCharacterMapper.class));
        when(moduleMapper.selectById(3L)).thenReturn(new CocModule()
                .setId(3L).setName("自建模组").setAuthor("作者")
                .setIntroduction("简介").setVisible(true)
                .setOwnerUserId(7L).setEditLocked(true));
        when(contextMapper.selectList(any())).thenReturn(java.util.List.of());
        when(locationMapper.selectList(any())).thenReturn(java.util.List.of(
                new CocModuleLocation().setId(11L).setModuleId(3L)
                        .setName("老宅").setSummary("现场")
                        .setContent("正文")));

        CocModuleArchiveDTO archive = service.exportReadable(7L, 3L);

        assertThat(archive.getFormatVersion()).isEqualTo(1);
        assertThat(archive.getModule().getName()).isEqualTo("自建模组");
        assertThat(archive.getModule().getLocations())
                .extracting(CocModuleCreateDTO.Location::getName)
                .containsExactly("老宅");
    }

    @Test
    void readableDetailAllowsDefaultModule() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleContextMapper contextMapper =
                mock(CocModuleContextMapper.class);
        CocModuleService service = new CocModuleService(
                moduleMapper, contextMapper,
                mock(CocModuleLocationMapper.class),
                mock(CocModuleClueMapper.class),
                mock(CocModuleMaterialMapper.class),
                mock(GroupConversationMapper.class),
                mock(CocModuleLockService.class),
                mock(CocModuleCharacterMapper.class));
        CocModule defaultModule = new CocModule()
                .setId(3L).setName("默认模组").setVisible(true)
                .setOwnerUserId(null);
        when(moduleMapper.selectById(3L)).thenReturn(defaultModule);
        when(contextMapper.selectList(any())).thenReturn(java.util.List.of());

        var detail = service.getReadableDetail(7L, 3L);

        assertThat(detail.getModule()).isSameAs(defaultModule);
    }

    @Test
    void readableDetailRejectsAnotherUsersModule() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleService service = serviceWith(moduleMapper);
        when(moduleMapper.selectById(3L)).thenReturn(new CocModule()
                .setId(3L).setVisible(true).setOwnerUserId(8L));

        assertThatThrownBy(() -> service.getReadableDetail(7L, 3L))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("模组不存在或无权查看");
    }

    @Test
    void exportReadableAllowsDefaultModule() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleService service = serviceWith(moduleMapper);
        when(moduleMapper.selectById(3L)).thenReturn(new CocModule()
                .setId(3L).setName("默认模组").setIntroduction("简介")
                .setVisible(true).setOwnerUserId(null));

        CocModuleArchiveDTO archive = service.exportReadable(7L, 3L);

        assertThat(archive.getModule().getName()).isEqualTo("默认模组");
    }

    @Test
    void defaultModuleRemainsReadOnly() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleService service = serviceWith(moduleMapper);
        when(moduleMapper.selectById(3L)).thenReturn(new CocModule()
                .setId(3L).setVisible(true).setOwnerUserId(null));
        CocModuleCreateDTO request = new CocModuleCreateDTO();
        request.setName("修改后");
        request.setIntroduction("修改后简介");

        assertThatThrownBy(() -> service.updateOwned(7L, 3L, request))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("模组不存在或无权操作");
    }

    @Test
    void importOwnedRejectsUnsupportedArchiveVersion() {
        CocModuleService service = serviceWith(mock(CocModuleMapper.class));
        CocModuleArchiveDTO archive = new CocModuleArchiveDTO()
                .setFormatVersion(99)
                .setModule(new CocModuleCreateDTO());

        assertThatThrownBy(() -> service.importOwned(7L, archive))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("版本");
    }

    @Test
    void importOwnedAcceptsMaterialWithoutImageUrl() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleMaterialMapper materialMapper =
                mock(CocModuleMaterialMapper.class);
        CocModuleService service = new CocModuleService(
                moduleMapper,
                mock(CocModuleContextMapper.class),
                mock(CocModuleLocationMapper.class),
                mock(CocModuleClueMapper.class),
                materialMapper,
                mock(GroupConversationMapper.class),
                mock(CocModuleLockService.class),
                mock(CocModuleCharacterMapper.class));
        org.mockito.Mockito.doAnswer(invocation -> {
            ((CocModule) invocation.getArgument(0)).setId(3L);
            return 1;
        }).when(moduleMapper).insert(any(CocModule.class));
        CocModuleCreateDTO.Material material =
                new CocModuleCreateDTO.Material();
        material.setTitle("梦境记录");
        material.setDescription("供守秘人朗读的纯文字材料");
        CocModuleCreateDTO request = new CocModuleCreateDTO();
        request.setName("古树之中");
        request.setIntroduction("调查失踪案");
        request.setMaterials(java.util.List.of(material));
        CocModuleArchiveDTO archive = new CocModuleArchiveDTO()
                .setFormatVersion(1)
                .setModule(request);

        service.importOwned(7L, archive);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.me.galchat.domain.po.CocModuleMaterial.class);
        verify(materialMapper).insert(captor.capture());
        assertThat(captor.getValue().getImageUrl()).isEmpty();
    }

    @Test
    void updateClueContentDoesNotChangeLockedClueMetadata() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleClueMapper clueMapper = mock(CocModuleClueMapper.class);
        CocModuleService service = new CocModuleService(
                moduleMapper,
                mock(CocModuleContextMapper.class),
                mock(CocModuleLocationMapper.class), clueMapper,
                mock(CocModuleMaterialMapper.class),
                mock(GroupConversationMapper.class),
                mock(CocModuleLockService.class),
                mock(CocModuleCharacterMapper.class));
        when(moduleMapper.selectById(3L)).thenReturn(new CocModule()
                .setId(3L).setOwnerUserId(7L).setEditLocked(true));
        when(clueMapper.selectById(12L)).thenReturn(new CocModuleClue()
                .setId(12L).setModuleId(3L).setTitle("原题")
                .setImportant(true).setContent("旧正文"));

        CocModuleClue result = service.updateClueContent(
                7L, 3L, 12L, "新正文");

        assertThat(result.getContent()).isEqualTo("新正文");
        assertThat(result.getTitle()).isEqualTo("原题");
        assertThat(result.getImportant()).isTrue();
        verify(clueMapper).updateById(result);
    }

    @Test
    void createStoresCharacterCardsInInputOrderWithoutBusinessValidation() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleCharacterMapper moduleCharacterMapper =
                mock(CocModuleCharacterMapper.class);
        CocModuleService service = new CocModuleService(
                moduleMapper,
                mock(CocModuleContextMapper.class),
                mock(CocModuleLocationMapper.class),
                mock(CocModuleClueMapper.class),
                mock(CocModuleMaterialMapper.class),
                mock(GroupConversationMapper.class),
                mock(CocModuleLockService.class),
                moduleCharacterMapper);
        org.mockito.Mockito.doAnswer(invocation -> {
            ((CocModule) invocation.getArgument(0)).setId(3L);
            return 1;
        }).when(moduleMapper).insert(any(CocModule.class));
        var jsonMapper = JsonMapper.builder().build();
        var first = jsonMapper.valueToTree(new CharacterCardVO(
                new CocCharacter().setName("无完整属性的NPC"),
                null, null, null));
        var second = jsonMapper.createObjectNode()
                .put("arbitrary", "不校验或丢弃未知结构");
        CocModuleCreateDTO request = new CocModuleCreateDTO();
        request.setName("闹鬼");
        request.setIntroduction("调查老宅");
        request.setCharacters(java.util.List.of(first, second));

        service.create(request);

        var captor = org.mockito.ArgumentCaptor.forClass(
                CocModuleCharacter.class);
        verify(moduleCharacterMapper,
                org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(
                        CocModuleCharacter::getModuleId,
                        CocModuleCharacter::getSortOrder,
                        CocModuleCharacter::getCardData)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(3L, 0, first),
                        org.assertj.core.groups.Tuple.tuple(3L, 1, second));
    }

    @Test
    void createRejectsCharacterWithInvalidDamageBonus() {
        CocModuleService service = serviceWith(mock(CocModuleMapper.class));
        var jsonMapper = JsonMapper.builder().build();
        var character = jsonMapper.createObjectNode();
        character.putObject("character")
                .put("name", "林默")
                .put("damageBonus", "+1D8");
        CocModuleCreateDTO request = new CocModuleCreateDTO();
        request.setName("闹鬼");
        request.setIntroduction("调查老宅");
        request.setCharacters(java.util.List.of(character));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("预设角色“林默”的伤害加值（DB）格式不合法");
    }

    @Test
    void createAcceptsUnsignedDiceDamageBonusFromCharacterParsing() {
        CocModuleService service = serviceWith(mock(CocModuleMapper.class));
        var jsonMapper = JsonMapper.builder().build();
        var character = jsonMapper.createObjectNode();
        character.putObject("character")
                .put("name", "林默")
                .put("damageBonus", "1d4");
        CocModuleCreateDTO request = new CocModuleCreateDTO();
        request.setName("闹鬼");
        request.setIntroduction("调查老宅");
        request.setCharacters(java.util.List.of(character));

        service.create(request);
    }

    @Test
    void createStoresModuleLocationsInSuppliedOrder() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleContextMapper contextMapper = mock(CocModuleContextMapper.class);
        CocModuleLocationMapper locationMapper = mock(CocModuleLocationMapper.class);
        CocModuleService service = new CocModuleService(
                moduleMapper, contextMapper, locationMapper,
                mock(CocModuleClueMapper.class), mock(CocModuleMaterialMapper.class),
                mock(GroupConversationMapper.class), mock(CocModuleLockService.class),
                mock(CocModuleCharacterMapper.class));
        org.mockito.Mockito.doAnswer(invocation -> {
            ((CocModule) invocation.getArgument(0)).setId(3L);
            return 1;
        }).when(moduleMapper).insert(any(CocModule.class));
        java.util.concurrent.atomic.AtomicLong locationIds =
                new java.util.concurrent.atomic.AtomicLong();
        org.mockito.Mockito.doAnswer(invocation -> {
            ((CocModuleLocation) invocation.getArgument(0))
                    .setId(locationIds.incrementAndGet());
            return 1;
        }).when(locationMapper).insert(any(CocModuleLocation.class));
        CocModuleCreateDTO request = new CocModuleCreateDTO();
        request.setName("太阳与九英镑");
        request.setIntroduction("寻找失踪者");
        CocModuleCreateDTO.Location street = new CocModuleCreateDTO.Location();
        street.setName("薰衣草街区");
        street.setSummary("感染爆发地");
        street.setContent("街区原文");
        CocModuleCreateDTO.Location basement = new CocModuleCreateDTO.Location();
        basement.setName("地下室");
        basement.setSummary("法术核心所在地");
        basement.setContent("地下室原文");
        request.setLocations(java.util.List.of(basement, street));

        CocModule result = service.create(request);

        assertThat(result.getId()).isEqualTo(3L);
        var captor = org.mockito.ArgumentCaptor.forClass(CocModuleLocation.class);
        verify(locationMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(CocModuleLocation::getName)
                .containsExactly("地下室", "薰衣草街区");
    }

    @Test
    void deleteRejectsModuleReferencedByAnyConversation() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleContextMapper contextMapper = mock(CocModuleContextMapper.class);
        CocModuleLocationMapper locationMapper = mock(CocModuleLocationMapper.class);
        CocModuleClueMapper clueMapper = mock(CocModuleClueMapper.class);
        CocModuleMaterialMapper materialMapper = mock(CocModuleMaterialMapper.class);
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        CocModuleLockService lockService = mock(CocModuleLockService.class);
        CocModuleService service = new CocModuleService(
                moduleMapper, contextMapper, locationMapper, clueMapper,
                materialMapper, conversationMapper, lockService,
                mock(CocModuleCharacterMapper.class));
        when(lockService.tryWriteLock(3L)).thenReturn(
                new CocModuleLockService.OwnedLock(mock(RLock.class), 1L));
        when(moduleMapper.selectById(3L)).thenReturn(new CocModule().setId(3L));
        when(conversationMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(3L))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("跑团");

        verify(contextMapper, never()).delete(any());
        verify(locationMapper, never()).delete(any());
        verify(clueMapper, never()).delete(any());
        verify(materialMapper, never()).delete(any());
        verify(moduleMapper, never()).deleteById(3L);
        verify(lockService).unlock(any(CocModuleLockService.OwnedLock.class));
    }

    private CocModuleService serviceWith(CocModuleMapper moduleMapper) {
        return new CocModuleService(
                moduleMapper,
                mock(CocModuleContextMapper.class),
                mock(CocModuleLocationMapper.class),
                mock(CocModuleClueMapper.class),
                mock(CocModuleMaterialMapper.class),
                mock(GroupConversationMapper.class),
                mock(CocModuleLockService.class),
                mock(CocModuleCharacterMapper.class));
    }
}

package com.me.galchat.service.impl;

import com.me.galchat.domain.dto.CocModuleCreateDTO;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.CocModuleCharacter;
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
    void createResolvesParentLocationByNameInsideNewModule() {
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
        basement.setParentName("薰衣草街区");
        basement.setSummary("法术核心所在地");
        basement.setContent("地下室原文");
        request.setLocations(java.util.List.of(basement, street));

        CocModule result = service.create(request);

        assertThat(result.getId()).isEqualTo(3L);
        var captor = org.mockito.ArgumentCaptor.forClass(CocModuleLocation.class);
        verify(locationMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(CocModuleLocation::getName, CocModuleLocation::getParentLocationId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("薰衣草街区", null),
                        org.assertj.core.groups.Tuple.tuple("地下室", 1L));
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

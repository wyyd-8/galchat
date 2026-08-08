package com.me.galchat.service.impl;

import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.dto.GroupConversationCreateDTO;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupChatMemberMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.CocModuleMapper;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.service.IUserWorldPrefixService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupConversationServiceTest {

    @Test
    void createTrpgConversationAllowsNoAiInvestigators() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupChatMemberMapper memberMapper = mock(GroupChatMemberMapper.class);
        IUserWorldPrefixService worldService = mock(IUserWorldPrefixService.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleLockService moduleLockService = mock(CocModuleLockService.class);
        CocModuleCharacterInstantiationService moduleCharacterService =
                mock(CocModuleCharacterInstantiationService.class);
        GroupConversationService service = new GroupConversationService(
                conversationMapper,
                memberMapper,
                mock(GroupChatMessageMapper.class),
                mock(GroupReplyPlanMapper.class),
                mock(GroupReplyPlanItemMapper.class),
                worldService,
                mock(IUserCharacterInfoService.class),
                lockService,
                moduleMapper,
                moduleLockService,
                moduleCharacterService);
        when(worldService.checkUserWorldAuth(1L, true)).thenReturn(
                new UserWorldPrefix().setId(1L).setWorldId(10L));
        when(lockService.tryWorldLock(1L)).thenReturn(
                new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        when(moduleLockService.tryReadLock(3L)).thenReturn(
                new CocModuleLockService.OwnedLock(mock(RLock.class), 1L));
        when(moduleMapper.selectById(3L)).thenReturn(
                new CocModule().setId(3L).setVisible(true));
        org.mockito.Mockito.doAnswer(invocation -> {
            ((GroupConversation) invocation.getArgument(0)).setId(7L);
            return 1;
        }).when(conversationMapper).insert(any(GroupConversation.class));
        GroupConversationCreateDTO request = new GroupConversationCreateDTO();
        request.setUserWorldId(1L);
        request.setMode(GroupChatConstant.MODE_TRPG);
        request.setModuleId(3L);
        request.setCharacterIds(List.of());

        GroupConversation result = service.create(request);

        assertThat(result.getId()).isEqualTo(7L);
        assertThat(result.getMode()).isEqualTo(GroupChatConstant.MODE_TRPG);
        verify(memberMapper, never()).insert(any(GroupChatMember.class));
        verify(moduleCharacterService).instantiate(3L, 7L);
    }

    @Test
    void createChatConversationStillRequiresACharacter() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        IUserWorldPrefixService worldService = mock(IUserWorldPrefixService.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        GroupConversationService service = new GroupConversationService(
                conversationMapper,
                mock(GroupChatMemberMapper.class),
                mock(GroupChatMessageMapper.class),
                mock(GroupReplyPlanMapper.class),
                mock(GroupReplyPlanItemMapper.class),
                worldService,
                mock(IUserCharacterInfoService.class),
                lockService,
                mock(CocModuleMapper.class),
                mock(CocModuleLockService.class),
                mock(CocModuleCharacterInstantiationService.class));
        when(worldService.checkUserWorldAuth(1L, true)).thenReturn(
                new UserWorldPrefix().setId(1L).setWorldId(10L));
        when(lockService.tryWorldLock(1L)).thenReturn(
                new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        GroupConversationCreateDTO request = new GroupConversationCreateDTO();
        request.setUserWorldId(1L);
        request.setMode(GroupChatConstant.MODE_CHAT);
        request.setCharacterIds(List.of());

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("群聊参与角色不能为空");
        verify(conversationMapper, never()).insert(any(GroupConversation.class));
    }

    @Test
    void createTrpgConversationStartsWithoutUserReplyPlan() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupChatMemberMapper memberMapper = mock(GroupChatMemberMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper = mock(GroupReplyPlanItemMapper.class);
        IUserWorldPrefixService worldService = mock(IUserWorldPrefixService.class);
        IUserCharacterInfoService characterService = mock(IUserCharacterInfoService.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleLockService moduleLockService = mock(CocModuleLockService.class);
        CocModuleCharacterInstantiationService moduleCharacterService =
                mock(CocModuleCharacterInstantiationService.class);
        GroupConversationService service = new GroupConversationService(
                conversationMapper,
                memberMapper,
                mock(GroupChatMessageMapper.class),
                planMapper,
                itemMapper,
                worldService,
                characterService,
                lockService,
                moduleMapper,
                moduleLockService,
                moduleCharacterService);
        when(worldService.checkUserWorldAuth(1L, true)).thenReturn(
                new UserWorldPrefix().setId(1L).setWorldId(10L));
        when(characterService.listByUserWorldId(1L)).thenReturn(List.of(
                new UserCharacterInfo().setCharacterId(11L)));
        when(lockService.tryWorldLock(1L)).thenReturn(
                new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        when(moduleLockService.tryReadLock(3L)).thenReturn(
                new CocModuleLockService.OwnedLock(mock(RLock.class), 1L));
        when(moduleMapper.selectById(3L)).thenReturn(
                new CocModule().setId(3L).setVisible(true));
        org.mockito.Mockito.doAnswer(invocation -> {
            ((GroupConversation) invocation.getArgument(0)).setId(7L);
            return 1;
        }).when(conversationMapper).insert(any(GroupConversation.class));
        GroupConversationCreateDTO request = new GroupConversationCreateDTO();
        request.setUserWorldId(1L);
        request.setMode(GroupChatConstant.MODE_TRPG);
        request.setModuleId(3L);
        request.setCharacterIds(List.of(11L));

        GroupConversation result = service.create(request);

        assertThat(result.getActiveReplyPlanId()).isNull();
        verify(moduleCharacterService).instantiate(3L, 7L);
        verify(planMapper, never()).insert(any(GroupReplyPlan.class));
        verify(itemMapper, never()).insert(any(GroupReplyPlanItem.class));
        verify(lockService).unlock(any(GroupConversationLockService.OwnedLock.class));
    }

    @Test
    void createTrpgConversationRequiresModule() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        IUserWorldPrefixService worldService = mock(IUserWorldPrefixService.class);
        IUserCharacterInfoService characterService = mock(IUserCharacterInfoService.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        GroupConversationService service = new GroupConversationService(
                conversationMapper,
                mock(GroupChatMemberMapper.class),
                mock(GroupChatMessageMapper.class),
                mock(GroupReplyPlanMapper.class),
                mock(GroupReplyPlanItemMapper.class),
                worldService,
                characterService,
                lockService,
                mock(CocModuleMapper.class),
                mock(CocModuleLockService.class),
                mock(CocModuleCharacterInstantiationService.class));
        when(worldService.checkUserWorldAuth(1L, true)).thenReturn(
                new UserWorldPrefix().setId(1L).setWorldId(10L));
        when(lockService.tryWorldLock(1L)).thenReturn(
                new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        when(characterService.listByUserWorldId(1L)).thenReturn(List.of(
                new UserCharacterInfo().setCharacterId(11L)));
        GroupConversationCreateDTO request = new GroupConversationCreateDTO();
        request.setUserWorldId(1L);
        request.setMode(GroupChatConstant.MODE_TRPG);
        request.setCharacterIds(List.of(11L));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("模组");
        verify(conversationMapper, never()).insert(any(GroupConversation.class));
    }

    @Test
    void normalChatCannotBindTrpgModule() {
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        GroupConversationService service = new GroupConversationService(
                conversationMapper,
                mock(GroupChatMemberMapper.class),
                mock(GroupChatMessageMapper.class),
                mock(GroupReplyPlanMapper.class),
                mock(GroupReplyPlanItemMapper.class),
                mock(IUserWorldPrefixService.class),
                mock(IUserCharacterInfoService.class),
                mock(GroupConversationLockService.class),
                mock(CocModuleMapper.class),
                mock(CocModuleLockService.class),
                mock(CocModuleCharacterInstantiationService.class));
        GroupConversationCreateDTO request =
                new GroupConversationCreateDTO();
        request.setUserWorldId(1L);
        request.setMode(GroupChatConstant.MODE_CHAT);
        request.setModuleId(3L);
        request.setCharacterIds(List.of(11L));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("只有TRPG");
        verify(conversationMapper, never())
                .insert(any(GroupConversation.class));
    }

    @Test
    void createTrpgConversationRejectsMissingModule() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        IUserWorldPrefixService worldService = mock(IUserWorldPrefixService.class);
        IUserCharacterInfoService characterService = mock(IUserCharacterInfoService.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleLockService moduleLockService = mock(CocModuleLockService.class);
        GroupConversationService service = new GroupConversationService(
                conversationMapper,
                mock(GroupChatMemberMapper.class),
                mock(GroupChatMessageMapper.class),
                mock(GroupReplyPlanMapper.class),
                mock(GroupReplyPlanItemMapper.class),
                worldService,
                characterService,
                lockService,
                moduleMapper,
                moduleLockService,
                mock(CocModuleCharacterInstantiationService.class));
        when(worldService.checkUserWorldAuth(1L, true)).thenReturn(
                new UserWorldPrefix().setId(1L).setWorldId(10L));
        when(characterService.listByUserWorldId(1L)).thenReturn(List.of(
                new UserCharacterInfo().setCharacterId(11L)));
        when(lockService.tryWorldLock(1L)).thenReturn(
                new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        when(moduleLockService.tryReadLock(99L)).thenReturn(
                new CocModuleLockService.OwnedLock(mock(RLock.class), 1L));
        GroupConversationCreateDTO request = new GroupConversationCreateDTO();
        request.setUserWorldId(1L);
        request.setMode(GroupChatConstant.MODE_TRPG);
        request.setModuleId(99L);
        request.setCharacterIds(List.of(11L));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("模组不存在");
        verify(conversationMapper, never()).insert(any(GroupConversation.class));
    }

    @Test
    void createTrpgConversationRejectsHiddenModule() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        IUserWorldPrefixService worldService = mock(IUserWorldPrefixService.class);
        IUserCharacterInfoService characterService = mock(IUserCharacterInfoService.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleLockService moduleLockService = mock(CocModuleLockService.class);
        GroupConversationService service = new GroupConversationService(
                conversationMapper,
                mock(GroupChatMemberMapper.class),
                mock(GroupChatMessageMapper.class),
                mock(GroupReplyPlanMapper.class),
                mock(GroupReplyPlanItemMapper.class),
                worldService,
                characterService,
                lockService,
                moduleMapper,
                moduleLockService,
                mock(CocModuleCharacterInstantiationService.class));
        when(worldService.checkUserWorldAuth(1L, true)).thenReturn(
                new UserWorldPrefix().setId(1L).setWorldId(10L));
        when(lockService.tryWorldLock(1L)).thenReturn(
                new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        when(moduleLockService.tryReadLock(3L)).thenReturn(
                new CocModuleLockService.OwnedLock(mock(RLock.class), 1L));
        when(moduleMapper.selectById(3L)).thenReturn(
                new CocModule().setId(3L).setVisible(false));
        GroupConversationCreateDTO request = new GroupConversationCreateDTO();
        request.setUserWorldId(1L);
        request.setMode(GroupChatConstant.MODE_TRPG);
        request.setModuleId(3L);
        request.setCharacterIds(List.of(11L));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("模组不存在或不可选");
        verify(conversationMapper, never()).insert(any(GroupConversation.class));
    }

    @Test
    void createRejectsWhileWorldSaveOrLoadOwnsWorldLock() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        GroupConversationService service = new GroupConversationService(
                conversationMapper,
                mock(GroupChatMemberMapper.class),
                mock(GroupChatMessageMapper.class),
                mock(GroupReplyPlanMapper.class),
                mock(GroupReplyPlanItemMapper.class),
                mock(IUserWorldPrefixService.class),
                mock(IUserCharacterInfoService.class),
                lockService,
                mock(CocModuleMapper.class),
                mock(CocModuleLockService.class),
                mock(CocModuleCharacterInstantiationService.class));
        GroupConversationCreateDTO request = new GroupConversationCreateDTO();
        request.setUserWorldId(1L);
        request.setCharacterIds(List.of(11L));
        when(lockService.tryWorldLock(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("存档或读档");
        verify(conversationMapper, never()).insert(any(GroupConversation.class));
    }

    @Test
    void createAuthorizesWorldBeforeAcquiringWorldLock() {
        IUserWorldPrefixService worldService = mock(IUserWorldPrefixService.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        GroupConversationService service = new GroupConversationService(
                mock(GroupConversationMapper.class),
                mock(GroupChatMemberMapper.class),
                mock(GroupChatMessageMapper.class),
                mock(GroupReplyPlanMapper.class),
                mock(GroupReplyPlanItemMapper.class),
                worldService,
                mock(IUserCharacterInfoService.class),
                lockService,
                mock(CocModuleMapper.class),
                mock(CocModuleLockService.class),
                mock(CocModuleCharacterInstantiationService.class));
        GroupConversationCreateDTO request = new GroupConversationCreateDTO();
        request.setUserWorldId(1L);
        request.setCharacterIds(List.of(11L));
        doThrow(new UserRequestException("无权访问该用户世界"))
                .when(worldService).checkUserWorldAuth(1L, true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("无权");
        verify(lockService, never()).tryWorldLock(1L);
    }

    @Test
    void createRejectsCharacterThatHasNotBeenCreatedInUserWorld() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        IUserWorldPrefixService worldService = mock(IUserWorldPrefixService.class);
        IUserCharacterInfoService characterService = mock(IUserCharacterInfoService.class);
        GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        GroupConversationService service = new GroupConversationService(conversationMapper,
                mock(GroupChatMemberMapper.class), mock(GroupChatMessageMapper.class),
                mock(GroupReplyPlanMapper.class), mock(GroupReplyPlanItemMapper.class),
                worldService, characterService, lockService,
                mock(CocModuleMapper.class), mock(CocModuleLockService.class),
                mock(CocModuleCharacterInstantiationService.class));
        when(lockService.tryWorldLock(1L)).thenReturn(
                new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        when(worldService.checkUserWorldAuth(1L, true)).thenReturn(
                new UserWorldPrefix().setId(1L).setWorldId(10L));
        when(characterService.listByUserWorldId(1L)).thenReturn(List.of(
                new UserCharacterInfo().setCharacterId(11L)));
        GroupConversationCreateDTO request = new GroupConversationCreateDTO();
        request.setUserWorldId(1L);
        request.setCharacterIds(List.of(11L, 12L));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("只有已创建的角色才能加入群聊");
    }

    @Test
    void listReturnsLatestCompletedMessageInOneBatch() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        IUserWorldPrefixService worldService = mock(IUserWorldPrefixService.class);
        GroupConversationService service = new GroupConversationService(conversationMapper,
                mock(GroupChatMemberMapper.class), messageMapper, mock(GroupReplyPlanMapper.class),
                mock(GroupReplyPlanItemMapper.class), worldService, mock(IUserCharacterInfoService.class),
                mock(GroupConversationLockService.class), mock(CocModuleMapper.class),
                mock(CocModuleLockService.class),
                mock(CocModuleCharacterInstantiationService.class));
        LocalDateTime messageTime = LocalDateTime.of(2026, 7, 14, 10, 30);
        GroupConversation first = new GroupConversation().setId(2L).setUserWorldId(1L).setTitle("调查");
        GroupConversation second = new GroupConversation().setId(1L).setUserWorldId(1L).setTitle("闲聊");
        when(conversationMapper.selectList(any())).thenReturn(List.of(first, second));
        when(messageMapper.selectLatestCompletedByConversationIds(List.of(2L, 1L))).thenReturn(List.of(
                new GroupChatMessage().setConversationId(2L).setContent("发现了一把钥匙")
                        .setCreatedAt(messageTime)));

        var result = service.list(1L, null);

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().getLastChatContent()).isEqualTo("发现了一把钥匙");
        assertThat(result.getFirst().getLastChatTime()).isEqualTo(messageTime);
        assertThat(result.get(1).getLastChatContent()).isNull();
        assertThat(result.get(1).getLastChatTime()).isNull();
        verify(worldService).checkUserWorldAuth(1L, false);
        verify(messageMapper).selectLatestCompletedByConversationIds(List.of(2L, 1L));
    }

    @Test
    void getIncludesSelectedCharactersBeforeInvestigatorCardsExist() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupChatMemberMapper memberMapper = mock(GroupChatMemberMapper.class);
        IUserWorldPrefixService worldService = mock(IUserWorldPrefixService.class);
        GroupConversationService service = new GroupConversationService(conversationMapper,
                memberMapper, mock(GroupChatMessageMapper.class), mock(GroupReplyPlanMapper.class),
                mock(GroupReplyPlanItemMapper.class), worldService, mock(IUserCharacterInfoService.class),
                mock(GroupConversationLockService.class), mock(CocModuleMapper.class),
                mock(CocModuleLockService.class), mock(CocModuleCharacterInstantiationService.class));
        when(conversationMapper.selectById(7L)).thenReturn(new GroupConversation()
                .setId(7L).setUserWorldId(1L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setGameDayNo(2)
                .setGameTimePeriod("EVENING")
                .setGameTimeRevision(3));
        when(memberMapper.selectList(any())).thenReturn(List.of(
                new GroupChatMember().setActorType(GroupChatConstant.ACTOR_CHARACTER).setActorId(11L).setPosition(0),
                new GroupChatMember().setActorType(GroupChatConstant.ACTOR_CHARACTER).setActorId(22L).setPosition(1)));

        String json = JsonMapper.builder().build().writeValueAsString(service.get(7L));

        assertThat(json).contains("\"characterIds\":[11,22]");
        assertThat(json)
                .contains("\"gameTime\"")
                .contains("\"displayText\":\"第二天 - 晚上\"")
                .contains("\"revision\":3");
    }
}

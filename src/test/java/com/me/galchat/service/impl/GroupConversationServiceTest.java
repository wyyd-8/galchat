package com.me.galchat.service.impl;

import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
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
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.service.IUserWorldPrefixService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupConversationServiceTest {

    @Test
    void createTrpgConversationStartsWithoutUserReplyPlan() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupChatMemberMapper memberMapper = mock(GroupChatMemberMapper.class);
        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper = mock(GroupReplyPlanItemMapper.class);
        IUserWorldPrefixService worldService = mock(IUserWorldPrefixService.class);
        IUserCharacterInfoService characterService = mock(IUserCharacterInfoService.class);
        GroupConversationService service = new GroupConversationService(
                conversationMapper,
                memberMapper,
                mock(GroupChatMessageMapper.class),
                planMapper,
                itemMapper,
                worldService,
                characterService);
        when(worldService.checkUserWorldAuth(1L, true)).thenReturn(
                new UserWorldPrefix().setId(1L).setWorldId(10L));
        when(characterService.listByUserWorldId(1L)).thenReturn(List.of(
                new UserCharacterInfo().setCharacterId(11L)));
        org.mockito.Mockito.doAnswer(invocation -> {
            ((GroupConversation) invocation.getArgument(0)).setId(7L);
            return 1;
        }).when(conversationMapper).insert(any(GroupConversation.class));
        GroupConversationCreateDTO request = new GroupConversationCreateDTO();
        request.setUserWorldId(1L);
        request.setMode(GroupChatConstant.MODE_TRPG);
        request.setCharacterIds(List.of(11L));

        GroupConversation result = service.create(request);

        assertThat(result.getActiveReplyPlanId()).isNull();
        verify(planMapper, never()).insert(any(GroupReplyPlan.class));
        verify(itemMapper, never()).insert(any(GroupReplyPlanItem.class));
    }

    @Test
    void createRejectsCharacterThatHasNotBeenCreatedInUserWorld() {
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        IUserWorldPrefixService worldService = mock(IUserWorldPrefixService.class);
        IUserCharacterInfoService characterService = mock(IUserCharacterInfoService.class);
        GroupConversationService service = new GroupConversationService(conversationMapper,
                mock(GroupChatMemberMapper.class), mock(GroupChatMessageMapper.class),
                mock(GroupReplyPlanMapper.class), mock(GroupReplyPlanItemMapper.class),
                worldService, characterService);
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
                mock(GroupReplyPlanItemMapper.class), worldService, mock(IUserCharacterInfoService.class));
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
}

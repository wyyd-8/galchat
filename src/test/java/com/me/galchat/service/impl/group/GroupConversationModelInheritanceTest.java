package com.me.galchat.service.impl.group;

import com.me.galchat.domain.dto.GroupConversationCreateDTO;
import com.me.galchat.domain.po.*;
import com.me.galchat.mapper.*;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.impl.trpg.CocModuleCharacterInstantiationService;
import com.me.galchat.service.impl.trpg.CocModuleLockService;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupConversationModelInheritanceTest {
    @Mock TrpgCompletionMapper completionMapper;
    @Mock GroupConversationMapper conversationMapper;
    @Mock GroupChatMemberMapper memberMapper;
    @Mock GroupChatMessageMapper messageMapper;
    @Mock GroupReplyPlanMapper replyPlanMapper;
    @Mock GroupReplyPlanItemMapper replyPlanItemMapper;
    @Mock IUserWorldPrefixService worldService;
    @Mock IUserCharacterInfoService characterService;
    @Mock GroupConversationLockService lockService;
    @Mock CocModuleMapper moduleMapper;
    @Mock CocModuleLockService moduleLockService;
    @Mock CocModuleCharacterInstantiationService moduleCharacterService;
    @Mock GroupActorRuntimeConfigMapper runtimeConfigMapper;
    @InjectMocks GroupConversationService service;

    @ParameterizedTest
    @ValueSource(strings = {"chat", "trpg"})
    void selectedCharactersInheritTheirOwnDirectChatModels(String mode) {
        when(worldService.checkUserWorldAuth(1L, true)).thenReturn(
                new UserWorldPrefix().setId(1L).setWorldId(10L));
        when(lockService.tryWorldLock(1L)).thenReturn(
                new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        when(characterService.listByUserWorldId(1L)).thenReturn(List.of(
                new UserCharacterInfo().setCharacterId(11L).setModelApiId(101L),
                new UserCharacterInfo().setCharacterId(12L).setModelApiId(102L),
                new UserCharacterInfo().setCharacterId(13L),
                new UserCharacterInfo().setCharacterId(14L).setModelApiId(104L)));
        doAnswer(invocation -> {
            ((GroupConversation) invocation.getArgument(0)).setId(7L);
            return 1;
        }).when(conversationMapper).insert(any(GroupConversation.class));
        GroupConversationCreateDTO request = new GroupConversationCreateDTO();
        request.setUserWorldId(1L);
        request.setMode(mode);
        request.setCharacterIds(List.of(12L, 11L, 13L, 11L));
        if ("trpg".equals(mode)) {
            request.setModuleId(3L);
            when(moduleLockService.tryReadLock(3L)).thenReturn(
                    new CocModuleLockService.OwnedLock(mock(RLock.class), 1L));
            when(moduleMapper.selectById(3L)).thenReturn(
                    new CocModule().setId(3L).setVisible(true));
        }

        service.create(request);

        ArgumentCaptor<GroupActorRuntimeConfig> configs =
                ArgumentCaptor.forClass(GroupActorRuntimeConfig.class);
        verify(runtimeConfigMapper, times(2)).insert(configs.capture());
        assertThat(configs.getAllValues()).extracting(GroupActorRuntimeConfig::getActorId)
                .containsExactly(12L, 11L);
        assertThat(configs.getAllValues()).extracting(GroupActorRuntimeConfig::getModelApiId)
                .containsExactly(102L, 101L);
        assertThat(configs.getAllValues()).allSatisfy(config -> {
            assertThat(config.getConversationId()).isEqualTo(7L);
            assertThat(config.getActorType()).isEqualTo("character");
            assertThat(config.getActorKey()).isEqualTo("character:" + config.getActorId());
            assertThat(config.getControlMode()).isEqualTo("MODEL");
            assertThat(config.getCreatedAt()).isNotNull();
            assertThat(config.getUpdatedAt()).isNotNull();
        });
    }
}

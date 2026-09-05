package com.me.galchat.service.impl.group;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.ITrpgRedisStateService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupConversationDeletionServiceTest {

    @Test
    void deletesAuthorizedConversationUnderWorldAndConversationLocks() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupConversationDeletionStore deletionStore =
                mock(GroupConversationDeletionStore.class);
        ITrpgRedisStateService redisStateService =
                mock(ITrpgRedisStateService.class);
        GroupGenerationStreamRegistry generationRegistry =
                mock(GroupGenerationStreamRegistry.class);
        GroupConversationDeletionService service =
                new GroupConversationDeletionService(
                        conversationService,
                        lockService,
                        deletionStore,
                        redisStateService,
                        generationRegistry);
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setUserWorldId(3L)
                .setMode(GroupChatConstant.MODE_CHAT);
        GroupConversationLockService.OwnedLock worldLock =
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L);
        GroupConversationLockService.OwnedLock conversationLock =
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L);
        when(conversationService.requireAuthorized(7L))
                .thenReturn(conversation);
        when(lockService.tryWorldLock(3L)).thenReturn(worldLock);
        when(lockService.tryLock(7L)).thenReturn(conversationLock);

        service.delete(7L);

        var ordered = inOrder(
                conversationService, lockService, deletionStore,
                redisStateService, generationRegistry);
        ordered.verify(conversationService).requireAuthorized(7L);
        ordered.verify(lockService).tryWorldLock(3L);
        ordered.verify(lockService).tryLock(7L);
        ordered.verify(conversationService).requireAuthorized(7L);
        ordered.verify(deletionStore).delete(conversation);
        ordered.verify(lockService).unlock(conversationLock);
        ordered.verify(lockService).unlock(worldLock);
        ordered.verify(redisStateService).clear(7L);
        ordered.verify(generationRegistry).evict(7L);
    }

    @Test
    void rejectsDeletionWhileWorldSaveOrLoadOwnsWorldLock() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupConversationDeletionStore deletionStore =
                mock(GroupConversationDeletionStore.class);
        ITrpgRedisStateService redisStateService =
                mock(ITrpgRedisStateService.class);
        GroupGenerationStreamRegistry generationRegistry =
                mock(GroupGenerationStreamRegistry.class);
        GroupConversationDeletionService service =
                new GroupConversationDeletionService(
                        conversationService,
                        lockService,
                        deletionStore,
                        redisStateService,
                        generationRegistry);
        when(conversationService.requireAuthorized(7L)).thenReturn(
                new GroupConversation().setId(7L).setUserWorldId(3L));
        when(lockService.tryWorldLock(3L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("当前世界正在存档或读档，请稍后再删除会话");
        verify(lockService, never()).tryLock(7L);
        verify(deletionStore, never()).delete(
                org.mockito.ArgumentMatchers.any());
        verify(redisStateService, never()).clear(7L);
        verify(generationRegistry, never()).evict(7L);
    }

    @Test
    void rejectsDeletionWhileConversationIsGenerating() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupConversationDeletionStore deletionStore =
                mock(GroupConversationDeletionStore.class);
        ITrpgRedisStateService redisStateService =
                mock(ITrpgRedisStateService.class);
        GroupGenerationStreamRegistry generationRegistry =
                mock(GroupGenerationStreamRegistry.class);
        GroupConversationDeletionService service =
                new GroupConversationDeletionService(
                        conversationService,
                        lockService,
                        deletionStore,
                        redisStateService,
                        generationRegistry);
        GroupConversationLockService.OwnedLock worldLock =
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L);
        when(conversationService.requireAuthorized(7L)).thenReturn(
                new GroupConversation().setId(7L).setUserWorldId(3L));
        when(lockService.tryWorldLock(3L)).thenReturn(worldLock);
        when(lockService.tryLock(7L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("群聊正在生成回复，请稍后再删除");
        verify(lockService).unlock(worldLock);
        verify(deletionStore, never()).delete(
                org.mockito.ArgumentMatchers.any());
        verify(redisStateService, never()).clear(7L);
        verify(generationRegistry, never()).evict(7L);
    }
}

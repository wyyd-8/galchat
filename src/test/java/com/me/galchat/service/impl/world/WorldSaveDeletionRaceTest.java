package com.me.galchat.service.impl.world;

import com.me.galchat.domain.dto.UserWorldSaveCreateDTO;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.impl.chat.SingleChatLockService;
import com.me.galchat.service.impl.group.GroupConversationLockService;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.redisson.api.RLock;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorldSaveDeletionRaceTest {
    @Mock IUserWorldPrefixService worlds;
    @Mock UserCharacterInfoMapper characters;
    @Mock SingleChatLockService singleLocks;
    @Mock GroupConversationLockService groupLocks;
    @Mock TransactionTemplate transactions;
    @Mock com.me.galchat.mapper.GroupConversationMapper conversations;
    @Mock com.me.galchat.service.impl.group.GroupTurnRecoveryService recovery;
    @InjectMocks UserWorldSaveServiceImpl service;
    AutoCloseable mocks;
    @BeforeEach void setup() { mocks = MockitoAnnotations.openMocks(this);
        com.me.galchat.support.MybatisPlusTestSupport.initialize(com.me.galchat.domain.po.GroupConversation.class, com.me.galchat.domain.po.UserCharacterInfo.class);
        when(conversations.selectList(any())).thenReturn(List.of());
        when(transactions.execute(any())).thenReturn(new com.me.galchat.domain.po.UserWorldSave().setUserWorldId(3L)); }
    @AfterEach void close() throws Exception { mocks.close(); }

    @Test void cannotSaveAWorldDeletedBeforeItsMutationLockWasAcquired() {
        when(worlds.checkUserWorldAuth(1L, 3L, true)).thenReturn(new UserWorldPrefix().setId(3L).setUserId(1L));
        when(characters.selectList(any())).thenReturn(List.of());
        when(singleLocks.lockConversations(eq(3L), any())).thenReturn(List.of());
        when(groupLocks.tryWorldLock(3L)).thenAnswer(call -> {
            when(worlds.checkUserWorldAuth(1L, 3L, true)).thenThrow(new UserRequestException("用户世界不存在"));
            return new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L);
        });
        assertThatThrownBy(() -> service.saveWorld(1L, 3L, new UserWorldSaveCreateDTO()))
                .isInstanceOf(UserRequestException.class).hasMessage("用户世界不存在");
        verifyNoInteractions(transactions);
    }
}

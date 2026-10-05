package com.me.galchat.service.impl.user;

import com.me.galchat.domain.po.*;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.service.*;
import com.me.galchat.service.impl.group.GroupConversationLockService;
import com.me.galchat.support.MybatisPlusTestSupport;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.redisson.api.RLock;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserCharacterCreationLockTest {
    @Mock ICharacterTemplateService templates;
    @Mock IUserWorldPrefixService worlds;
    @Mock UserCharacterInfoMapper characters;
    @Mock GroupConversationLockService locks;
    @InjectMocks UserCharacterInfoServiceImpl service;
    AutoCloseable mocks;

    @BeforeEach void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        MybatisPlusTestSupport.initialize(UserCharacterInfo.class);
        ReflectionTestUtils.setField(service, "baseMapper", characters);
        ReflectionTestUtils.setField(service, "entityClass", UserCharacterInfo.class);
        when(worlds.checkUserWorldAuth(3L, true)).thenReturn(new UserWorldPrefix().setId(3L).setWorldId(10L));
        when(templates.getCharacterTemplateById(7L)).thenReturn(new CharacterTemplate().setId(7L).setWorldId(10L).setName("角色"));
        when(characters.insert(any(UserCharacterInfo.class))).thenReturn(1);
    }
    @AfterEach void close() throws Exception { mocks.close(); }

    @Test void rejectsCreationWhileTheWorldIsBeingDeleted() {
        when(locks.tryWorldLock(3L)).thenReturn(null);
        assertThatThrownBy(() -> service.addCharacter(3L, 7L)).isInstanceOf(UserRequestException.class);
        verify(characters, never()).insert(any(UserCharacterInfo.class));
    }

    @Test void checksWorldExistenceAfterAcquiringTheLock() {
        var lock = new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L);
        when(locks.tryWorldLock(3L)).thenAnswer(call -> {
            when(worlds.checkUserWorldAuth(3L, true)).thenThrow(new UserRequestException("用户世界不存在"));
            return lock;
        });
        assertThatThrownBy(() -> service.addCharacter(3L, 7L)).isInstanceOf(UserRequestException.class)
                .hasMessage("用户世界不存在");
        verify(characters, never()).insert(any(UserCharacterInfo.class));
        verify(locks).unlock(lock);
    }
}

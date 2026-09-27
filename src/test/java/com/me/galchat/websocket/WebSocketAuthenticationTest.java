package com.me.galchat.websocket;

import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.dto.ChatMessageDTO;
import com.me.galchat.redis.ChatLuaScripts;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.utils.JwtUtils;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RDelayedQueue;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebSocketAuthenticationTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {3L, 999L})
    void queuesOnlyTheAuthenticatedWorldWithoutAdditionalLookups(Long requestedWorldId) throws Exception {
        JwtUtils jwt = new JwtUtils(Base64.getEncoder().encodeToString(new byte[32]));
        IUserWorldPrefixService worlds = mock(IUserWorldPrefixService.class);
        UserWorldPrefix owned = new UserWorldPrefix().setId(10L).setUserId(7L)
                .setWorldId(3L).setThinkStatus(false).setEotDetectionStatus(false);
        when(worlds.checkUserWorldAuth(7L, 10L, true)).thenReturn(owned);
        when(worlds.getById(10L)).thenReturn(owned);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ChatLuaScripts scripts = new ChatLuaScripts();
        when(redis.execute(eq(scripts.updateTypingScript()), anyList(), eq("0"), anyString()))
                .thenReturn("0:5:1");
        WebSocketServer server = new WebSocketServer(worlds, jwt, redis, null, scripts, null);
        @SuppressWarnings("unchecked")
        RDelayedQueue<ChatMessageDTO> queue = mock(RDelayedQueue.class);
        Object previousQueue = ReflectionTestUtils.getField(WebSocketServer.class, "delayedQueue");
        ReflectionTestUtils.setField(WebSocketServer.class, "delayedQueue", queue);
        Session session = session(jwt.generateToken(Map.of("id", 7)));
        try {
            server.onOpen(session, config());
            String worldField = requestedWorldId == null ? "" : ",\"worldId\":" + requestedWorldId;

            server.onMessage("{\"type\":\"typing\",\"userWorldId\":888,\"characterId\":2,\"isTyping\":false"
                    + worldField + "}", session);

            ArgumentCaptor<ChatMessageDTO> task = ArgumentCaptor.forClass(ChatMessageDTO.class);
            verify(queue).offer(task.capture(), anyLong(), eq(TimeUnit.MILLISECONDS));
            assertEquals(3L, task.getValue().getWorldId());
            assertEquals(10L, task.getValue().getUserWorldId());
            assertEquals(2L, task.getValue().getCharacterId());
            verify(worlds).checkUserWorldAuth(7L, 10L, true);
            verify(worlds).getById(10L);
            verifyNoMoreInteractions(worlds);
        } finally {
            server.onClose(session);
            ReflectionTestUtils.setField(WebSocketServer.class, "delayedQueue", previousQueue);
        }
    }

    @Test
    void acceptsTheConfiguredSigningKeyAndChecksWorldOwnership() throws Exception {
        JwtUtils jwt = new JwtUtils(Base64.getEncoder().encodeToString(new byte[32]));
        IUserWorldPrefixService worlds = mock(IUserWorldPrefixService.class);
        when(worlds.checkUserWorldAuth(7L, 10L, true))
                .thenReturn(new UserWorldPrefix().setId(10L).setUserId(7L));
        WebSocketServer server = new WebSocketServer(worlds, jwt, null, null, null, null);
        Session session = session(jwt.generateToken(Map.of("id", 7)));

        server.onOpen(session, config());

        verify(worlds).checkUserWorldAuth(7L, 10L, true);
        verify(session, never()).close(any(CloseReason.class));
        server.onClose(session);
    }

    @Test
    void rejectsADifferentSigningKeyBeforeReadingWorlds() throws Exception {
        JwtUtils jwt = new JwtUtils(Base64.getEncoder().encodeToString(new byte[32]));
        byte[] otherKey = new byte[32];
        java.util.Arrays.fill(otherKey, (byte) 1);
        JwtUtils other = new JwtUtils(Base64.getEncoder().encodeToString(otherKey));
        IUserWorldPrefixService worlds = mock(IUserWorldPrefixService.class);
        WebSocketServer server = new WebSocketServer(worlds, jwt, null, null, null, null);
        Session session = session(other.generateToken(Map.of("id", 7)));

        server.onOpen(session, config());

        verify(session).close(any(CloseReason.class));
        verifyNoInteractions(worlds);
    }

    private Session session(String token) {
        Session session = mock(Session.class);
        when(session.getId()).thenReturn("jwt-auth-test");
        when(session.getRequestParameterMap()).thenReturn(Map.of(
                "token", List.of(token), "userWorldId", List.of("10")));
        return session;
    }

    private EndpointConfig config() {
        HandshakeRequest request = mock(HandshakeRequest.class);
        when(request.getHeaders()).thenReturn(Map.of());
        EndpointConfig config = mock(EndpointConfig.class);
        when(config.getUserProperties()).thenReturn(Map.of(HandshakeRequest.class.getName(), request));
        return config;
    }
}

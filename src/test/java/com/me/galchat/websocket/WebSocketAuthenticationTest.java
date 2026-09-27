package com.me.galchat.websocket;

import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.po.UserChatHistory;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
import org.json.JSONObject;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.utils.JwtUtils;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebSocketAuthenticationTest {
    @Test
    void pushesPersistedMessagesOnlyToTheAuthenticatedWorldAndStopsAfterClose() throws Exception {
        JwtUtils jwt = new JwtUtils(Base64.getEncoder().encodeToString(new byte[32]));
        IUserWorldPrefixService worlds = mock(IUserWorldPrefixService.class);
        when(worlds.checkUserWorldAuth(7L, 10L, true))
                .thenReturn(new UserWorldPrefix().setId(10L).setUserId(7L));
        WebSocketServer server = new WebSocketServer(worlds, jwt);
        Session session = session(jwt.generateToken(Map.of("id", 7)));
        RemoteEndpoint.Async remote = mock(RemoteEndpoint.Async.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getAsyncRemote()).thenReturn(remote);
        server.onOpen(session, config());
        try {
            UserChatHistory message = new UserChatHistory().setId(42L).setUserWorldId(99L)
                    .setCharacterId(2L).setType("assistant").setContent("记得休息");
            server.sendMessageToSession(message);
            verifyNoInteractions(remote);

            message.setUserWorldId(10L);
            server.sendMessageToSession(message);
            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(remote).sendText(payload.capture(), any(SendHandler.class));
            JSONObject json = new JSONObject(payload.getValue());
            assertEquals(42L, json.getLong("id"));
            assertEquals(10L, json.getLong("userWorldId"));
            assertEquals("记得休息", json.getString("content"));

            server.onClose(session);
            server.sendMessageToSession(message);
            verifyNoMoreInteractions(remote);
        } finally {
            server.onClose(session);
        }
    }

    @Test
    void acceptsTheConfiguredSigningKeyAndChecksWorldOwnership() throws Exception {
        JwtUtils jwt = new JwtUtils(Base64.getEncoder().encodeToString(new byte[32]));
        IUserWorldPrefixService worlds = mock(IUserWorldPrefixService.class);
        when(worlds.checkUserWorldAuth(7L, 10L, true))
                .thenReturn(new UserWorldPrefix().setId(10L).setUserId(7L));
        WebSocketServer server = new WebSocketServer(worlds, jwt);
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
        WebSocketServer server = new WebSocketServer(worlds, jwt);
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

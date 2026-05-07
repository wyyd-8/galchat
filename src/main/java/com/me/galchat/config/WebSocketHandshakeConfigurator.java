package com.me.galchat.config;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import org.springframework.web.socket.server.standard.SpringConfigurator;

public class WebSocketHandshakeConfigurator extends SpringConfigurator {

    @Override
    public void modifyHandshake(ServerEndpointConfig config,
                                HandshakeRequest request,
                                HandshakeResponse response) {
        config.getUserProperties().put(HandshakeRequest.class.getName(), request);
    }
}

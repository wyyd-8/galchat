package com.me.galchat.config;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

@Component
public class WebSocketHandshakeConfigurator extends ServerEndpointConfig.Configurator implements ApplicationContextAware {

    private static volatile ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        WebSocketHandshakeConfigurator.applicationContext = applicationContext;
    }

    @Override
    public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
        if (applicationContext == null) {
            return super.getEndpointInstance(endpointClass);
        }
        return applicationContext.getBean(endpointClass);
    }

    @Override
    public void modifyHandshake(ServerEndpointConfig config,
                                HandshakeRequest request,
                                HandshakeResponse response) {
        config.getUserProperties().put(HandshakeRequest.class.getName(), request);
    }
}

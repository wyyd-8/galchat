package com.me.galchat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

@Configuration
public class WebSocketEndpointConfig {

    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter() {
            @Override
            public void afterPropertiesSet() {
                if (getServerContainer() != null) {
                    super.afterPropertiesSet();
                }
            }

            @Override
            public void afterSingletonsInstantiated() {
                if (getServerContainer() != null) {
                    super.afterSingletonsInstantiated();
                }
            }
        };
    }
}

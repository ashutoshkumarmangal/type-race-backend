package com.typerush.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.typerush.game.GameSocketHandler;
import com.typerush.security.AuthHandshakeHandler;
import com.typerush.security.AuthHandshakeInterceptor;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GameSocketHandler gameSocketHandler;
    private final GameProperties properties;
    private final AuthHandshakeInterceptor authHandshakeInterceptor;

    public WebSocketConfig(GameSocketHandler gameSocketHandler, GameProperties properties,
            AuthHandshakeInterceptor authHandshakeInterceptor) {
        this.gameSocketHandler = gameSocketHandler;
        this.properties = properties;
        this.authHandshakeInterceptor = authHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gameSocketHandler, "/ws/game")
                .addInterceptors(authHandshakeInterceptor)
                .setHandshakeHandler(new AuthHandshakeHandler())
                .setAllowedOriginPatterns(properties.getCors().getAllowedOrigins().toArray(String[]::new));
    }
}
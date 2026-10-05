package com.typerush.security;

import java.security.Principal;
import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * Promotes the identity verified by {@link AuthHandshakeInterceptor} to the WebSocket session
 * principal.
 *
 * <p>Spring's stock {@code AbstractHandshakeHandler#determineUser} derives the session principal
 * from {@code HttpServletRequest#getUserPrincipal()} and ignores the handshake attribute map
 * entirely, so a token-based handshake would otherwise produce an anonymous session. Overriding
 * the lookup lets the game engine keep reading {@code WebSocketSession#getPrincipal()} as its
 * single source of truth.
 */
public class AuthHandshakeHandler extends DefaultHandshakeHandler {

    /** Attribute key reserved by {@link AuthHandshakeInterceptor} for the verified identity. */
    public static final String PRINCIPAL_ATTRIBUTE = Principal.class.getName();

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        Object verified = attributes == null ? null : attributes.get(PRINCIPAL_ATTRIBUTE);
        if (verified instanceof Principal principal) {
            return principal;
        }
        // Fall back to the container/servlet-chain principal for non-token (e.g. test) clients.
        return super.determineUser(request, wsHandler, attributes);
    }
}
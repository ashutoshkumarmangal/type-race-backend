package com.typerush.security;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import com.typerush.persistence.Player;
import com.typerush.persistence.PlayerRepository;

import io.jsonwebtoken.Claims;

/**
 * Authenticates the WebSocket upgrade itself.
 *
 * <p>This is the load-bearing check for the whole system: browsers cannot attach an
 * {@code Authorization} header to a WebSocket handshake, so the token arrives in the query string and
 * is verified here — before any game state is created for the connection. A connection that gets
 * through here carries a {@link GamePrincipal} as its session principal, which is the only identity
 * the game engine will ever read.
 */
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthHandshakeInterceptor.class);
    private static final String QUERY = "token";

    private final JwtService jwt;
    private final PlayerRepository players;

    public AuthHandshakeInterceptor(JwtService jwt, PlayerRepository players) {
        this.jwt = jwt;
        this.players = players;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = queryParam(request.getURI().getRawQuery(), QUERY);
        if (token == null) {
            // Also accept the header, which non-browser clients (the smoke script, tests) can send.
            token = Optional.ofNullable(request.getHeaders().getFirst("Authorization"))
                    .filter(h -> h.regionMatches(true, 0, "Bearer ", 0, 7))
                    .map(h -> h.substring(7).trim())
                    .orElse(null);
        }
        if (token == null) {
            reject(response, "missing access token");
            return false;
        }

        Optional<Claims> claims = jwt.parse(token);
        if (claims.isEmpty()) {
            reject(response, "invalid or expired access token");
            return false;
        }

        long playerId;
        try {
            playerId = Long.parseLong(claims.get().getSubject());
        } catch (RuntimeException e) {
            reject(response, "malformed token subject");
            return false;
        }

        Player player = players.findById(playerId).orElse(null);
        if (player == null || !player.isCredentialed()) {
            reject(response, "unknown or uncredentialed player");
            return false;
        }

        // AuthHandshakeHandler#determineUser promotes this key to WebSocketSession#getPrincipal().
        // Storing it under any other name leaves the session anonymous, which the handler then treats
        // as a failure and closes.
        attributes.put(AuthHandshakeHandler.PRINCIPAL_ATTRIBUTE, new GamePrincipal(
                player.getId(), player.getUsername(), player.getNickname(), player.getRole(), null));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // nothing to do
    }

    private void reject(ServerHttpResponse response, String reason) {
        log.debug("websocket handshake rejected: {}", reason);
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    /** Minimal query-string parse; avoids treating an encoded token as a different parameter. */
    static String queryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
package com.typerush.security;

import java.security.Principal;

/**
 * The authenticated racer behind a REST call or a WebSocket session.
 *
 * <p>Identity is always derived from a verified token, never from anything the client typed. The
 * session id is what the game engine keys its in-memory state on, so it travels with the principal.
 */
public record GamePrincipal(Long playerId, String username, String nickname, String role, String sessionId)
        implements Principal {

    @Override
    public String getName() {
        return username == null ? ("player-" + playerId) : username;
    }

    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
package com.typerush.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.typerush.persistence.Player;
import com.typerush.persistence.PlayerRepository;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Turns a valid {@code Authorization: Bearer} header into an authenticated {@link GamePrincipal}.
 *
 * <p>An absent or bad token is not an error here; the request simply stays anonymous and the
 * authorization rules decide whether that is acceptable.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtService jwt;
    private final PlayerRepository players;

    public JwtAuthFilter(JwtService jwt, PlayerRepository players) {
        this.jwt = jwt;
        this.players = players;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(7).trim();
            authenticate(token, request).ifPresent(authentication -> SecurityContextHolder
                    .getContext().setAuthentication(authentication));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Optional<Authentication> authenticate(String token, HttpServletRequest request) {
        return jwt.parse(token).flatMap(claims -> {
            long playerId;
            try {
                playerId = Long.parseLong(claims.getSubject());
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
            Player player = players.findById(playerId).orElse(null);
            if (player == null || !player.isCredentialed()) {
                return java.util.Optional.empty();
            }
            GamePrincipal principal = new GamePrincipal(
                    player.getId(),
                    player.getUsername(),
                    player.getNickname(),
                    player.getRole(),
                    null);
            // The three-argument constructor is what marks the token authenticated; the two-argument
            // one leaves isAuthenticated() false and would fail downstream checks.
            var authorities = List.of(
                    new SimpleGrantedAuthority("ROLE_" + player.getRole().toUpperCase()),
                    new SimpleGrantedAuthority("SCOPE_race"));
            var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            return Optional.of(authentication);
        });
    }
}
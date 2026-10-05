package com.typerush.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.typerush.persistence.Player;
import com.typerush.security.AuthService;
import com.typerush.security.GamePrincipal;
import com.typerush.security.JwtService;
import com.typerush.security.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration, login, refresh and logout.
 *
 * <p>Only the long-lived refresh token is written to storage. The access token is returned to the
 * caller to hold in memory only, so an XSS cannot lift a usable credential that outlives the page.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final JwtService jwt;
    private final RateLimiter limiter;

    public AuthController(AuthService auth, JwtService jwt, RateLimiter limiter) {
        this.auth = auth;
        this.jwt = jwt;
        this.limiter = limiter;
    }

    /**
     * {@code password} deliberately has no upper bound here. Capping it at the DTO would produce a
     * generic "too long" message from the validation layer; the auth service reports the real reason,
     * namely that bcrypt only hashes the first 72 bytes.
     */
    public record RegisterRequest(@NotBlank @Size(max = 64) String username, @NotBlank String password) {
    }

    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record LogoutRequest(String refreshToken) {
    }

    public record SessionResponse(String username, String nickname, String role, String accessToken,
            String refreshToken, long expiresIn) {

        static SessionResponse of(AuthService.Session session) {
            Player p = session.player();
            return new SessionResponse(p.getUsername(), p.getNickname(), p.getRole(),
                    session.accessToken(), session.refreshToken(), session.expiresIn());
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        if (!limiter.allowRegistration(clientIp(http))) {
            throw new AuthService.AuthException("rate_limited",
                    "Too many accounts created from here. Try again later.");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SessionResponse.of(auth.register(request.username(), request.password())));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        // AuthException carries its own code and maps to the right status in ApiExceptionHandler.
        return ResponseEntity.ok(SessionResponse.of(
                auth.login(request.username(), request.password(), clientIp(http))));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(SessionResponse.of(auth.refresh(request.refreshToken(), clientIp(http))));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody(required = false) LogoutRequest request,
            @AuthenticationPrincipal GamePrincipal principal) {
        if (request != null) {
            auth.logout(request.refreshToken());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Render puts a proxy in front of the service, so the socket address is the only one present and
     * rate limiting keyed on it would treat every visitor as one client.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @SuppressWarnings("unused")
    private long accessTtlSeconds() {
        return jwt.accessTokenTtlSeconds();
    }
}
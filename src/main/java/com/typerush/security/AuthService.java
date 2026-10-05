package com.typerush.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.typerush.config.GameProperties;
import com.typerush.persistence.Player;
import com.typerush.persistence.PlayerRepository;
import com.typerush.persistence.RefreshToken;
import com.typerush.persistence.RefreshTokenRepository;

/**
 * Registration, login and refresh-token rotation.
 *
 * <p>Login failures are deliberately indistinguishable to the caller (bad password and unknown
 * account both report the same message) so the endpoint cannot be used to enumerate accounts; the
 * per-IP and per-username limiters are what actually blunt guessing.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Lowercase letters, digits, dot, dash, underscore. No spaces, so the login name stays URL-safe. */
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,19}$");

    /**
     * bcrypt only considers the first 72 bytes, so anything longer is silently truncated. Capping the
     * length keeps a long password from appearing stronger than it is.
     */
    private static final int PASSWORD_MIN = 8;
    private static final int PASSWORD_MAX = 72;

    private final PlayerRepository players;
    private final RefreshTokenRepository tokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final GameProperties.Auth auth;
    private final RateLimiter limiter;
    private final RefreshTokenRevoker revoker;

    public AuthService(PlayerRepository players, RefreshTokenRepository tokens, PasswordEncoder encoder,
            JwtService jwt, GameProperties properties, RateLimiter limiter, RefreshTokenRevoker revoker) {
        this.players = players;
        this.tokens = tokens;
        this.encoder = encoder;
        this.jwt = jwt;
        this.auth = properties.getAuth();
        this.limiter = limiter;
        this.revoker = revoker;
    }

    public record Session(Player player, String accessToken, String refreshToken, long expiresIn) {
    }

    public static class AuthException extends RuntimeException {
        private final String code;

        public AuthException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    @Transactional
    public Session register(String rawUsername, String rawPassword) {
        String username = normaliseUsername(rawUsername);
        validatePassword(rawPassword);

        String usernameKey = username.toLowerCase(Locale.ROOT);
        if (players.existsByUsernameKey(usernameKey)) {
            throw new AuthException("username_taken", "That username is already taken.");
        }
        // The display nickname inherits the username and is unique under the same rules, so it cannot
        // collide with an existing player's nickname.
        if (players.existsByNicknameKey(username.toUpperCase(Locale.ROOT))) {
            throw new AuthException("nickname_taken", "That name is already in use as a racer name.");
        }

        Player player = new Player();
        player.setUsername(username);
        player.setNickname(username);
        player.setPasswordHash(encoder.encode(rawPassword));
        player.setRole("player");
        player.setLastLoginAt(Instant.now());
        players.saveAndFlush(player);

        log.info("registered player {} (id={})", player.getUsername(), player.getId());
        return startSession(player);
    }

    @Transactional
    public Session login(String rawUsername, String rawPassword, String ip) {
        String usernameKey = normaliseUsername(rawUsername).toLowerCase(Locale.ROOT);
        if (!limiter.allowLoginAttempt(ip, usernameKey)) {
            throw new AuthException("rate_limited", "Too many failed attempts. Try again in a few minutes.");
        }

        Optional<Player> found = players.findByUsernameKey(usernameKey);
        if (found.isEmpty() || !found.get().isCredentialed()) {
            // Spend comparable time so a missing account is not detectable by response latency.
            encoder.encode("no-such-account-placeholder");
            limiter.recordLoginFailure(ip, usernameKey);
            throw new AuthException("bad_credentials", "Wrong username or password.");
        }

        Player player = found.get();
        if (!encoder.matches(rawPassword, player.getPasswordHash())) {
            limiter.recordLoginFailure(ip, usernameKey);
            throw new AuthException("bad_credentials", "Wrong username or password.");
        }

        player.setLastLoginAt(Instant.now());
        players.save(player);
        return startSession(player);
    }

    /**
     * Rotates a refresh token.
     *
     * <p>Presenting a token that was already rotated means it leaked, so the entire family is revoked
     * rather than merely the one token: the legitimate user gets logged out instead of quietly sharing
     * a session with whoever copied the token.
     */
    @Transactional
    public Session refresh(String rawRefreshToken, String ip) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new AuthException("bad_refresh", "Refresh token missing.");
        }
        String hash = jwt.hashRefreshToken(rawRefreshToken);
        Optional<RefreshToken> stored = tokens.findByTokenHash(hash);
        if (stored.isEmpty()) {
            throw new AuthException("bad_refresh", "Refresh token is not recognised.");
        }

        RefreshToken token = stored.get();
        if (!token.isUsableAt(Instant.now())) {
            if (token.getRevokedAt() != null) {
                int revoked = revoker.revokeFamily(token.getFamilyId());
                log.warn("reuse of a rotated refresh token detected; revoked {} token(s) in family {}",
                        revoked, token.getFamilyId());
            }
            throw new AuthException("bad_refresh", "Session expired. Please sign in again.");
        }

        Player player = players.findById(token.getPlayerId()).orElse(null);
        if (player == null || !player.isCredentialed()) {
            throw new AuthException("bad_refresh", "Session expired. Please sign in again.");
        }

        token.revoke();
        return startSession(player, token.getFamilyId(), ip);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        revoker.revokeByHash(jwt.hashRefreshToken(rawRefreshToken));
    }

    private Session startSession(Player player) {
        return startSession(player, jwt.newFamilyId(), null);
    }

    private Session startSession(Player player, String familyId, String ip) {
        String refresh = jwt.newRefreshToken();
        Instant expiry = Instant.now().plus(auth.getRefreshTtlDays(), ChronoUnit.DAYS);
        tokens.save(new RefreshToken(player.getId(), jwt.hashRefreshToken(refresh), familyId, expiry, ip));
        return new Session(player, jwt.issueAccessToken(player), refresh, jwt.accessTokenTtlSeconds());
    }

    static String normaliseUsername(String raw) {
        if (raw == null) {
            throw new AuthException("bad_username", "Choose a username.");
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(trimmed).matches()) {
            throw new AuthException("bad_username",
                    "Username must be 3-20 characters: lowercase letters, digits, dot, dash or underscore.");
        }
        return trimmed;
    }

    private void validatePassword(String raw) {
        if (raw == null || raw.length() < PASSWORD_MIN) {
            throw new AuthException("weak_password", "Password must be at least 8 characters.");
        }
        if (raw.length() > PASSWORD_MAX) {
            throw new AuthException("weak_password", "Password must be at most 72 characters.");
        }
    }
}
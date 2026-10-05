package com.typerush.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.typerush.config.GameProperties;
import com.typerush.persistence.Player;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and verifies short-lived access tokens, and mints the opaque refresh tokens that outlive
 * them.
 *
 * <p>Refresh tokens are random bytes; only their SHA-256 hash is ever persisted. Rotating them on
 * every use lets us detect replay: if a token that was already rotated comes back, it leaked, and the
 * entire family is revoked.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final GameProperties.Auth auth;
    private final SecretKey key;
    private final boolean usingDefaultSecret;

    public JwtService(GameProperties properties) {
        this.auth = properties.getAuth();
        this.usingDefaultSecret = this.auth.getJwtSecret()
                .startsWith("dev-only-insecure-secret");
        byte[] secret = this.auth.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "typerush.auth.jwt-secret must be at least 32 bytes for HS256; got " + secret.length);
        }
        this.key = Keys.hmacShaKeyFor(secret);
        if (usingDefaultSecret) {
            log.warn("Using the built-in development JWT secret. Set AUTH_JWT_SECRET before deploying.");
        }
    }

    public String issueAccessToken(Player player) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(player.getId()))
                .claim("username", player.getUsername())
                .claim("nickname", player.getNickname())
                .claim("role", player.getRole())
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(auth.getAccessTtlSeconds())))
                .id(UUID.randomUUID().toString())
                .signWith(key)
                .compact();
    }

    /** Returns the verified claims, or empty for anything tampered with, expired or malformed. */
    public Optional<Claims> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!"access".equals(claims.get("typ", String.class))) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long accessTokenTtlSeconds() {
        return auth.getAccessTtlSeconds();
    }

    public String newRefreshToken() {
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public String newFamilyId() {
        return UUID.randomUUID().toString();
    }

    /** Refresh tokens are looked up by hash, so the plaintext exists only on the wire. */
    public String hashRefreshToken(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
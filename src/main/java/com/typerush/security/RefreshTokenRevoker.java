package com.typerush.security;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.typerush.persistence.RefreshToken;
import com.typerush.persistence.RefreshTokenRepository;

/**
 * Commits token revocations that must survive a failed request.
 *
 * <p>Revoking a stolen token family means throwing an error to the caller. In {@link AuthService} that
 * error rolls the surrounding transaction back, which would undo the revocation and leave the stolen
 * chain usable. Separating the work into its own {@code REQUIRES_NEW} transaction makes the write
 * durable independently of whatever the request does next. It also cannot be a method on
 * {@code AuthService}: calling it from inside would bypass the proxy and never open a new transaction.
 */
@Component
public class RefreshTokenRevoker {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenRevoker.class);

    private final RefreshTokenRepository tokens;

    public RefreshTokenRevoker(RefreshTokenRepository tokens) {
        this.tokens = tokens;
    }

    /** Revokes every live token in one login chain and returns how many were revoked. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(String familyId) {
        List<RefreshToken> family = tokens.findByFamilyIdAndRevokedAtIsNull(familyId);
        family.forEach(RefreshToken::revoke);
        return family.size();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeByHash(String tokenHash) {
        tokens.findByTokenHash(tokenHash).ifPresent(RefreshToken::revoke);
    }

    /**
     * Drop refresh tokens that are past their expiry.
     *
     * <p>Scheduled because nothing in the request path deletes them: rotation marks the old row
     * revoked but leaves it for forensics, so without this the table only ever grows.
     */
    @Scheduled(cron = "${typerush.auth.purge-cron:0 17 * * * *}")
    @Transactional
    public int purgeExpired() {
        int before = (int) tokens.count();
        tokens.deleteByExpiresAtBefore(java.time.Instant.now());
        log.debug("purged {} expired refresh token(s)", before - (int) tokens.count());
        return before - (int) tokens.count();
    }
}
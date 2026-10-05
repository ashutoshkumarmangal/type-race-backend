package com.typerush.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.typerush.config.GameProperties;

/**
     * Fixed-window counters for abuse control.
     *
     * <p>In-memory and therefore per-instance: correct for the single free Render instance this runs on,
     * but it would need shared storage before scaling out. Counters are swept opportunistically so the
     * map cannot grow without bound from unique keys.
     *
     * <p>Login counting is failure-only. A user who signs in correctly twenty times must not be locked
     * out by their own successful logins, so the check and the increment are separate calls.
     */
    @Component
    public class RateLimiter {

    private record Key(String bucket, String id, long window) {
    }

    private final GameProperties.Auth auth;
    private final Map<Key, Integer> counts = new ConcurrentHashMap<>();
    private volatile long lastSweep;

    public RateLimiter(GameProperties properties) {
        this.auth = properties.getAuth();
    }

    /** Returns true when the action is allowed, counting this occurrence against the limit. */
    public boolean tryAcquire(String bucket, String id, int limit, long windowSeconds) {
        long now = System.currentTimeMillis() / 1000;
        long window = now / windowSeconds;
        sweep(now);
        return counts.merge(new Key(bucket, id, window), 1, Integer::sum) <= limit;
    }

    /** Reads the current usage without recording a new attempt. */
    public boolean withinLimit(String bucket, String id, int limit, long windowSeconds) {
        long now = System.currentTimeMillis() / 1000;
        long window = now / windowSeconds;
        Integer used = counts.get(new Key(bucket, id, window));
        return used == null || used < limit;
    }

    private boolean overLoginLimit(String bucket, String id, int limit) {
        return !withinLimit(bucket, id, limit, auth.getLimitCooldownSeconds());
    }

    /** Cheap pre-check: is this client currently allowed to attempt a login? */
    public boolean allowLoginAttempt(String ip, String usernameKey) {
        return !overLoginLimit("login-ip", nullSafe(ip), auth.getLoginAttemptsPerIp())
                && !overLoginLimit("login-user", nullSafe(usernameKey), auth.getLoginAttemptsPerUser());
    }

    /**
     * Counts a failed attempt against both the client address and the account name, so a distributed
     * guess against one account is throttled and a single client spraying many accounts is too.
     */
    public void recordLoginFailure(String ip, String usernameKey) {
        tryAcquire("login-ip", nullSafe(ip), auth.getLoginAttemptsPerIp(), auth.getLimitCooldownSeconds());
        tryAcquire("login-user", nullSafe(usernameKey), auth.getLoginAttemptsPerUser(),
                auth.getLimitCooldownSeconds());
    }

    public boolean allowRegistration(String ip) {
        return tryAcquire("register-ip", nullSafe(ip), auth.getRegistrationsPerIp(),
                auth.getLimitCooldownSeconds());
    }

    public boolean allowRoomCreation(long playerId) {
        return tryAcquire("rooms", Long.toString(playerId), auth.getRoomsPerHour(), 3600);
    }

    private String nullSafe(String value) {
        return value == null ? "unknown" : value;
    }

    private void sweep(long now) {
        if (now - lastSweep < 60) {
            return;
        }
        lastSweep = now;
        long stale = now / auth.getLimitCooldownSeconds() - 1;
        counts.keySet().removeIf(key -> key.window() < stale);
    }
}
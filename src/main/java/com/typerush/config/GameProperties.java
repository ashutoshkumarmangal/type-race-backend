package com.typerush.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "typerush")
public class GameProperties {

    private Game game = new Game();
    private Cors cors = new Cors();
    private Auth auth = new Auth();

    public Game getGame() {
        return game;
    }

    public void setGame(Game game) {
        this.game = game;
    }

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public static class Game {
        private int minPlayers = 2;
        private int maxPlayers = 8;
        private int textLength = 220;
        private long countdownMs = 4000;
        private long tickMs = 100;
        private long timeLimitMs = 180_000;
        private long finishGraceMs = 8_000;
        private int maxWpm = 260;
        private QuickMatch quickMatch = new QuickMatch();

        public int getMinPlayers() {
            return minPlayers;
        }

        public void setMinPlayers(int minPlayers) {
            this.minPlayers = minPlayers;
        }

        public int getMaxPlayers() {
            return maxPlayers;
        }

        public void setMaxPlayers(int maxPlayers) {
            this.maxPlayers = maxPlayers;
        }

        public int getTextLength() {
            return textLength;
        }

        public void setTextLength(int textLength) {
            this.textLength = textLength;
        }

        public long getCountdownMs() {
            return countdownMs;
        }

        public void setCountdownMs(long countdownMs) {
            this.countdownMs = countdownMs;
        }

        public long getTickMs() {
            return tickMs;
        }

        public void setTickMs(long tickMs) {
            this.tickMs = tickMs;
        }

        public long getTimeLimitMs() {
            return timeLimitMs;
        }

        public void setTimeLimitMs(long timeLimitMs) {
            this.timeLimitMs = timeLimitMs;
        }

        public long getFinishGraceMs() {
            return finishGraceMs;
        }

        public void setFinishGraceMs(long finishGraceMs) {
            this.finishGraceMs = finishGraceMs;
        }

        public int getMaxWpm() {
            return maxWpm;
        }

        public void setMaxWpm(int maxWpm) {
            this.maxWpm = maxWpm;
        }

        public QuickMatch getQuickMatch() {
            return quickMatch;
        }

        public void setQuickMatch(QuickMatch quickMatch) {
            this.quickMatch = quickMatch;
        }
    }

    public static class QuickMatch {
        private boolean enabled = true;
        private int pairSize = 2;
        private long rematchDelayMs = 4000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPairSize() {
            return pairSize;
        }

        public void setPairSize(int pairSize) {
            this.pairSize = pairSize;
        }

        public long getRematchDelayMs() {
            return rematchDelayMs;
        }

        public void setRematchDelayMs(long rematchDelayMs) {
            this.rematchDelayMs = rematchDelayMs;
        }
    }

    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:5173");

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class Auth {
        /** HMAC secret for access tokens. Must be overridden in production. */
        private String jwtSecret = "dev-only-insecure-secret-change-me-before-deploying-anywhere";
        /** Access token lifetime in seconds. Kept short; the socket lives longer than this. */
        private long accessTtlSeconds = 900;
        /** Refresh token lifetime in days. */
        private long refreshTtlDays = 30;
        private int bcryptCost = 12;
        /** Failed logins per IP before a cool-off. */
        private int loginAttemptsPerIp = 20;
        /** Failed logins per username before that account is locked out. */
        private int loginAttemptsPerUser = 8;
        /** Registration attempts allowed per IP within the window. */
        private int registrationsPerIp = 5;
        /** Simultaneous sockets one account may hold open. */
        private int socketsPerAccount = 4;
        /** Rooms one account may create within the window. */
        private int roomsPerHour = 30;
        /** Cool-off applied once a limiter trips, in seconds. */
        private long limitCooldownSeconds = 900;

        public String getJwtSecret() {
            return jwtSecret;
        }

        public void setJwtSecret(String jwtSecret) {
            this.jwtSecret = jwtSecret;
        }

        public long getAccessTtlSeconds() {
            return accessTtlSeconds;
        }

        public void setAccessTtlSeconds(long accessTtlSeconds) {
            this.accessTtlSeconds = accessTtlSeconds;
        }

        public long getRefreshTtlDays() {
            return refreshTtlDays;
        }

        public void setRefreshTtlDays(long refreshTtlDays) {
            this.refreshTtlDays = refreshTtlDays;
        }

        public int getBcryptCost() {
            return bcryptCost;
        }

        public void setBcryptCost(int bcryptCost) {
            this.bcryptCost = bcryptCost;
        }

        public int getLoginAttemptsPerIp() {
            return loginAttemptsPerIp;
        }

        public void setLoginAttemptsPerIp(int loginAttemptsPerIp) {
            this.loginAttemptsPerIp = loginAttemptsPerIp;
        }

        public int getLoginAttemptsPerUser() {
            return loginAttemptsPerUser;
        }

        public void setLoginAttemptsPerUser(int loginAttemptsPerUser) {
            this.loginAttemptsPerUser = loginAttemptsPerUser;
        }

        public int getRegistrationsPerIp() {
            return registrationsPerIp;
        }

        public void setRegistrationsPerIp(int registrationsPerIp) {
            this.registrationsPerIp = registrationsPerIp;
        }

        public int getSocketsPerAccount() {
            return socketsPerAccount;
        }

        public void setSocketsPerAccount(int socketsPerAccount) {
            this.socketsPerAccount = socketsPerAccount;
        }

        public int getRoomsPerHour() {
            return roomsPerHour;
        }

        public void setRoomsPerHour(int roomsPerHour) {
            this.roomsPerHour = roomsPerHour;
        }

        public long getLimitCooldownSeconds() {
            return limitCooldownSeconds;
        }

        public void setLimitCooldownSeconds(long limitCooldownSeconds) {
            this.limitCooldownSeconds = limitCooldownSeconds;
        }
    }
}
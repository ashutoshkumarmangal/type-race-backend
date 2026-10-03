package com.typerush.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "typerush")
public class GameProperties {

    private Game game = new Game();
    private Cors cors = new Cors();

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
}
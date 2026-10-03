package com.typerush.protocol.server;

import java.util.List;

public final class Payloads {

    private Payloads() {
    }

    public record Welcome(String playerId, String nickname, String avatarColor, long serverTimeEpochMs) {
    }

    public record Queued(int queued, int needed, long waitMs) {
    }

    public record Joined(String roomCode, boolean host, List<PlayerView> players, String phase, int minPlayers,
            int maxPlayers, boolean quickMatch, long serverTimeEpochMs) {
    }

    public record PlayerView(String id, String nickname, String avatarColor, boolean ready, boolean connected,
            boolean finished, boolean host, boolean spectator, int place) {
    }

    public record RoomClosed(String reason) {
    }

    public record Countdown(String roomCode, long startsAtEpochMs, int seconds, int secondsRemaining) {
    }

    public record RaceStart(String roomCode, String text, int totalChars, long startAtEpochMs, long timeLimitMs,
            String raceId) {
    }

    public record RaceState(String roomCode, String phase, long serverTimeEpochMs, long elapsedMs, int totalChars,
            List<ProgressView> players) {
    }

    public record ProgressView(String id, String nickname, String avatarColor, int correctChars, int errors,
            double progress, double wpm, double accuracy, boolean finished, int place, boolean you) {
    }

    public record PlayerFinished(String id, String nickname, int place, double wpm, double accuracy,
            long durationMs) {
    }

    public record Standing(String id, String nickname, String avatarColor, int place, boolean dnf, double wpm,
            double accuracy, int correctChars, int errors, long durationMs, boolean bestInRoom, boolean flagged) {
    }

    public record RaceOver(String raceId, String roomCode, long endedAtEpochMs, List<Standing> standings) {
    }

    public record Error(String code, String message) {
    }
}
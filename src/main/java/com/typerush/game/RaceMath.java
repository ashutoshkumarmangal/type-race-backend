package com.typerush.game;

/**
 * Server-side scoring. The client never gets to decide its own WPM: it reports progress and
 * the server derives speed and accuracy from its own clock.
 */
public final class RaceMath {

    private RaceMath() {
    }

    /** Standard 5-characters-per-word convention. */
    public static double wpm(int correctChars, long durationMs) {
        if (durationMs <= 0) {
            return 0;
        }
        double words = correctChars / 5.0;
        double minutes = durationMs / 60_000.0;
        return round1(words / minutes);
    }

    /** Live WPM estimate used for the progress HUD. */
    public static double liveWpm(int correctChars, long elapsedSinceFirstKeystrokeMs) {
        return wpm(correctChars, elapsedSinceFirstKeystrokeMs);
    }

    public static double accuracy(int correctChars, int errors) {
        int total = correctChars + errors;
        if (total <= 0) {
            return 100.0;
        }
        return round1((correctChars * 100.0) / total);
    }

    /**
     * Maximum correct characters a client may legitimately claim to have typed since its last
     * credited progress update, given a hard WPM ceiling plus a small burst allowance for
     * network batching.
     */
    public static int charBudget(long elapsedMs, int maxWpm) {
        if (elapsedMs <= 0) {
            return 4;
        }
        double charsPerSecond = (maxWpm * 5.0) / 60.0;
        return (int) Math.ceil((charsPerSecond * elapsedMs) / 1000.0) + 4;
    }

    public static double clampWpm(double wpm, int maxWpm) {
        return Math.min(wpm, maxWpm);
    }

    public static double progress(int correctChars, int totalChars) {
        if (totalChars <= 0) {
            return 0;
        }
        return Math.min(1.0, correctChars / (double) totalChars);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
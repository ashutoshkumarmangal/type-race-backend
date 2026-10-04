package com.typerush.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** One row per player per race. */
@Entity
@Table(name = "race_results", indexes = {
        @Index(name = "idx_results_player", columnList = "player_id"),
        @Index(name = "idx_results_race", columnList = "race_id"),
        @Index(name = "idx_results_wpm", columnList = "wpm")
})
public class RaceResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "race_id", nullable = false, length = 12)
    private String raceId;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "nickname", nullable = false, length = 40)
    private String nickname;

    @Column(nullable = false)
    private int place;

    @Column(name = "field_size", nullable = false)
    private int fieldSize;

    @Column(nullable = false)
    private double wpm;

    @Column(name = "raw_wpm", nullable = false)
    private double rawWpm;

    @Column(nullable = false)
    private double accuracy;

    @Column(name = "correct_chars", nullable = false)
    private int correctChars;

    @Column(name = "error_chars", nullable = false)
    private int errorChars;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(nullable = false)
    private boolean finished;

    /** True when the anti-cheat validator had to clamp an implausible run. */
    @Column(nullable = false)
    private boolean flagged;

    @Column(name = "text_id")
    private Long textId;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime")
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public static RaceResult of(String raceId, Player player, int place, int fieldSize, double wpm, double rawWpm,
            double accuracy, int correctChars, int errorChars, long durationMs, boolean finished, boolean flagged,
            Long textId) {
        RaceResult r = new RaceResult();
        r.raceId = raceId;
        r.playerId = player.getId();
        r.nickname = player.getNickname();
        r.place = place;
        r.fieldSize = fieldSize;
        r.wpm = wpm;
        r.rawWpm = rawWpm;
        r.accuracy = accuracy;
        r.correctChars = correctChars;
        r.errorChars = errorChars;
        r.durationMs = durationMs;
        r.finished = finished;
        r.flagged = flagged;
        r.textId = textId;
        return r;
    }

    public Long getId() {
        return id;
    }

    public String getRaceId() {
        return raceId;
    }

    public Long getPlayerId() {
        return playerId;
    }

    public String getNickname() {
        return nickname;
    }

    public int getPlace() {
        return place;
    }

    public int getFieldSize() {
        return fieldSize;
    }

    public double getWpm() {
        return wpm;
    }

    public double getRawWpm() {
        return rawWpm;
    }

    public double getAccuracy() {
        return accuracy;
    }

    public int getCorrectChars() {
        return correctChars;
    }

    public int getErrorChars() {
        return errorChars;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean isFlagged() {
        return flagged;
    }

    public Long getTextId() {
        return textId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
package com.typerush.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "players", indexes = {
        @Index(name = "idx_players_best_wpm", columnList = "best_wpm"),
        @Index(name = "idx_players_races", columnList = "races_played")
})
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String nickname;

    /** Upper-cased nickname, unique, so "Ash" and "ash" are the same player. */
    @Column(name = "nickname_key", nullable = false, unique = true, length = 40)
    private String nicknameKey;

    /** Login identity. Immutable after registration; never shown where the nickname is shown. */
    @Column(length = 32)
    private String username;

    /** Upper-cased username, unique, so login is case-insensitive. */
    @Column(name = "username_key", unique = true, length = 32)
    private String usernameKey;

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 16)
    private String role = "player";

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "races_played", nullable = false)
    private int racesPlayed;

    @Column(nullable = false)
    private int wins;

    @Column(name = "podiums", nullable = false)
    private int podiums;

    @Column(name = "best_wpm", nullable = false)
    private double bestWpm;

    @Column(name = "best_accuracy", nullable = false)
    private double bestAccuracy;

    @Column(name = "avg_wpm", nullable = false)
    private double avgWpm;

    @Column(name = "total_chars_typed", nullable = false)
    private long totalCharsTyped;

    @Column(name = "total_typing_ms", nullable = false)
    private long totalTypingMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        lastSeenAt = now;
        if (nicknameKey == null && nickname != null) {
            nicknameKey = nickname.toUpperCase();
        }
    }

    @PreUpdate
    void onUpdate() {
        lastSeenAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
        this.nicknameKey = nickname == null ? null : nickname.toUpperCase();
    }

    public String getNicknameKey() {
        return nicknameKey;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
        this.usernameKey = username == null ? null : username.toLowerCase();
    }

    public String getUsernameKey() {
        return usernameKey;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    /** True for rows created before accounts existed, which can never authenticate. */
    public boolean isCredentialed() {
        return usernameKey != null && passwordHash != null;
    }

    public int getRacesPlayed() {
        return racesPlayed;
    }

    public int getWins() {
        return wins;
    }

    public int getPodiums() {
        return podiums;
    }

    public double getBestWpm() {
        return bestWpm;
    }

    public double getBestAccuracy() {
        return bestAccuracy;
    }

    public double getAvgWpm() {
        return avgWpm;
    }

    public long getTotalCharsTyped() {
        return totalCharsTyped;
    }

    public long getTotalTypingMs() {
        return totalTypingMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    /** Folds a finished race into the rolling career aggregates. */
    public void recordRace(double wpm, double accuracy, int correctChars, long durationMs, int place, int fieldSize) {
        racesPlayed++;
        if (place == 1) {
            wins++;
        }
        if (place >= 1 && place <= 3) {
            podiums++;
        }
        if (wpm > bestWpm) {
            bestWpm = wpm;
        }
        if (accuracy > bestAccuracy) {
            bestAccuracy = accuracy;
        }
        totalCharsTyped += correctChars;
        totalTypingMs += Math.max(durationMs, 0);
        avgWpm = totalTypingMs <= 0 ? wpm : (totalCharsTyped / 5.0) / (totalTypingMs / 60_000.0);
    }
}
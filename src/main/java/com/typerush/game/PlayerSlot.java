package com.typerush.game;

import java.util.UUID;

import org.springframework.web.socket.WebSocketSession;

/** Server-side per-player state inside a room. Only the server mutates this. */
public class PlayerSlot {

    private static final String[] PALETTE = {
            "#f97316", "#22d3ee", "#a78bfa", "#4ade80", "#f472b6", "#facc15", "#60a5fa", "#fb7185"
    };

    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final WebSocketSession session;
    private String nickname;
    private String avatarColor;
    private boolean host;
    private boolean connected = true;
    private boolean ready;
    private boolean spectator;
    private boolean finished;
    private int place;
    private int correctChars;
    private int errors;
    private int keystrokes;
    private long firstKeystrokeAtMs;
    private long lastCharCreditAtMs;
    private long finishAtMs;
    private boolean flagged;
    private boolean bestInRoom;

    public PlayerSlot(WebSocketSession session, String nickname, int colorIndex) {
        this.session = session;
        this.nickname = nickname;
        this.avatarColor = PALETTE[Math.floorMod(colorIndex, PALETTE.length)];
    }

    public void resetForNewRace() {
        this.ready = false;
        this.spectator = false;
        this.finished = false;
        this.place = 0;
        this.correctChars = 0;
        this.errors = 0;
        this.keystrokes = 0;
        this.firstKeystrokeAtMs = 0;
        this.lastCharCreditAtMs = 0;
        this.finishAtMs = 0;
        this.flagged = false;
        this.bestInRoom = false;
    }

    public String getId() {
        return id;
    }

    public WebSocketSession getSession() {
        return session;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatarColor() {
        return avatarColor;
    }

    public boolean isHost() {
        return host;
    }

    public void setHost(boolean host) {
        this.host = host;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public boolean isSpectator() {
        return spectator;
    }

    public void setSpectator(boolean spectator) {
        this.spectator = spectator;
    }

    public boolean isFinished() {
        return finished;
    }

    public void setFinished(boolean finished) {
        this.finished = finished;
    }

    public int getPlace() {
        return place;
    }

    public void setPlace(int place) {
        this.place = place;
    }

    public int getCorrectChars() {
        return correctChars;
    }

    public void setCorrectChars(int correctChars) {
        this.correctChars = correctChars;
    }

    public int getErrors() {
        return errors;
    }

    public void setErrors(int errors) {
        this.errors = errors;
    }

    public int getKeystrokes() {
        return keystrokes;
    }

    public void setKeystrokes(int keystrokes) {
        this.keystrokes = keystrokes;
    }

    public long getFirstKeystrokeAtMs() {
        return firstKeystrokeAtMs;
    }

    public void setFirstKeystrokeAtMs(long firstKeystrokeAtMs) {
        this.firstKeystrokeAtMs = firstKeystrokeAtMs;
    }

    public long getLastCharCreditAtMs() {
        return lastCharCreditAtMs;
    }

    public void setLastCharCreditAtMs(long lastCharCreditAtMs) {
        this.lastCharCreditAtMs = lastCharCreditAtMs;
    }

    public long getFinishAtMs() {
        return finishAtMs;
    }

    public void setFinishAtMs(long finishAtMs) {
        this.finishAtMs = finishAtMs;
    }

    public boolean isFlagged() {
        return flagged;
    }

    public void setFlagged(boolean flagged) {
        this.flagged = flagged;
    }

    public boolean isBestInRoom() {
        return bestInRoom;
    }

    public void setBestInRoom(boolean bestInRoom) {
        this.bestInRoom = bestInRoom;
    }
}
package com.typerush.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.typerush.persistence.TextSnippet;

/**
 * Mutable room state. All mutations happen while holding {@link #lock()}, which the
 * {@link RoomManager} acquires before touching a room. Readers of live snapshots may read
 * primitives/lists without the lock only because the broadcaster iterates a snapshot copy.
 */
public class GameRoom {

    private final String code;
    private final boolean quickMatch;
    private final Object lock = new Object();
    private final Map<String, PlayerSlot> slots = new LinkedHashMap<>();

    private Phase phase = Phase.LOBBY;
    private String hostId;
    private TextSnippet snippet;
    private String textContent = "";
    private int totalChars;
    private int raceSeq;
    private String raceId;
    private long startAtEpochMs;
    private long countdownEndsAtMs;
    private long timeLimitMs;
    private long firstFinishAtMs;
    private boolean raceClosed;
    private final Map<String, Boolean> rematchVotes = new LinkedHashMap<>();
    private long createdAtMs = System.currentTimeMillis();
    private long lastActivityMs = System.currentTimeMillis();

    public GameRoom(String code, boolean quickMatch) {
        this.code = code;
        this.quickMatch = quickMatch;
    }

    public Object lock() {
        return lock;
    }

    public String getCode() {
        return code;
    }

    public boolean isQuickMatch() {
        return quickMatch;
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }

    public String getHostId() {
        return hostId;
    }

    public void setHostId(String hostId) {
        this.hostId = hostId;
    }

    public TextSnippet getSnippet() {
        return snippet;
    }

    public void setSnippet(TextSnippet snippet) {
        this.snippet = snippet;
        this.textContent = snippet == null ? "" : snippet.getContent();
        this.totalChars = textContent.length();
    }

    public String getTextContent() {
        return textContent;
    }

    public int getTotalChars() {
        return totalChars;
    }

    public int nextRaceSeq() {
        return ++raceSeq;
    }

    public int getRaceSeq() {
        return raceSeq;
    }

    public String getRaceId() {
        return raceId;
    }

    public void setRaceId(String raceId) {
        this.raceId = raceId;
    }

    public long getStartAtEpochMs() {
        return startAtEpochMs;
    }

    public void setStartAtEpochMs(long startAtEpochMs) {
        this.startAtEpochMs = startAtEpochMs;
    }

    public long getCountdownEndsAtMs() {
        return countdownEndsAtMs;
    }

    public void setCountdownEndsAtMs(long countdownEndsAtMs) {
        this.countdownEndsAtMs = countdownEndsAtMs;
    }

    public long getTimeLimitMs() {
        return timeLimitMs;
    }

    public void setTimeLimitMs(long timeLimitMs) {
        this.timeLimitMs = timeLimitMs;
    }

    public long getFirstFinishAtMs() {
        return firstFinishAtMs;
    }

    public void setFirstFinishAtMs(long firstFinishAtMs) {
        this.firstFinishAtMs = firstFinishAtMs;
    }

    public boolean isRaceClosed() {
        return raceClosed;
    }

    public void setRaceClosed(boolean raceClosed) {
        this.raceClosed = raceClosed;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public long getLastActivityMs() {
        return lastActivityMs;
    }

    public void touch() {
        lastActivityMs = System.currentTimeMillis();
    }

    public void put(PlayerSlot slot) {
        slots.put(slot.getId(), slot);
    }

    public void remove(String playerId) {
        slots.remove(playerId);
    }

    public PlayerSlot get(String playerId) {
        return slots.get(playerId);
    }

    public boolean contains(String playerId) {
        return slots.containsKey(playerId);
    }

    public List<PlayerSlot> players() {
        return new ArrayList<>(slots.values());
    }

    public List<String> playerIds() {
        return new ArrayList<>(slots.keySet());
    }

    public int size() {
        return slots.size();
    }

    public Optional<PlayerSlot> host() {
        return Optional.ofNullable(slots.get(hostId));
    }

    public int connectedCount() {
        return (int) slots.values().stream().filter(PlayerSlot::isConnected).count();
    }

    public int racerCount() {
        return (int) slots.values().stream()
                .filter(p -> p.isConnected() && !p.isSpectator())
                .count();
    }

    public boolean allRacersConnected() {
        return slots.values().stream().allMatch(p -> p.isConnected());
    }

    public void voteRematch(String playerId, boolean again) {
        if (again) {
            rematchVotes.put(playerId, Boolean.TRUE);
        } else {
            rematchVotes.remove(playerId);
        }
    }

    public int rematchYes() {
        return (int) rematchVotes.values().stream().filter(Boolean::booleanValue).count();
    }

    public void clearRematchVotes() {
        rematchVotes.clear();
    }

    public boolean everyoneVotedRematch(int eligible) {
        return eligible > 0 && rematchYes() >= eligible;
    }

    /** Picks a new host when the current one leaves; keeps the room playable. */
    public String reassignHost(String currentHostId) {
        if (currentHostId != null && currentHostId.equals(hostId)) {
            hostId = slots.keySet().stream().findFirst().orElse(null);
        }
        if (hostId != null) {
            slots.get(hostId).setHost(true);
        } else {
            slots.values().forEach(p -> p.setHost(false));
        }
        return hostId;
    }
}
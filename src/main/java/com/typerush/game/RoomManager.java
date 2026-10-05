package com.typerush.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import com.typerush.config.GameProperties;
import com.typerush.persistence.TextSnippet;
import com.typerush.protocol.ClientCommand;
import com.typerush.protocol.server.Payloads;
import com.typerush.security.GamePrincipal;

/**
 * The whole game: room lifecycle, matchmaking, race clocking and validation.
 *
 * <p>The server is authoritative. A client only ever claims "I have typed N correct characters with E errors";
 * speed, accuracy, ranking and the final result are all derived here from a clock the client cannot
 * influence, and implausible progress claims are clamped by {@link RaceMath#charBudget}.
 */
@Component
public class RoomManager {

    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long FINISHED_ROOM_TTL_MS = 60_000;
    private static final long IDLE_LOBBY_TTL_MS = 10 * 60_000L;

    private final GameProperties properties;
    private final MessageSender sender;
    private final TextService textService;
    private final ResultPersistenceService persistence;

    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final Map<String, PlayerSlot> playersBySession = new ConcurrentHashMap<>();
    private final Deque<String> matchQueue = new ArrayDeque<>();
    private final Map<String, Long> queuedAt = new HashMap<>();

    public RoomManager(GameProperties properties, MessageSender sender, TextService textService,
            ResultPersistenceService persistence) {
        this.properties = properties;
        this.sender = sender;
        this.textService = textService;
        this.persistence = persistence;
    }

    // ------------------------------------------------------------------ session lifecycle

    /**
     * Registers a socket. The slot stays outside any room until the client asks to play.
     *
     * <p>The nickname comes from the verified principal attached during the handshake, never from the
     * client's first message, so a racer cannot claim to be somebody else.
     */
    public PlayerSlot onConnect(WebSocketSession session, GamePrincipal principal) {
        if (liveSocketsFor(principal.playerId()) >= properties.getAuth().getSocketsPerAccount()) {
            log.info("Player {} hit the live-socket cap; refusing socket {}",
                    principal.playerId(), session.getId());
            return null;
        }
        String clean = principal.nickname();
        PlayerSlot slot = new PlayerSlot(session, clean, ThreadLocalRandom.current().nextInt(8));
        slot.setPlayerId(principal.playerId());
        playersBySession.put(session.getId(), slot);
        sender.send(session.getId(), "welcome",
                new Payloads.Welcome(slot.getId(), clean, slot.getAvatarColor(), System.currentTimeMillis()));
        return slot;
    }

    public Optional<PlayerSlot> player(String sessionId) {
        return Optional.ofNullable(playersBySession.get(sessionId));
    }

    private long liveSocketsFor(long playerId) {
        return playersBySession.values().stream().filter(slot -> slot.getPlayerId() == playerId).count();
    }

    /**
     * Display-name change while connected.
     *
     * <p>The name on the socket is now the account's, so a rename no longer proves who you are: it is
     * only cosmetic, and the account behind the slot is unchanged. Spoofing a rival's name is still
     * possible here, so the server clamps it against names already in play and persistence keys off
     * the player id rather than the display string.
     */
    public void rename(PlayerSlot slot, String nickname) {
        String clean = sanitizeNickname(nickname);
        GameRoom room = findRoomOf(slot);
        if (room != null) {
            synchronized (room.lock()) {
                if (room.getPhase() == Phase.RACING || room.getPhase() == Phase.COUNTDOWN) {
                    return;
                }
                if (nameTakenInRoom(room, slot, clean)) {
                    error(slot, "nickname_taken", "Someone in this room is already using that name.");
                    return;
                }
                slot.setNickname(clean);
                room.touch();
            }
            pushLobby(room);
            return;
        }
        slot.setNickname(clean);
        sender.send(slot.getSession().getId(), "welcome",
                new Payloads.Welcome(slot.getId(), clean, slot.getAvatarColor(), System.currentTimeMillis()));
    }

    private boolean nameTakenInRoom(GameRoom room, PlayerSlot self, String nickname) {
        return room.players().stream()
                .anyMatch(p -> p != self && p.getNickname().equalsIgnoreCase(nickname));
    }

    public void onDisconnect(String sessionId) {
        PlayerSlot slot = playersBySession.remove(sessionId);
        sender.unregister(sessionId);
        if (slot == null) {
            return;
        }
        leaveQueue(sessionId);
        leaveRoom(slot, "disconnected");
    }

    // ------------------------------------------------------------------ joining

    public void joinQuickMatch(PlayerSlot slot) {
        GameProperties.QuickMatch config = properties.getGame().getQuickMatch();
        if (!config.isEnabled()) {
            error(slot, "quick_match_disabled", "Quick match is turned off on this server.");
            return;
        }
        leaveRoom(slot, "switching");
        synchronized (matchQueue) {
            if (!matchQueue.contains(slot.getSession().getId())) {
                matchQueue.addLast(slot.getSession().getId());
                queuedAt.put(slot.getSession().getId(), System.currentTimeMillis());
            }
            announceQueue();
            tryPair();
        }
    }

    public void joinRoom(PlayerSlot slot, String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            error(slot, "bad_code", "Enter a room code.");
            return;
        }
        leaveRoom(slot, "switching");
        GameRoom room = rooms.get(code);
        if (room == null) {
            room = new GameRoom(code, false);
            GameRoom existing = rooms.putIfAbsent(code, room);
            if (existing != null) {
                room = existing;
            }
        }
        synchronized (room.lock()) {
            if (room.racerCount() >= properties.getGame().getMaxPlayers()) {
                error(slot, "room_full", "That room already has " + properties.getGame().getMaxPlayers()
                        + " players.");
                return;
            }
            room.put(slot);
            if (room.getHostId() == null || !room.contains(room.getHostId())) {
                room.setHostId(slot.getId());
                slot.setHost(true);
            } else {
                slot.setHost(false);
            }
            if (room.getPhase() == Phase.RACING || room.getPhase() == Phase.COUNTDOWN) {
                slot.setSpectator(true);
            }
            room.touch();
        }
        sender.send(slot.getSession().getId(), "joined", lobbyPayload(room, slot));
        pushLobby(room);
    }

    public void leaveRoom(PlayerSlot slot, String reason) {
        GameRoom room = findRoomOf(slot);
        if (room == null) {
            return;
        }
        slot.setConnected(false);
        synchronized (room.lock()) {
            if (room.getPhase() == Phase.RACING || room.getPhase() == Phase.COUNTDOWN) {
                // Keep the slot so the standings can show the dropout as DNF.
                slot.setReady(false);
            } else {
                room.remove(slot.getId());
            }
            room.reassignHost(room.getHostId());
            room.touch();
            boolean empty = room.connectedCount() == 0;
            if (empty) {
                room.setPhase(Phase.DISSOLVED);
                rooms.remove(room.getCode());
            }
        }
        if (!"disconnected".equals(reason)) {
            sender.send(slot.getSession().getId(), "room_closed", new Payloads.RoomClosed(reason));
        }
        if (room.getPhase() == Phase.DISSOLVED) {
            broadcastToRoom(room, "room_closed", new Payloads.RoomClosed("all_players_left"));
            return;
        }
        pushLobby(room);
        pushState(room);
    }

    // ------------------------------------------------------------------ ready / start / rematch

    public void setReady(PlayerSlot slot, boolean ready) {
        GameRoom room = findRoomOf(slot);
        if (room == null) {
            return;
        }
        synchronized (room.lock()) {
            if (room.getPhase() != Phase.LOBBY) {
                return;
            }
            slot.setReady(ready);
            room.touch();
            if (ready && allRacersReady(room)) {
                beginCountdown(room);
            }
        }
        pushLobby(room);
    }

    /** Host-only: skip the ready wall. */
    public void forceStart(PlayerSlot slot) {
        GameRoom room = findRoomOf(slot);
        if (room == null) {
            return;
        }
        synchronized (room.lock()) {
            if (room.getPhase() != Phase.LOBBY) {
                return;
            }
            if (!slot.isHost()) {
                error(slot, "not_host", "Only the host can start the race.");
                return;
            }
            if (room.racerCount() < properties.getGame().getMinPlayers()) {
                error(slot, "need_more_players", "You need at least " + properties.getGame().getMinPlayers()
                        + " racers to start.");
                return;
            }
            beginCountdown(room);
        }
        pushLobby(room);
    }

    public void voteRematch(PlayerSlot slot, boolean again) {
        GameRoom room = findRoomOf(slot);
        if (room == null) {
            return;
        }
        synchronized (room.lock()) {
            if (room.getPhase() != Phase.FINISHED) {
                return;
            }
            room.voteRematch(slot.getId(), again);
            if (room.everyoneVotedRematch(room.racerCount())) {
                resetToLobby(room);
                beginCountdown(room);
            }
        }
        pushLobby(room);
    }

    private void beginCountdown(GameRoom room) {
        if (room.racerCount() < properties.getGame().getMinPlayers()) {
            return;
        }
        TextSnippet snippet = textService.pick();
        room.setSnippet(snippet);
        int seq = room.nextRaceSeq();
        room.setRaceId(room.getCode() + "-" + seq);
        room.clearRematchVotes();
        room.setRaceClosed(false);
        room.setFirstFinishAtMs(0);
        room.setTimeLimitMs(properties.getGame().getTimeLimitMs());

        long now = System.currentTimeMillis();
        long startAt = now + properties.getGame().getCountdownMs();
        room.setStartAtEpochMs(startAt);
        room.setCountdownEndsAtMs(startAt);
        room.setPhase(Phase.COUNTDOWN);

        for (PlayerSlot racer : room.players()) {
            boolean lateJoiner = racer.isSpectator();
            racer.resetForNewRace();
            racer.setSpectator(lateJoiner);
            if (racer.isConnected() && !lateJoiner) {
                racer.setReady(true);
            }
        }
        room.touch();

        List<PlayerSlot> targets = room.players().stream().filter(PlayerSlot::isConnected).toList();
        sender.broadcast(targets, "race_start", new Payloads.RaceStart(room.getCode(), room.getTextContent(),
                room.getTotalChars(), startAt, room.getTimeLimitMs(), room.getRaceId()));
        sender.broadcast(targets, "countdown", new Payloads.Countdown(room.getCode(), startAt,
                (int) (properties.getGame().getCountdownMs() / 1000),
                (int) Math.max(1, properties.getGame().getCountdownMs() / 1000)));
        log.info("Race {} starting in room {} with {} racers", room.getRaceId(), room.getCode(),
                room.racerCount());
    }

    private void resetToLobby(GameRoom room) {
        room.setPhase(Phase.LOBBY);
        room.setRaceId(null);
        room.clearRematchVotes();
        for (PlayerSlot player : room.players()) {
            player.resetForNewRace();
            if (room.isQuickMatch() && player.isConnected()) {
                player.setReady(true);
            }
        }
        room.touch();
    }

    // ------------------------------------------------------------------ live progress

    public void onProgress(PlayerSlot slot, ClientCommand command) {
        GameRoom room = findRoomOf(slot);
        if (room == null) {
            return;
        }
        synchronized (room.lock()) {
            long now = System.currentTimeMillis();
            if (!raceIsLive(room, now) || slot.isSpectator()) {
                return;
            }
            applyProgress(room, slot, command, now);
        }
    }

    /**
     * A room is live once the shared start instant has passed. The COUNTDOWN → RACING flip happens on
     * the tick boundary, so without this a keystroke or finish landing in that &lt;100ms window would be
     * silently dropped and the racer would be stuck until the time limit.
     */
    private boolean raceIsLive(GameRoom room, long now) {
        if (room.getPhase() == Phase.RACING) {
            return true;
        }
        return room.getPhase() == Phase.COUNTDOWN && now >= room.getStartAtEpochMs();
    }

    private void applyProgress(GameRoom room, PlayerSlot slot, ClientCommand command, long now) {
        int claimedCorrect = clamp(command.correctChars(), 0, room.getTotalChars());
        int claimedErrors = Math.max(0, command.errors() == null ? 0 : command.errors());
        int claimedKeystrokes = Math.max(0, command.keystrokes() == null ? 0 : command.keystrokes());

        slot.setErrors(Math.max(slot.getErrors(), claimedErrors));
        slot.setKeystrokes(Math.max(slot.getKeystrokes(), claimedKeystrokes));

        if (slot.getFirstKeystrokeAtMs() == 0 && (claimedCorrect + claimedErrors) > 0) {
            slot.setFirstKeystrokeAtMs(now);
        }

        if (claimedCorrect > slot.getCorrectChars()) {
            long elapsed = slot.getLastCharCreditAtMs() == 0
                    ? now - room.getStartAtEpochMs()
                    : now - slot.getLastCharCreditAtMs();
            int budget = RaceMath.charBudget(elapsed, properties.getGame().getMaxWpm());
            int delta = claimedCorrect - slot.getCorrectChars();
            if (delta > budget) {
                slot.setCorrectChars(slot.getCorrectChars() + budget);
                slot.setFlagged(true);
                log.warn("Clamped progress claim for {} in {}: +{} claimed, +{} allowed", slot.getNickname(),
                        room.getCode(), delta, budget);
            } else {
                slot.setCorrectChars(claimedCorrect);
            }
            slot.setLastCharCreditAtMs(now);
        }
    }

    public void onFinish(PlayerSlot slot, ClientCommand command) {
        GameRoom room = findRoomOf(slot);
        if (room == null) {
            return;
        }
        synchronized (room.lock()) {
            long now = System.currentTimeMillis();
            if (!raceIsLive(room, now) || slot.isSpectator() || slot.isFinished()) {
                return;
            }
            applyProgress(room, slot, command, now);

            if (slot.getCorrectChars() < room.getTotalChars() && !slot.isFlagged()) {
                // Honest player who submitted early: tell them to keep typing.
                error(slot, "incomplete", "Finish the whole text before you submit.");
                pushState(room);
                return;
            }
            // A clamped player can never reach totalChars, so their finish is honoured but the
            // result is flagged and its WPM is pinned to the ceiling (see closeRace/persistence).

            int place = 1;
            for (PlayerSlot other : room.players()) {
                if (other != slot && other.isFinished()) {
                    place++;
                }
            }
            slot.setFinished(true);
            slot.setPlace(place);
            slot.setFinishAtMs(now);
            if (slot.getFirstKeystrokeAtMs() == 0) {
                slot.setFirstKeystrokeAtMs(room.getStartAtEpochMs());
            }
            double rawWpm = RaceMath.wpm(slot.getCorrectChars(), slot.getFinishAtMs() - slot.getFirstKeystrokeAtMs());
            double wpm = RaceMath.clampWpm(rawWpm, properties.getGame().getMaxWpm());
            if (rawWpm > properties.getGame().getMaxWpm()) {
                slot.setFlagged(true);
            }

            log.info("{} finished race {} in {} place at {} wpm", slot.getNickname(), room.getRaceId(), place, wpm);

            broadcastToRoom(room, "player_finished", new Payloads.PlayerFinished(slot.getId(),
                    slot.getNickname(), place, wpm, RaceMath.accuracy(slot.getCorrectChars(), slot.getErrors()),
                    slot.getFinishAtMs() - slot.getFirstKeystrokeAtMs()));

            if (allRacersDone(room)) {
                closeRace(room, now);
            }
            pushState(room);
        }
    }

    private boolean allRacersDone(GameRoom room) {
        return room.players().stream()
                .filter(p -> !p.isSpectator())
                .allMatch(p -> p.isFinished() || !p.isConnected());
    }

    // ------------------------------------------------------------------ phase machine (driven by broadcaster)

    void tick() {
        long now = System.currentTimeMillis();
        for (GameRoom room : rooms.values()) {
            boolean dirty = false;
            synchronized (room.lock()) {
                switch (room.getPhase()) {
                    case COUNTDOWN -> {
                        if (now >= room.getCountdownEndsAtMs()) {
                            room.setPhase(Phase.RACING);
                            dirty = true;
                        }
                    }
                    case RACING -> {
                        if (allRacersDone(room)) {
                            closeRace(room, now);
                        } else if (room.getFirstFinishAtMs() > 0
                                && now - room.getFirstFinishAtMs() > properties.getGame().getFinishGraceMs()) {
                            closeRace(room, now);
                        } else if (now - room.getStartAtEpochMs() > room.getTimeLimitMs()) {
                            closeRace(room, now);
                        }
                    }
                    case FINISHED -> {
                        if (now - room.getLastActivityMs() > FINISHED_ROOM_TTL_MS) {
                            room.setPhase(Phase.LOBBY);
                            room.setRaceId(null);
                            dirty = true;
                        }
                    }
                    case LOBBY -> {
                        if (room.isQuickMatch() && allRacersReady(room)) {
                            beginCountdown(room);
                            dirty = true;
                        }
                    }
                    case DISSOLVED -> {
                        rooms.remove(room.getCode());
                    }
                    default -> {
                        // no-op
                    }
                }
                if (room.getPhase() != Phase.DISSOLVED && room.connectedCount() == 0) {
                    room.setPhase(Phase.DISSOLVED);
                    rooms.remove(room.getCode());
                }
            }
            if (dirty) {
                pushLobby(room);
            }
            Phase phase = room.getPhase();
            if (phase == Phase.RACING || phase == Phase.COUNTDOWN || phase == Phase.FINISHED) {
                pushState(room);
            }
        }
    }

    private void closeRace(GameRoom room, long now) {
        if (room.isRaceClosed()) {
            return;
        }
        room.setRaceClosed(true);
        room.setPhase(Phase.FINISHED);

        List<PlayerSlot> racers = room.players().stream().filter(p -> !p.isSpectator()).toList();
        int fieldSize = racers.size();
        List<PlayerSlot> ordered = new ArrayList<>(racers);
        ordered.sort(Comparator
                // Flagged (anti-cheat clamped) runs always rank behind honest ones.
                .comparing((PlayerSlot p) -> p.isFlagged() ? 1 : 0)
                .thenComparing(p -> p.isFinished() ? 0 : 1)
                .thenComparing(p -> p.getFinishAtMs() == 0 ? Long.MAX_VALUE : p.getFinishAtMs())
                .thenComparingInt(p -> p.getPlace())
                .thenComparing(Comparator.comparingDouble((PlayerSlot p) -> -progressOf(room, p))));

        List<Payloads.Standing> standings = new ArrayList<>();
        List<ResultPersistenceService.RaceRecord> records = new ArrayList<>();
        double bestWpm = -1;
        int index = 0;
        for (PlayerSlot player : ordered) {
            index++;
            boolean dnf = !player.isFinished();
            long durationMs = player.getFirstKeystrokeAtMs() == 0 ? 0
                    : player.getFinishAtMs() - player.getFirstKeystrokeAtMs();
            double rawWpm = player.isFinished() ? RaceMath.wpm(player.getCorrectChars(), durationMs) : 0;
            double wpm = RaceMath.clampWpm(rawWpm, properties.getGame().getMaxWpm());
            if (rawWpm > properties.getGame().getMaxWpm()) {
                player.setFlagged(true);
            }
            double accuracy = RaceMath.accuracy(player.getCorrectChars(), player.getErrors());
            int place = player.isFinished() ? (player.getPlace() > 0 ? player.getPlace() : index) : 0;
            if (!dnf && !player.isFlagged() && wpm > bestWpm) {
                bestWpm = wpm;
                player.setBestInRoom(true);
            }

            standings.add(new Payloads.Standing(player.getId(), player.getNickname(), player.getAvatarColor(),
                    dnf ? 0 : place, dnf, wpm, accuracy, player.getCorrectChars(), player.getErrors(), durationMs,
                    player.isBestInRoom(), player.isFlagged()));

            if (player.getNickname() != null) {
                records.add(new ResultPersistenceService.RaceRecord(room.getRaceId(), player.getNickname(),
                        player.getPlayerId(), dnf ? fieldSize : place, fieldSize, wpm, rawWpm, accuracy,
                        player.getCorrectChars(), player.getErrors(), durationMs, !dnf, player.isFlagged(),
                        room.getSnippet() == null ? null : room.getSnippet().getId()));
            }
        }

        records.forEach(persistence::saveAsync);
        room.touch();

        sender.broadcast(racers.stream().filter(PlayerSlot::isConnected).toList(), "race_over",
                new Payloads.RaceOver(room.getRaceId(), room.getCode(), now, standings));
        log.info("Race {} closed in room {}: {} standings", room.getRaceId(), room.getCode(), standings.size());
    }

    private double progressOf(GameRoom room, PlayerSlot player) {
        return RaceMath.progress(player.getCorrectChars(), room.getTotalChars());
    }

    // ------------------------------------------------------------------ snapshots

    public void pushLobby(GameRoom room) {
        List<PlayerSlot> players = room.players();
        for (PlayerSlot target : players) {
            if (target.isConnected()) {
                sender.send(target.getSession().getId(), "joined", lobbyPayload(room, target));
            }
        }
    }

    public void pushState(GameRoom room) {
        if (room.getTotalChars() == 0 && room.getRaceId() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = room.getPhase() == Phase.RACING || room.getPhase() == Phase.FINISHED
                ? Math.max(0, now - room.getStartAtEpochMs())
                : 0;

        List<Payloads.ProgressView> views = new ArrayList<>();
        for (PlayerSlot player : room.players()) {
            long typedFor = player.getFirstKeystrokeAtMs() == 0 ? 0 : now - player.getFirstKeystrokeAtMs();
            views.add(new Payloads.ProgressView(player.getId(), player.getNickname(), player.getAvatarColor(),
                    player.getCorrectChars(), player.getErrors(),
                    RaceMath.progress(player.getCorrectChars(), room.getTotalChars()),
                    player.isFinished()
                            ? RaceMath.clampWpm(RaceMath.wpm(player.getCorrectChars(),
                                    player.getFinishAtMs() - player.getFirstKeystrokeAtMs()),
                                    properties.getGame().getMaxWpm())
                            : RaceMath.liveWpm(player.getCorrectChars(), typedFor),
                    RaceMath.accuracy(player.getCorrectChars(), player.getErrors()),
                    player.isFinished(), player.getPlace(), false));
        }

        List<PlayerSlot> targets = room.players().stream().filter(PlayerSlot::isConnected).toList();
        for (PlayerSlot target : targets) {
            List<Payloads.ProgressView> marked = views.stream()
                    .map(v -> new Payloads.ProgressView(v.id(), v.nickname(), v.avatarColor(), v.correctChars(),
                            v.errors(), v.progress(), v.wpm(), v.accuracy(), v.finished(), v.place(),
                            v.id().equals(target.getId())))
                    .toList();
            sender.send(target.getSession().getId(), "state", new Payloads.RaceState(room.getCode(),
                    room.getPhase().name(), now, elapsed, room.getTotalChars(), marked));
        }
    }

    private Payloads.Joined lobbyPayload(GameRoom room, PlayerSlot viewer) {
        List<Payloads.PlayerView> players = room.players().stream()
                .map(p -> new Payloads.PlayerView(p.getId(), p.getNickname(), p.getAvatarColor(), p.isReady(),
                        p.isConnected(), p.isFinished(), p.isHost(), p.isSpectator(), p.getPlace()))
                .toList();
        return new Payloads.Joined(room.getCode(), viewer.isHost(), players, room.getPhase().name(),
                properties.getGame().getMinPlayers(), properties.getGame().getMaxPlayers(), room.isQuickMatch(),
                System.currentTimeMillis());
    }

    private void broadcastToRoom(GameRoom room, String type, Object data) {
        sender.broadcast(room.players().stream().filter(PlayerSlot::isConnected).toList(), type, data);
    }

    private void error(PlayerSlot slot, String code, String message) {
        sender.send(slot.getSession().getId(), "error", new Payloads.Error(code, message));
    }

    // ------------------------------------------------------------------ helpers

    private boolean allRacersReady(GameRoom room) {
        List<PlayerSlot> racers = room.players().stream()
                .filter(p -> p.isConnected() && !p.isSpectator())
                .toList();
        return racers.size() >= properties.getGame().getMinPlayers() && racers.stream().allMatch(PlayerSlot::isReady);
    }

    private GameRoom findRoomOf(PlayerSlot slot) {
        for (GameRoom room : rooms.values()) {
            if (room.contains(slot.getId())) {
                return room;
            }
        }
        return null;
    }

    private void tryPair() {
        GameProperties.QuickMatch config = properties.getGame().getQuickMatch();
        int needed = Math.max(2, config.getPairSize());
        while (matchQueue.size() >= needed) {
            List<String> batch = new ArrayList<>();
            for (int i = 0; i < needed; i++) {
                batch.add(matchQueue.pollFirst());
            }
            batch.forEach(queuedAt::remove);

            List<PlayerSlot> group = new ArrayList<>();
            for (String sessionId : batch) {
                PlayerSlot slot = playersBySession.get(sessionId);
                if (slot == null || findRoomOf(slot) != null) {
                    continue;
                }
                group.add(slot);
            }
            if (group.size() < 2) {
                continue;
            }
            String code = uniqueRoomCode();
            GameRoom room = new GameRoom(code, true);
            rooms.put(code, room);
            synchronized (room.lock()) {
                int i = 0;
                for (PlayerSlot slot : group) {
                    slot.setHost(i == 0);
                    slot.setReady(true);
                    slot.setConnected(true);
                    room.put(slot);
                    i++;
                }
                room.setHostId(group.get(0).getId());
                room.touch();
            }
            for (PlayerSlot slot : group) {
                sender.send(slot.getSession().getId(), "left_queue", null);
                sender.send(slot.getSession().getId(), "joined", lobbyPayload(room, slot));
            }
            pushLobby(room);
            log.info("Quick match paired {} players into room {}", group.size(), code);
        }
    }

    private void announceQueue() {
        int size = matchQueue.size();
        for (String sessionId : new ArrayList<>(matchQueue)) {
            long since = queuedAt.getOrDefault(sessionId, System.currentTimeMillis());
            sender.send(sessionId, "queued", new Payloads.Queued(size, Math.max(2,
                    properties.getGame().getQuickMatch().getPairSize()), System.currentTimeMillis() - since));
        }
    }

    private void leaveQueue(String sessionId) {
        boolean removed;
        synchronized (matchQueue) {
            removed = matchQueue.remove(sessionId);
            queuedAt.remove(sessionId);
        }
        if (removed) {
            announceQueue();
        }
    }

    /** Reserves an unused room code without creating the room (created on first join). */
    public String newRoomCode() {
        return uniqueRoomCode();
    }

    private String uniqueRoomCode() {
        for (int attempt = 0; attempt < 40; attempt++) {
            String code = randomCode();
            if (!rooms.containsKey(code)) {
                return code;
            }
        }
        return "R" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT);
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            sb.append(CODE_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private int clamp(Integer value, int min, int max) {
        int v = value == null ? 0 : value;
        return Math.max(min, Math.min(max, v));
    }

    public static String sanitizeNickname(String raw) {
        if (raw == null) {
            return "Racer";
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9 _\\-.]", "").trim();
        if (cleaned.length() < 3) {
            cleaned = "Racer";
        }
        if (cleaned.length() > 16) {
            cleaned = cleaned.substring(0, 16);
        }
        return cleaned;
    }
}
package com.typerush.game;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.typerush.persistence.Player;
import com.typerush.persistence.PlayerRepository;
import com.typerush.persistence.RaceResult;
import com.typerush.persistence.RaceResultRepository;

import jakarta.annotation.PreDestroy;

/**
 * Writes race outcomes to MySQL on a dedicated single thread so the WebSocket handler thread is
 * never blocked by a database round trip.
 */
@Service
public class ResultPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(ResultPersistenceService.class);

    private final PlayerRepository playerRepository;
    private final RaceResultRepository resultRepository;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "race-persistence");
        t.setDaemon(true);
        return t;
    });

    public ResultPersistenceService(PlayerRepository playerRepository, RaceResultRepository resultRepository) {
        this.playerRepository = playerRepository;
        this.resultRepository = resultRepository;
    }

    public void saveAsync(RaceRecord record) {
        writer.submit(() -> {
            try {
                persist(record);
            } catch (Exception ex) {
                log.error("Failed to persist race {} for {}", record.raceId(), record.nickname(), ex);
            }
        });
    }

    @Transactional
    void persist(RaceRecord record) {
        Player player = resolvePlayer(record);

        RaceResult result = RaceResult.of(record.raceId(), player, record.place(), record.fieldSize(),
                record.wpm(), record.rawWpm(), record.accuracy(), record.correctChars(), record.errors(),
                record.durationMs(), record.finished(), record.flagged(), record.textId());
        resultRepository.save(result);

        // Flagged (anti-cheat clamped) runs are kept as an audit trail but never credited to the
        // player's career stats, so a bogus score can never reach the leaderboard.
        if (record.finished() && !record.flagged()) {
            player.recordRace(record.wpm(), record.accuracy(), record.correctChars(), record.durationMs(),
                    record.place(), record.fieldSize());
            playerRepository.saveAndFlush(player);
        }
        log.info("Recorded {} in race {} as place {} ({} wpm, flagged={})", record.nickname(), record.raceId(),
                record.place(), record.wpm(), record.flagged());
    }

    /**
     * Resolves the account behind a result.
     *
     * <p>Previously the nickname from the socket decided which {@code Player} row absorbed the score,
     * which meant any client could credit itself under another racer's name by sending a different
     * nickname. Identity now comes from the authenticated principal and the id is looked up directly;
     * the nickname is only used for display and for legacy rows that predate accounts.
     */
    private Player resolvePlayer(RaceRecord record) {
        if (record.playerId() != null) {
            return playerRepository.findById(record.playerId()).orElse(null);
        }
        return playerRepository.findByNicknameKey(record.nickname().toUpperCase())
                .orElseGet(() -> {
                    Player created = new Player();
                    created.setNickname(record.nickname());
                    return playerRepository.saveAndFlush(created);
                });
    }

    @PreDestroy
    void shutdown() {
        writer.shutdown();
    }

    /**
     * Immutable snapshot of one player's outcome at race close.
     *
     * @param playerId authenticated account id; authoritative for crediting stats
     */
    public record RaceRecord(String raceId, String nickname, Long playerId, int place, int fieldSize, double wpm,
            double rawWpm, double accuracy, int correctChars, int errors, long durationMs, boolean finished,
            boolean flagged, Long textId) {
    }
}
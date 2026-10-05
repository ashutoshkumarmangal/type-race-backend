package com.typerush.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.typerush.persistence.Player;
import com.typerush.persistence.PlayerRepository;
import com.typerush.persistence.RaceResult;
import com.typerush.persistence.RaceResultRepository;
import com.typerush.persistence.TextSnippet;
import com.typerush.persistence.TextSnippetRepository;
import com.typerush.security.GamePrincipal;

@RestController
@RequestMapping("/api")
public class StatsController {

    private final PlayerRepository playerRepository;
    private final RaceResultRepository resultRepository;
    private final TextSnippetRepository snippetRepository;

    public StatsController(PlayerRepository playerRepository, RaceResultRepository resultRepository,
            TextSnippetRepository snippetRepository) {
        this.playerRepository = playerRepository;
        this.resultRepository = resultRepository;
        this.snippetRepository = snippetRepository;
    }

    @GetMapping("/health")
    public Health health() {
        return new Health("up", snippetRepository.countByActiveTrue());
    }

    @GetMapping("/players/{nickname}")
    public ResponseEntity<PlayerSummary> player(@PathVariable String nickname) {
        return playerRepository.findByNicknameKey(nickname.toUpperCase())
                .map(p -> ResponseEntity.ok(PlayerSummary.of(p, bestPlace(p))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private int bestPlace(Player p) {
        return resultRepository.findBestPlaceByPlayerId(p.getId()).orElse(0);
    }

    /**
     * The caller's own race history.
     *
     * <p>Reading it requires the same account the results belong to: the id comes from the verified
     * principal, never from the path, so there is no way to walk another player's history.
     */
@GetMapping("/me/races")
    public List<RaceHistoryItem> myHistory(@AuthenticationPrincipal GamePrincipal principal,
            @RequestParam(defaultValue = "10") int limit) {
        return resultRepository.findByPlayerIdOrderByCreatedAtDesc(principal.playerId(),
                PageRequest.of(0, clamp(limit, 1, 50)))
                .stream()
                .map(RaceHistoryItem::of)
                .toList();
    }

    /** The signed-in account, used by the frontend to render the profile without a second guess. */
    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal GamePrincipal principal) {
        return playerRepository.findById(principal.playerId())
                .map(p -> new Me(p.getId(), p.getUsername(), p.getNickname(), p.getRole(), p.getLastLoginAt()))
                .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException(
                        "account no longer exists"));
    }

    @GetMapping("/me/stats")
    public PlayerSummary myStats(@AuthenticationPrincipal GamePrincipal principal) {
        Player player = playerRepository.findById(principal.playerId())
                .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException(
                        "account no longer exists"));
        return PlayerSummary.of(player, bestPlace(player));
    }

    /** Deliberately absent: any {@code /api/players/{name}/races} route. History is not public. */

    @GetMapping("/leaderboard")
    public List<LeaderboardEntry> leaderboard(@RequestParam(defaultValue = "20") int limit) {
        List<Player> players = playerRepository.topByBestWpm(PageRequest.of(0, clamp(limit, 1, 100)));
        List<LeaderboardEntry> entries = new ArrayList<>(players.size());
        for (int i = 0; i < players.size(); i++) {
            entries.add(LeaderboardEntry.of(i + 1, players.get(i)));
        }
        return entries;
    }

    @GetMapping("/texts")
    public List<TextItem> texts() {
        return snippetRepository.findAll().stream().map(TextItem::of).toList();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Health(String status, long textCount) {
    }

    public record PlayerSummary(String nickname, int racesPlayed, int wins, int podiums,
            double bestWpm, double bestAccuracy, double avgWpm, long totalCharsTyped, int bestPlace) {

        static PlayerSummary of(Player p, int bestPlace) {
            return new PlayerSummary(p.getNickname(), p.getRacesPlayed(), p.getWins(), p.getPodiums(),
                    p.getBestWpm(), p.getBestAccuracy(), p.getAvgWpm(), p.getTotalCharsTyped(), bestPlace);
        }
    }

    public record LeaderboardEntry(int rank, String nickname, int racesPlayed, int wins, int podiums,
            double bestWpm, double avgWpm, double bestAccuracy) {

        static LeaderboardEntry of(int rank, Player p) {
            return new LeaderboardEntry(rank, p.getNickname(), p.getRacesPlayed(), p.getWins(), p.getPodiums(),
                    p.getBestWpm(), p.getAvgWpm(), p.getBestAccuracy());
        }
    }

    public record RaceHistoryItem(String raceId, int place, boolean finished, boolean flagged, double wpm,
            double accuracy, int correctChars, int errorChars, long durationMs, Instant finishedAt) {

        static RaceHistoryItem of(RaceResult r) {
            return new RaceHistoryItem(r.getRaceId(), r.getPlace(), r.isFinished(), r.isFlagged(), r.getWpm(),
                    r.getAccuracy(), r.getCorrectChars(), r.getErrorChars(), r.getDurationMs(), r.getCreatedAt());
        }
    }

    public record Me(Long id, String username, String nickname, String role, Instant lastLoginAt) {
    }

    public record TextItem(Long id, String category, int charCount, String preview) {

        static TextItem of(TextSnippet t) {
            String content = t.getContent();
            String preview = content.substring(0, Math.min(90, content.length())) + "...";
            return new TextItem(t.getId(), t.getCategory(), t.getCharCount(), preview);
        }
    }
}
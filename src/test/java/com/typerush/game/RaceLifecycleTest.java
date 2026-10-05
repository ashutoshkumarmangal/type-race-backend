package com.typerush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typerush.persistence.RefreshTokenRepository;

/**
 * Covers the two race-lifecycle rules that are easy to regress because nothing else exercises them:
 * a lone racer still gets a fully scored result, and abandoning a race mid-flight does not disturb
 * the racers who stayed.
 *
 * <p>Runs against the same MySQL as the app; see {@code AuthFlowTest} for why the schema is real
 * rather than mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=${TEST_DB_URL:jdbc:mysql://127.0.0.1:3307/typerush_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8}",
        "spring.datasource.username=${TEST_DB_USER:typerush}",
        "spring.datasource.password=${TEST_DB_PASSWORD:typerush}",
        "spring.jpa.hibernate.ddl-auto=update",
        "typerush.auth.jwt-secret=test-secret-that-is-long-enough-for-hs256-signing",
        // Several accounts register from the same loopback address inside this suite.
        "typerush.auth.registrations-per-ip=1000",
        "typerush.auth.login-attempts-per-ip=1000",
        "typerush.auth.login-attempts-per-user=1000",
        "typerush.auth.rooms-per-hour=1000",
        // A long countdown would dominate the runtime without testing anything extra.
        "typerush.game.countdown-ms=500",
        "typerush.game.finish-grace-ms=2000",
        // These tests type far faster than a human to keep the suite quick. The anti-cheat has its
        // own coverage, so the ceiling is lifted here rather than letting every run be flagged.
        "typerush.game.max-wpm=900"
})
class RaceLifecycleTest {

    private static final String PASSWORD = "correct-horse-battery";

    @LocalServerPort
    int port;

    @Autowired
    private RefreshTokenRepository tokens;

    private final ObjectMapper json = new ObjectMapper();
    private String uniqueSuffix;

    @BeforeEach
    void reset() {
        tokens.deleteAll();
        uniqueSuffix = Long.toString(System.nanoTime(), 36);
    }

    private RestTemplate rest() {
        RestTemplate template = new RestTemplate(new org.springframework.http.client.JdkClientHttpRequestFactory());
        template.setErrorHandler(new NoOpErrorHandler());
        return template;
    }

    // ----------------------------------------------------------------- solo race

    @Test
    void aLoneRacerGetsAFullyScoredResult() throws Exception {
        Racer solo = racer("solo");
        String code = newRoom(solo);

        solo.send(Map.of("type", "join_room", "roomCode", code));
        // min-players is 1, so a lone racer must not be told to wait for an opponent.
        solo.send(Map.of("type", "start"));

        assertThat(solo.await("race_start", 20_000))
                .as("a lone racer can start a race").isTrue();
        solo.typeAtSpeed(400);

        JsonNode raceOver = solo.awaitJson("race_over", 40_000);
        assertThat(raceOver).as("solo race closes (saw " + solo.seen() + ")").isNotNull();

        JsonNode mine = standingFor(raceOver, solo.playerId);
        assertThat(mine).as("the solo racer appears in the standings").isNotNull();
        assertThat(mine.get("dnf").asBoolean()).as("solo racer is not a DNF").isFalse();
        assertThat(mine.get("place").asInt()).as("solo racer takes place 1").isEqualTo(1);
        assertThat(mine.get("wpm").asDouble()).as("solo wpm is scored").isGreaterThan(0d);
        assertThat(mine.get("accuracy").asDouble()).as("solo accuracy is scored").isGreaterThan(0d);
        assertThat(mine.get("durationMs").asLong()).as("solo duration is scored").isGreaterThan(0L);
        assertThat(mine.get("correctChars").asInt()).as("solo chars are credited").isGreaterThan(0);
        assertThat(mine.get("flagged").asBoolean()).as("an honest solo run is not flagged").isFalse();
    }

    // ----------------------------------------------------------------- leaving mid-race

    @Test
    void leavingMidRaceDoesNotDisturbTheRemainingRacer() throws Exception {
        Racer stayer = racer("stayer");
        Racer leaver = racer("leaver");
        String code = newRoom(stayer);

        // race_start only goes to room members, so both have to be in the room.
        stayer.send(Map.of("type", "join_room", "roomCode", code));
        Thread.sleep(400);
        leaver.send(Map.of("type", "join_room", "roomCode", code));
        Thread.sleep(600);
        stayer.send(Map.of("type", "start"));

        assertThat(stayer.await("race_start", 20_000)).as("the race starts").isTrue();
        assertThat(leaver.await("race_start", 20_000)).as("the second racer is in the race").isTrue();

        // The leaver walks out partway through.
        leaver.send(Map.of("type", "leave"));
        assertThat(leaver.await("room_closed", 15_000))
                .as("the leaver is told its room closed").isTrue();

        stayer.typeAtSpeed(400);
        JsonNode raceOver = stayer.awaitJson("race_over", 40_000);

        assertThat(raceOver).as("the remaining racer still gets a result (saw " + stayer.seen() + ")").isNotNull();
        assertThat(stayer.stateCount()).as("live state kept flowing after the leaver").isGreaterThan(2);

        JsonNode mine = standingFor(raceOver, stayer.playerId);
        assertThat(mine.get("dnf").asBoolean()).as("the remaining racer finished").isFalse();
        assertThat(mine.get("place").asInt()).as("the remaining racer won the place").isEqualTo(1);
        assertThat(mine.get("wpm").asDouble()).as("the remaining racer is scored").isGreaterThan(0d);

        JsonNode dropped = standingFor(raceOver, leaver.playerId);
        assertThat(dropped).as("the leaver is still listed").isNotNull();
        assertThat(dropped.get("dnf").asBoolean()).as("the leaver shows as DNF").isTrue();
        assertThat(dropped.get("wpm").asDouble()).as("the leaver is credited no speed").isEqualTo(0d);
    }

    // ----------------------------------------------------------------- helpers

    private static JsonNode standingFor(JsonNode raceOver, String playerId) {
        for (JsonNode row : raceOver.get("standings")) {
            if (playerId.equals(row.get("id").asText())) {
                return row;
            }
        }
        return null;
    }

    private String newRoom(Racer racer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(racer.accessToken());
        ResponseEntity<String> response = rest().exchange(url("/api/rooms/new"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        try {
            return json.readTree(response.getBody()).get("roomCode").asText();
        } catch (Exception e) {
            throw new AssertionError("room creation did not return a code", e);
        }
    }

    private Racer racer(String label) throws Exception {
        String username = label + uniqueSuffix;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> registered = rest().postForEntity(url("/api/auth/register"),
                new HttpEntity<>(Map.of("username", username, "password", PASSWORD), headers), String.class);
        assertThat(registered.getStatusCode().value())
                .withFailMessage(() -> "register " + username + " -> " + registered.getStatusCode())
                .isEqualTo(201);

        ResponseEntity<String> loggedIn = rest().postForEntity(url("/api/auth/login"),
                new HttpEntity<>(Map.of("username", username, "password", PASSWORD), headers), String.class);
        assertThat(loggedIn.getStatusCode().value()).isEqualTo(200);
        String access = json.readTree(loggedIn.getBody()).get("accessToken").asText();

        Racer racer = new Racer(label, access);
        racer.connect();
        return racer;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** One authenticated socket, collecting every server frame so tests can await a specific type. */
    private final class Racer implements WebSocketHandler {
        private final String label;
        private final String accessToken;
        private final List<String> frames = new CopyOnWriteArrayList<>();
        private final CompletableFuture<WebSocketSession> connected = new CompletableFuture<>();
        private WebSocketSession session;
        private String playerId;
        private int totalChars;
        private long startAtEpochMs;

        Racer(String label, String accessToken) {
            this.label = label;
            this.accessToken = accessToken;
        }

        void connect() throws Exception {
            String ws = "ws://localhost:" + port + "/ws/game?token=" + accessToken;
            new StandardWebSocketClient().execute(this,
                    new WebSocketHttpHeaders(new HttpHeaders()), new URI(ws));
            session = connected.get(15, TimeUnit.SECONDS);
            // The welcome only arrives for an admitted connection, so it doubles as the admission signal.
            assertThat(await("welcome", 15_000)).as(label + " admitted").isTrue();
        }

        void send(Object command) {
            try {
                session.sendMessage(new TextMessage(json.writeValueAsString(command)));
            } catch (Exception e) {
                throw new AssertionError(label + " could not send " + command, e);
            }
        }

        /** Reports the whole text at a brisk pace so the race finishes well inside the test budget. */
        void typeAtSpeed(int wpm) {
            new Thread(() -> {
                double perSecond = (wpm * 5) / 60.0;
                int correct = 0;
                int keystrokes = 0;
                boolean submitted = false;
                long deadline = System.currentTimeMillis() + 60_000;
                try {
                    while (!submitted && System.currentTimeMillis() < deadline) {
                        double elapsed = (System.currentTimeMillis() - startAtEpochMs) / 1000.0;
                        if (elapsed <= 0) {
                            // Still counting down.
                            Thread.sleep(50);
                            continue;
                        }
                        int target = Math.min(totalChars, (int) (perSecond * elapsed));
                        if (target > correct) {
                            keystrokes += target - correct;
                            correct = target;
                            send(Map.of("type", "progress", "correctChars", correct,
                                    "errors", 2, "keystrokes", keystrokes));
                        }
                        if (correct >= totalChars) {
                            submitted = true;
                            send(Map.of("type", "finish", "correctChars", correct,
                                    "errors", 2, "keystrokes", keystrokes));
                        }
                        Thread.sleep(120);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, label + "-typer").start();
        }

        boolean await(String type, long timeoutMs) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (frameOf(type) != null) {
                    return true;
                }
                Thread.sleep(100);
            }
            return false;
        }

        /** Awaits a server frame and returns its {@code data} payload, matching the client's wire shape. */
        JsonNode awaitJson(String type, long timeoutMs) throws Exception {
            return await(type, timeoutMs) ? json.readTree(frameOf(type)).get("data") : null;
        }

        /** Frame types seen so far, so a timeout failure says what actually arrived. */
        String seen() {
            List<String> types = new ArrayList<>();
            for (String frame : frames) {
                try {
                    String t = json.readTree(frame).path("type").asText();
                    if (!types.contains(t)) {
                        types.add(t);
                    }
                } catch (Exception ignored) {
                    types.add("<unparsed>");
                }
            }
            return types.toString();
        }

        int stateCount() {
            return (int) frames.stream().filter(f -> f.contains("\"type\":\"state\"")).count();
        }

        private String frameOf(String type) {
            for (String frame : frames) {
                if (frame.contains("\"type\":\"" + type + "\"")) {
                    return frame;
                }
            }
            return null;
        }

        String playerId() {
            return playerId;
        }

        String accessToken() {
            return accessToken;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            this.session = session;
            connected.complete(session);
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
            Object payload = message.getPayload();
            String text = payload instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                    : payload.toString();
            frames.add(text);
            try {
                JsonNode node = json.readTree(text);
                String type = node.path("type").asText();
                JsonNode data = node.path("data");
                if ("welcome".equals(type)) {
                    playerId = data.path("playerId").asText();
                } else if ("race_start".equals(type)) {
                    totalChars = data.path("totalChars").asInt();
                    startAtEpochMs = data.path("startAtEpochMs").asLong();
                }
            } catch (Exception ignored) {
                // A frame this test cannot parse is still recorded for the type checks above.
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            connected.completeExceptionally(exception);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        }

        @Override
        public boolean supportsPartialMessages() {
            return false;
        }
    }

    private static class NoOpErrorHandler implements org.springframework.web.client.ResponseErrorHandler {
        @Override
        public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
            return false;
        }

        @Override
        public void handleError(URI url, HttpMethod method,
                org.springframework.http.client.ClientHttpResponse response) {
        }
    }
}

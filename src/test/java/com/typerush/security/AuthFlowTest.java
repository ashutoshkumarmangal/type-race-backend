package com.typerush.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import com.typerush.persistence.Player;
import com.typerush.persistence.PlayerRepository;
import com.typerush.persistence.RefreshTokenRepository;

/**
 * End-to-end checks on the real filter chain over HTTP, including the WebSocket upgrade.
 *
 * <p>These run against the same MySQL as the app so the schema and the unique constraints on
 * {@code username_key} are exercised for real, not mocked away.
 */
/**
 * Points at a dedicated {@code typerush_test} schema, never the development one, so running the
 * suite cannot touch real accounts or race history. Override with {@code TEST_DB_URL},
 * {@code TEST_DB_USER} and {@code TEST_DB_PASSWORD} in CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=${TEST_DB_URL:jdbc:mysql://127.0.0.1:3307/typerush_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8}",
        "spring.datasource.username=${TEST_DB_USER:typerush}",
        "spring.datasource.password=${TEST_DB_PASSWORD:typerush}",
        "spring.jpa.hibernate.ddl-auto=update",
        "typerush.auth.jwt-secret=test-secret-that-is-long-enough-for-hs256-signing",
        // Every test in this class registers from the same loopback address, so the production caps
        // would throttle the suite itself. Rate limiting is verified directly in RateLimiterTest.
        "typerush.auth.registrations-per-ip=1000",
        "typerush.auth.login-attempts-per-ip=1000",
        "typerush.auth.login-attempts-per-user=1000",
        "typerush.auth.rooms-per-hour=1000"
})
class AuthFlowTest {

    @LocalServerPort
    int port;

    @Autowired
    private AuthService auth;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private RefreshTokenRepository tokens;

    @Autowired
    private RefreshTokenRevoker revoker;

    @Autowired
    private com.typerush.config.GameProperties properties;

    /** Access token of the account under test, so a reconnect can reuse the same identity. */
    private final AtomicReference<String> lastAccessToken = new AtomicReference<>();

    /**
     * A fresh client per call: error responses are the point of most of these tests, so the handler
     * must return the 4xx body instead of throwing.
     */
    private RestTemplate rest() {
        RestTemplate template = new RestTemplate(
                new org.springframework.http.client.JdkClientHttpRequestFactory());
        template.setErrorHandler(new NoOpErrorHandler());
        return template;
    }

    private String uniqueSuffix;

    @BeforeEach
    void reset() {
        tokens.deleteAll();
        uniqueSuffix = Long.toString(System.nanoTime(), 36);
    }

    private String username() {
        return "tester" + uniqueSuffix;
    }

    // ----------------------------------------------------------------- registration and login

    @Test
    void registerThenLoginReturnsUsableSession() {
        String name = username();
        register(name, "correct-horse-battery");

        Map<String, Object> login = postJson("/api/auth/login",
                Map.of("username", name, "password", "correct-horse-battery"));

        assertThat(login).containsKeys("accessToken", "refreshToken", "username");
        assertThat(login.get("username")).isEqualTo(name);
        assertThat(login.get("accessToken")).asString().isNotBlank();

        Player stored = players.findByUsernameKey(name).orElseThrow();
        assertThat(stored.isCredentialed()).isTrue();
        assertThat(stored.getPasswordHash()).startsWith("$2");
        assertThat(stored.getPasswordHash()).doesNotContain("correct-horse-battery");
    }

    @Test
    void duplicateUsernameIsRejected() {
        String name = username();
        register(name, "correct-horse-battery");

        ResponseEntity<String> second = postRaw("/api/auth/register",
                Map.of("username", name, "password", "another-password"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(second.getBody()).contains("username_taken");
    }

    @Test
    void weakPasswordAndShortUsernameAreRejected() {
        assertThat(postRaw("/api/auth/register",
                Map.of("username", username(), "password", "short")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postRaw("/api/auth/register",
                Map.of("username", "ab", "password", "correct-horse-battery")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // bcrypt silently ignores anything past 72 bytes, so longer input is refused outright.
        assertThat(postRaw("/api/auth/register",
                Map.of("username", username(), "password", "x".repeat(73))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // Uppercase and surrounding whitespace are normalised rather than rejected.
        assertThat(registerRaw("  " + username().toUpperCase() + " ", "correct-horse-battery",
                HttpStatus.CREATED).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void usernamesAreCaseInsensitiveAndUnique() {
        String name = username();
        register(name, "correct-horse-battery");

        // The same name in a different case is the same account.
        assertThat(postRaw("/api/auth/register",
                Map.of("username", name.toUpperCase(), "password", "correct-horse-battery"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // ...and login works in any case.
        assertThat(postJson("/api/auth/login",
                Map.of("username", name.toUpperCase(), "password", "correct-horse-battery")))
                .containsEntry("username", name);
    }

    @Test
    void loginWithWrongPasswordIsIndistinguishableFromUnknownUser() {
        String name = username();
        register(name, "correct-horse-battery");

        ResponseEntity<String> wrongPassword = postRaw("/api/auth/login",
                Map.of("username", name, "password", "wrong-password-entirely"));
        ResponseEntity<String> unknownUser = postRaw("/api/auth/login",
                Map.of("username", "nobody" + uniqueSuffix, "password", "wrong-password-entirely"));

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Byte-identical replies, so neither the status nor the wording reveals whether the account
        // exists.
        assertThat(wrongPassword.getBody()).isNotNull();
        assertThat(wrongPassword.getBody()).isEqualTo(unknownUser.getBody());
        assertThat(wrongPassword.getBody()).contains("bad_credentials").doesNotContain(name);
        assertThat(unknownUser.getBody()).doesNotContain("nobody");
    }

    // ----------------------------------------------------------------- refresh rotation and reuse

    @Test
    void refreshRotatesTheTokenAndRevokesTheOldOne() {
        String name = username();
        register(name, "correct-horse-battery");
        Map<String, Object> login = postJson("/api/auth/login",
                Map.of("username", name, "password", "correct-horse-battery"));
        String original = (String) login.get("refreshToken");

        Map<String, Object> refreshed = postJson("/api/auth/refresh", Map.of("refreshToken", original));
        String rotated = (String) refreshed.get("refreshToken");

        assertThat(rotated).isNotBlank().isNotEqualTo(original);

        // The new token works...
        assertThat(postRaw("/api/auth/refresh", Map.of("refreshToken", rotated)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // ...and the consumed one does not.
        assertThat(postRaw("/api/auth/refresh", Map.of("refreshToken", original)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void replayingARotatedTokenRevokesTheWholeFamily() {
        String name = username();
        register(name, "correct-horse-battery");
        Map<String, Object> login = postJson("/api/auth/login",
                Map.of("username", name, "password", "correct-horse-battery"));
        String stolen = (String) login.get("refreshToken");

        String rotated = (String) postJson("/api/auth/refresh", Map.of("refreshToken", stolen))
                .get("refreshToken");

        // The attacker re-uses the stolen token: that is the signal of theft.
        assertThat(postRaw("/api/auth/refresh", Map.of("refreshToken", stolen)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // The legitimate holder is signed out too, which is the point.
        assertThat(postRaw("/api/auth/refresh", Map.of("refreshToken", rotated)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutRevokesTheRefreshToken() {
        String name = username();
        register(name, "correct-horse-battery");
        String refresh = (String) postJson("/api/auth/login",
                Map.of("username", name, "password", "correct-horse-battery")).get("refreshToken");

        postRaw("/api/auth/logout", Map.of("refreshToken", refresh));

        assertThat(postRaw("/api/auth/refresh", Map.of("refreshToken", refresh)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ----------------------------------------------------------------- access token enforcement

    @Test
    void publicEndpointsNeedNoToken() {
        assertThat(get("/api/health").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/texts").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/leaderboard").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void privateEndpointsRejectMissingAndForgedTokens() {
        String name = username();
        register(name, "correct-horse-battery");
        String access = (String) postJson("/api/auth/login",
                Map.of("username", name, "password", "correct-horse-battery")).get("accessToken");

        assertThat(get("/api/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/me", "not-a-jwt").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/me", access.substring(0, access.length() - 2) + "xy").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // A signature from a different secret must not be accepted.
        JwtService other = new JwtService(otherSecretProperties());
        Player player = players.findByUsernameKey(name).orElseThrow();
        assertThat(get("/api/me", other.issueAccessToken(player)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(get("/api/me", access).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void playerHistoryIsPrivateAndOnlyReturnedToItsOwner() {
        String name = username();
        register(name, "correct-horse-battery");
        String ownerAccess = (String) postJson("/api/auth/login",
                Map.of("username", name, "password", "correct-horse-battery")).get("accessToken");

        assertThat(get("/api/me/races", ownerAccess).getStatusCode()).isEqualTo(HttpStatus.OK);

        String otherName = username() + "b";
        register(otherName, "another-good-password");
        String otherAccess = (String) postJson("/api/auth/login",
                Map.of("username", otherName, "password", "another-good-password")).get("accessToken");

        // The public aggregate is readable by anyone...
        assertThat(get("/api/players/" + name).getStatusCode()).isEqualTo(HttpStatus.OK);
        // ...but there is no route that serves another account's history by name. 401 or 404 are both
        // acceptable; what matters is that it is not 200.
        assertThat(get("/api/players/" + name + "/races").getStatusCode()).isNotEqualTo(HttpStatus.OK);
        // And one account can never read another's identity through /api/me.
        assertThat(get("/api/me", otherAccess).getBody()).contains(otherName)
                .doesNotContain("\"username\":\"" + name + "\"");
    }

    @Test
    void roomCreationRequiresAnAccount() {
        assertThat(get("/api/rooms/new").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String name = username();
        register(name, "correct-horse-battery");
        String access = (String) postJson("/api/auth/login",
                Map.of("username", name, "password", "correct-horse-battery")).get("accessToken");

        assertThat(get("/api/rooms/new", access).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ----------------------------------------------------------------- WebSocket upgrade

    @Test
    void anAccountCannotExceedItsLiveSocketCap() throws Exception {
        String name = username() + "cap";
        register(name, "correct-horse-battery");
        String access = (String) postJson("/api/auth/login",
                Map.of("username", name, "password", "correct-horse-battery")).get("accessToken");

        int cap = properties.getAuth().getSocketsPerAccount();
        lastAccessToken.set(access);
        List<Connected> opened = new ArrayList<>();
        for (int i = 0; i < cap; i++) {
            Connected c = connect(access);
            assertThat(c.session()).as("socket " + (i + 1) + " within the cap").isNotNull();
            opened.add(c);
        }

        // One past the cap is refused, so a single account cannot farm ranks across many sockets.
        assertThat(connect(access).session()).as("socket past the cap").isNull();

        // Freeing a slot lets the account back in.
        opened.remove(0).session().close();
        assertThat(awaitSessionIn(15_000)).as("reconnect after a socket closed").isTrue();
    }

    /** Polls until a socket connects, since the server frees the closed slot asynchronously. */
    private boolean awaitSessionIn(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Connected c = connect(lastAccessToken.get());
            if (c.session() != null) {
                c.session().close();
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    @Test
    void websocketUpgradeRequiresAValidToken() throws Exception {
        String name = username() + "ws";
        register(name, "correct-horse-battery");
        Player player = players.findByUsernameKey(name).orElseThrow();
        String access = (String) postJson("/api/auth/login",
                Map.of("username", name, "password", "correct-horse-battery")).get("accessToken");

        // No token at all.
        assertThat(connect(null).session()).as("anonymous socket").isNull();
        // Structurally valid, but signed with a different key.
        JwtService other = new JwtService(otherSecretProperties());
        assertThat(connect(other.issueAccessToken(player)).session()).as("foreign-signed token").isNull();
        // Truncated token.
        assertThat(connect(access.substring(0, access.length() - 4)).session())
                .as("truncated token").isNull();

        // The account's own token gets through. The principal lives on the server-side session, so
        // the only observable proof from here is that the socket stays open and the server greets the
        // connection using the account's own nickname rather than anything the client supplied.
        Connected connected = connect(access);
        assertThat(connected.session()).as("authenticated socket").isNotNull();
        String welcome = connected.welcome().get(10, TimeUnit.SECONDS);
        assertThat(welcome).contains("\"type\":\"welcome\"");
        assertThat(welcome).contains(player.getNickname());
        assertThat(welcome).doesNotContain("\"nickname\":\"spoofed\"");
        connected.session().close();
    }

    private record Connected(WebSocketSession session, CompletableFuture<String> welcome) {
    }

    /**
     * Attempts a real handshake and reports whether the server actually admitted the connection.
     *
     * <p>A refusal is not always visible as a failed upgrade: the server can accept the handshake and
     * then drop the socket, so a live client session alone proves nothing. The {@code welcome} message
     * is sent only after the connection is admitted into the game, which makes it the real signal.
     */
    private Connected connect(String token) throws Exception {
        String url = "ws://localhost:" + port + "/ws/game" + (token == null ? "" : "?token=" + token);
        CompletableFuture<WebSocketSession> connected = new CompletableFuture<>();
        CompletableFuture<String> welcome = new CompletableFuture<>();
        new StandardWebSocketClient().execute(new NoOpHandler(connected, welcome),
                new WebSocketHttpHeaders(new HttpHeaders()), new java.net.URI(url));
        try {
            WebSocketSession session = connected.get(10, TimeUnit.SECONDS);
            // Blocks until the server admits the connection; times out if it never does.
            welcome.get(10, TimeUnit.SECONDS);
            return new Connected(session, welcome);
        } catch (ExecutionException e) {
            // Refused: the container reported a failed handshake.
            return new Connected(null, welcome);
        } catch (java.util.concurrent.TimeoutException e) {
            // Admitted then dropped, or refused without a clean close. Either way, not a player.
            return new Connected(null, welcome);
        }
    }

    private static class NoOpHandler implements WebSocketHandler {
        private final CompletableFuture<WebSocketSession> connected;
        private final CompletableFuture<String> welcome;

        NoOpHandler(CompletableFuture<WebSocketSession> connected, CompletableFuture<String> welcome) {
            this.connected = connected;
            this.welcome = welcome;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            connected.complete(session);
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
            Object payload = message.getPayload();
            String text = payload instanceof byte[] bytes ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                    : payload.toString();
            if (text.contains("\"type\":\"welcome\"")) {
                welcome.complete(text);
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

    

    // ----------------------------------------------------------------- helpers

    private com.typerush.config.GameProperties otherSecretProperties() {
        var props = new com.typerush.config.GameProperties();
        props.getAuth().setJwtSecret("a-completely-different-secret-of-sufficient-length");
        return props;
    }

    private void register(String name, String password) {
        registerRaw(name, password, HttpStatus.CREATED);
    }

    private ResponseEntity<String> registerRaw(String name, String password, HttpStatus expected) {
        ResponseEntity<String> response = postRaw("/api/auth/register",
                Map.of("username", name, "password", password));
        assertThat(response.getStatusCode())
                .withFailMessage(() -> "register " + name + " -> " + response.getStatusCode() + " "
                        + response.getBody())
                .isEqualTo(expected);
        return response;
    }

    private ResponseEntity<String> postRaw(String path, Object body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest().postForEntity(url(path), new HttpEntity<>(body, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postJson(String path, Object body) {
        try {
            var response = postRaw(path, body);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(response.getBody(), Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("auth response was not JSON", e);
        }
    }

    private ResponseEntity<String> get(String path) {
        return get(path, null);
    }

    private ResponseEntity<String> get(String path, String token) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest().exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void purgesExpiredTokens() {
        register(username(), "correct-horse-battery");
        assertThat(revoker.purgeExpired()).isNotNegative();
    }

    /** Returns every response verbatim so tests can assert on 4xx bodies and codes. */
    private static class NoOpErrorHandler implements org.springframework.web.client.ResponseErrorHandler {
        @Override
        public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
            return false;
        }

        @Override
        public void handleError(java.net.URI url, org.springframework.http.HttpMethod method,
                org.springframework.http.client.ClientHttpResponse response) {
        }
    }
}
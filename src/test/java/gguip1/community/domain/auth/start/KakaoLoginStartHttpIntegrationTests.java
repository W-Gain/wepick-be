package gguip1.community.domain.auth.start;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gguip1.community.domain.auth.attempt.LoginAttempt;
import gguip1.community.domain.auth.attempt.LoginAttemptConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 Tomcat Cookie 파싱·HTTP 응답과 독립 MySQL 저장을 함께 확인합니다. 카카오 서버는 호출하지 않습니다. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.kakao.enabled=true", "app.auth.kakao.client-id=http-test-client",
        "app.auth.kakao.redirect-uri=https://wepick.example/api/auth/kakao/callback"
})
class KakaoLoginStartHttpIntegrationTests {
    @Container @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");
    private static final String COOKIE = "__Host-wepick-login-binding";
    private static final String FIRST = "http-attempt-012345678901";
    private static final String SECOND = "http-attempt-012345678902";
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired LoginAttemptConsumer consumer;

    @BeforeEach
    void clearOnlyThisContainersAttempts() { jdbc.update("DELETE FROM login_attempts"); }

    @Test
    @DisplayName("실제 HTTP 준비는 세션·회원·시도를 만들지 않고 시작은 해시와 10분 만료만 저장한다")
    void prepareThenStartPersistsOnlyBoundHashes() throws Exception {
        int usersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        HttpResponse<String> prepared = send("/auth/kakao/prepare", null, "application/json");
        assertThat(prepared.statusCode()).isEqualTo(204);
        assertThat(prepared.body()).isEmpty();
        assertThat(rows()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(usersBefore);
        String cookie = cookie(prepared);
        HttpResponse<String> started = send(startPath(FIRST), cookie, "application/json");
        assertThat(started.statusCode()).isEqualTo(302);
        assertThat(started.body()).isEmpty();
        assertThat(cookie(started)).isEqualTo(cookie);
        assertThat(started.headers().firstValue("Cache-Control")).contains("private, no-store");
        URI location = URI.create(started.headers().firstValue("Location").orElseThrow());
        assertThat(location.getHost()).isEqualTo("kauth.kakao.com");
        Map<String, String> query = KakaoLoginStartControllerTest.decodeQuery(location);
        assertThat(query).containsOnlyKeys("client_id", "redirect_uri", "response_type", "state");
        String state = query.get("state");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM login_attempts");
        assertThat(row.get("state_hash")).isEqualTo(hash(state));
        assertThat(row.get("browser_binding_hash")).isEqualTo(hash(cookie.substring(cookie.indexOf('=') + 1)));
        assertThat(Duration.between((LocalDateTime) row.get("created_at"), (LocalDateTime) row.get("expires_at")))
                .isEqualTo(Duration.ofMinutes(10));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Integer.class)).isZero();
    }

    @Test
    @DisplayName("처음 방문한 두 탭의 무쿠키 start는 모두 400이며 서로 덮어쓸 쿠키나 시도가 없다")
    void simultaneousUnpreparedStartsAreRejectedWithoutCookieRaces() throws Exception {
        List<HttpResponse<String>> responses = parallelStarts(null, FIRST, SECOND);
        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json.readTree(response.body()).at("/error/details/fields/0/code").asText()).isEqualTo("REQUIRED");
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        }
        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName("준비된 두 탭은 같은 쿠키로 동시에 시작해 서로 다른 state를 만들고 역순으로도 한 번씩 소비한다")
    void preparedTabsStartConcurrentlyAndConsumeInReverseOrder() throws Exception {
        String cookie = cookie(send("/auth/kakao/prepare", null, "application/json"));
        List<HttpResponse<String>> responses = parallelStarts(cookie, FIRST, SECOND);
        assertThat(responses).allSatisfy(response -> {
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(cookie(response)).isEqualTo(cookie);
        });
        String firstState = state(responses.get(0));
        String secondState = state(responses.get(1));
        assertThat(firstState).isNotEqualTo(secondState);
        assertThat(rows()).isEqualTo(2);
        String binding = cookie.substring(cookie.indexOf('=') + 1);
        assertThat(consumer.consume(secondState, binding)).contains(new LoginAttempt(SECOND, "/"));
        assertThat(consumer.consume(firstState, binding)).contains(new LoginAttempt(FIRST, "/"));
        assertThat(consumer.consume(firstState, binding)).isEmpty();
    }

    @Test
    @DisplayName("동일 시도의 동시 시작은 302 하나·409 하나이며 반복 오류도 최초 저장 행을 바꾸지 않는다")
    void duplicateStartPreservesTheWinningAttempt() throws Exception {
        String cookie = cookie(send("/auth/kakao/prepare", null, "application/json"));
        List<HttpResponse<String>> responses = parallelStarts(cookie, FIRST, FIRST);
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(302, 409);
        Map<String, Object> original = jdbc.queryForMap("SELECT * FROM login_attempts");
        HttpResponse<String> repeated = send(startPath(FIRST), cookie, "application/json");
        assertThat(repeated.statusCode()).isEqualTo(409);
        assertThat(json.readTree(repeated.body()).at("/error/code").asText()).isEqualTo("ATTEMPT_ALREADY_ACTIVE");
        assertThat(repeated.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(jdbc.queryForMap("SELECT * FROM login_attempts")).isEqualTo(original);
    }

    @Test
    @DisplayName("Tomcat이 파싱하는 원문 중복 쿠키도 prepare와 start 모두 새 쿠키·DB 행 없이 400을 반환한다")
    void rawDuplicateCookieHeadersAreRejectedByTheRealServer() throws Exception {
        String cookie = cookie(send("/auth/kakao/prepare", null, "application/json"));
        for (String duplicate : new String[]{cookie + "; " + cookie, cookie + "; " + COOKIE + "=\"bad\"", cookie + "; " + COOKIE}) {
            for (String path : new String[]{"/auth/kakao/prepare", startPath(FIRST)}) {
                HttpResponse<String> response = send(path, duplicate, "application/json");
                assertThat(response.statusCode()).isEqualTo(400);
                assertThat(json.readTree(response.body()).at("/error/details/fields/0/code").asText()).isEqualTo("INVALID_FORMAT");
                assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
            }
        }
        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName("실제 브라우저 Accept 오류는 안전한 HTML이고 생성 OpenAPI는 상태·필수 query·두 오류 표현을 명시한다")
    void navigationAndGeneratedOpenApiMatchTheHttpContract() throws Exception {
        HttpResponse<String> response = send("/auth/kakao/start", null, "text/html,application/xhtml+xml,*/*;q=0.8");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/html");
        assertThat(response.body()).contains("로그인 안내", "홈으로 돌아가기");
        HttpResponse<String> specification = send("/v3/api-docs", null, "application/json");
        assertThat(specification.statusCode()).isEqualTo(200);
        JsonNode paths = json.readTree(specification.body()).path("paths");
        JsonNode start = paths.path("/auth/kakao/start").path("get");
        assertThat(start.path("responses").has("302")).isTrue();
        for (String code : new String[]{"400", "409", "500"}) {
            assertThat(start.path("responses").path(code).path("content").has("application/json")).isTrue();
            assertThat(start.path("responses").path(code).path("content").has("text/html")).isTrue();
        }
        for (String name : new String[]{"attempt", "returnTo"}) {
            assertThat(start.path("parameters")).anySatisfy(parameter -> {
                assertThat(parameter.path("name").asText()).isEqualTo(name);
                assertThat(parameter.path("in").asText()).isEqualTo("query");
                assertThat(parameter.path("required").asBoolean()).isTrue();
            });
        }
        assertThat(paths.path("/auth/kakao/prepare").path("get").path("responses").has("204")).isTrue();
    }

    // redirect를 따라가지 않아 외부 카카오 호출 없이 저장 완료 시점의 HTTP 결과를 검증합니다.
    private HttpRequest request(String path, String cookie, String accept) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", accept).GET();
        if (cookie != null) builder.header("Cookie", cookie);
        return builder.build();
    }
    private HttpResponse<String> send(String path, String cookie, String accept) throws Exception {
        return client.send(request(path, cookie, accept), HttpResponse.BodyHandlers.ofString());
    }
    private List<HttpResponse<String>> parallelStarts(String cookie, String first, String second) throws Exception {
        var a = client.sendAsync(request(startPath(first), cookie, "application/json"), HttpResponse.BodyHandlers.ofString());
        var b = client.sendAsync(request(startPath(second), cookie, "application/json"), HttpResponse.BodyHandlers.ofString());
        return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
    }
    private static String startPath(String attempt) { return "/auth/kakao/start?attempt=" + attempt + "&returnTo=%2F"; }
    private static String cookie(HttpResponse<String> response) {
        String header = response.headers().firstValue("Set-Cookie").orElseThrow();
        return header.substring(0, header.indexOf(';'));
    }
    private static String state(HttpResponse<String> response) {
        return KakaoLoginStartControllerTest.decodeQuery(URI.create(response.headers().firstValue("Location").orElseThrow())).get("state");
    }
    private int rows() { return jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts", Integer.class); }
    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}

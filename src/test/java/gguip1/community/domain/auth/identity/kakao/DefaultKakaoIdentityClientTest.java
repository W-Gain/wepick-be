package gguip1.community.domain.auth.identity.kakao;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gguip1.community.domain.auth.identity.KakaoIdentityLookupException;
import gguip1.community.domain.auth.start.KakaoAuthorizationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultKakaoIdentityClientTest {
    private static final String FAKE_CODE = "fake-one-time-code";
    private static final String FAKE_STATE = "A".repeat(43);
    private static final String FAKE_SECRET = "fake-client-secret";
    private static final String FAKE_ACCESS_TOKEN = "fake-access-token";

    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicInteger userInfoRequests = new AtomicInteger();
    private final AtomicInteger redirectRequests = new AtomicInteger();
    private final AtomicReference<Integer> tokenStatus = new AtomicReference<>(200);
    private final AtomicReference<String> tokenBody = new AtomicReference<>(tokenSuccessBody());
    private final AtomicReference<Integer> userInfoStatus = new AtomicReference<>(200);
    private final AtomicReference<String> userInfoBody = new AtomicReference<>("{\"id\":123456789}");
    private final AtomicBoolean tokenTimeout = new AtomicBoolean();
    private final AtomicBoolean userInfoTimeout = new AtomicBoolean();
    private final AtomicBoolean tokenIoFailure = new AtomicBoolean();
    private final AtomicBoolean userInfoIoFailure = new AtomicBoolean();
    private final AtomicReference<String> receivedTokenBody = new AtomicReference<>();
    private final AtomicReference<String> receivedTokenPathAndQuery = new AtomicReference<>();
    private final AtomicReference<String> receivedTokenAuthorization = new AtomicReference<>();
    private final AtomicReference<String> receivedUserInfoPathAndQuery = new AtomicReference<>();
    private final AtomicReference<String> receivedUserInfoAuthorization = new AtomicReference<>();
    private final AtomicReference<String> receivedUserInfoContentType = new AtomicReference<>();

    private HttpServer fakeKakaoServer;
    private ExecutorService serverExecutor;
    private CloseableHttpClient httpClient;
    private KakaoAuthorizationProperties properties;
    private DefaultKakaoIdentityClient identityClient;

    @BeforeEach
    void startFakeKakaoServer() throws Exception {
        // 실제 Kakao 호출이나 실키 없이 loopback HTTP로 요청 계약과 실패 경계를 확인합니다.
        fakeKakaoServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        fakeKakaoServer.setExecutor(serverExecutor);
        fakeKakaoServer.createContext("/oauth/token", this::handleTokenRequest);
        fakeKakaoServer.createContext("/v2/user/me", this::handleUserInfoRequest);
        fakeKakaoServer.createContext("/redirect", exchange -> {
            redirectRequests.incrementAndGet();
            respond(exchange, 200, "{}");
        });
        fakeKakaoServer.start();

        KakaoIdentityHttpConfiguration httpConfiguration = new KakaoIdentityHttpConfiguration();
        httpClient = httpConfiguration.kakaoIdentityHttpClient();
        var requestFactory = httpConfiguration.kakaoIdentityRequestFactory(httpClient);
        var restClient = httpConfiguration.kakaoIdentityRestClient(requestFactory);
        var tokenClient = httpConfiguration.kakaoTokenResponseClient(restClient);

        properties = new KakaoAuthorizationProperties();
        properties.setEnabled(true);
        properties.setClientId("fake-rest-client-id");
        properties.setClientSecret(FAKE_SECRET);
        properties.setRedirectUri("https://wepick.example/auth/kakao/callback");
        properties.afterPropertiesSet();

        int port = fakeKakaoServer.getAddress().getPort();
        identityClient = new DefaultKakaoIdentityClient(
                properties,
                tokenClient,
                restClient,
                URI.create("http://127.0.0.1:" + port + "/oauth/token"),
                URI.create("http://127.0.0.1:" + port + "/v2/user/me"));
    }

    @AfterEach
    void stopFakeKakaoServer() throws Exception {
        fakeKakaoServer.stop(0);
        serverExecutor.shutdownNow();
        httpClient.close();
    }

    @Test
    @DisplayName("가짜 Kakao HTTP 서버로 code를 client_secret_post 교환하고 회원번호만 조회한다")
    void exchangesCodeAndReturnsOnlyThePositiveIntegerIdentity() {
        userInfoBody.set("{\"id\":123456789,\"properties\":{\"nickname\":\"must-not-map\"},"
                + "\"kakao_account\":{\"email\":\"must-not-map@example.test\"}}");

        var identity = identityClient.exchangeCodeAndLoadIdentity(FAKE_CODE, FAKE_STATE);

        assertThat(identity.providerUserId()).isEqualTo(123456789L);
        assertThat(identity.toString()).isEqualTo("KakaoIdentity[redacted]");
        assertThat(tokenRequests).hasValue(1);
        assertThat(userInfoRequests).hasValue(1);
        assertThat(redirectRequests).hasValue(0);

        Map<String, String> tokenParameters = decodeForm(receivedTokenBody.get());
        assertThat(tokenParameters)
                .containsEntry("grant_type", "authorization_code")
                .containsEntry("code", FAKE_CODE)
                .containsEntry("redirect_uri", "https://wepick.example/auth/kakao/callback")
                .containsEntry("client_id", "fake-rest-client-id")
                .containsEntry("client_secret", FAKE_SECRET)
                .doesNotContainKey("scope");
        assertThat(receivedTokenAuthorization.get()).isNull();
        assertThat(receivedTokenPathAndQuery.get()).isEqualTo("/oauth/token");

        assertThat(receivedUserInfoAuthorization.get()).isEqualTo("Bearer " + FAKE_ACCESS_TOKEN);
        assertThat(receivedUserInfoContentType.get()).isEqualTo("application/x-www-form-urlencoded");
        Map<String, String> query = decodeQuery(receivedUserInfoPathAndQuery.get());
        assertThat(query).containsOnlyKeys("property_keys").containsEntry("property_keys", "[\"id\"]");
        assertThat(receivedUserInfoPathAndQuery.get()).doesNotContain("email", "profile", "scope", FAKE_ACCESS_TOKEN);
    }

    @Test
    @DisplayName("code 교환 실패는 한 번만 전송하고 일반 예외로 민감 응답을 감춘다")
    void doesNotRetryTokenFailureOrExposeRequestAndResponseDetails() {
        tokenStatus.set(503);
        tokenBody.set("fake-client-secret fake-one-time-code upstream-detail");

        assertGenericFailure();

        assertThat(tokenRequests).hasValue(1);
        assertThat(userInfoRequests).hasValue(0);
        assertThat(redirectRequests).hasValue(0);
    }

    @Test
    @DisplayName("정상 token JSON이 포함된 302도 성공 처리하거나 따라가지 않는다")
    void rejectsTokenRedirectEvenWhenBodyContainsValidTokenJson() {
        tokenStatus.set(302);
        tokenBody.set(tokenSuccessBody());

        assertGenericFailure();

        assertThat(tokenRequests).hasValue(1);
        assertThat(redirectRequests).hasValue(0);
        assertThat(userInfoRequests).hasValue(0);
    }

    @Test
    @DisplayName("운영 Kakao endpoint는 모두 고정 HTTPS 주소다")
    void usesFixedHttpsKakaoEndpoints() {
        assertThat(DefaultKakaoIdentityClient.AUTHORIZATION_ENDPOINT.toString())
                .isEqualTo("https://kauth.kakao.com/oauth/authorize");
        assertThat(DefaultKakaoIdentityClient.TOKEN_ENDPOINT.toString())
                .isEqualTo("https://kauth.kakao.com/oauth/token");
        assertThat(DefaultKakaoIdentityClient.USER_INFO_ENDPOINT.toString())
                .isEqualTo("https://kapi.kakao.com/v2/user/me");
    }

    @Test
    @DisplayName("빈 token 또는 잘못된 token JSON은 안전한 일반 예외로 종료한다")
    void rejectsEmptyOrMalformedTokenResponses() {
        tokenBody.set("{\"access_token\":\"\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        assertGenericFailure();
        assertThat(userInfoRequests).hasValue(0);

        tokenBody.set("{malformed-json");
        assertGenericFailure();
        assertThat(tokenRequests).hasValue(2);
        assertThat(userInfoRequests).hasValue(0);
    }

    @Test
    @DisplayName("정상 회원번호가 포함된 userinfo 302도 성공 처리하거나 따라가지 않는다")
    void rejectsUserInfoRedirectEvenWhenBodyContainsValidIdentityJson() {
        userInfoStatus.set(302);
        userInfoBody.set("{\"id\":123456789}");
        assertGenericFailure();
        assertThat(redirectRequests).hasValue(0);

        userInfoStatus.set(200);
        userInfoBody.set("");
        assertGenericFailure();
        assertThat(userInfoRequests).hasValue(2);
    }

    @Test
    @DisplayName("userinfo HTTP 오류와 JSON 파싱 오류는 원인 없이 고정 예외로 변환한다")
    void hidesUserInfoHttpAndJsonFailures() {
        userInfoStatus.set(500);
        userInfoBody.set("fake-access-token fake-client-secret provider-detail");

        assertGenericFailure();

        assertThat(tokenRequests).hasValue(1);
        assertThat(userInfoRequests).hasValue(1);

        userInfoStatus.set(200);
        userInfoBody.set("{malformed-json");
        assertGenericFailure();
        assertThat(userInfoRequests).hasValue(2);
    }

    @Test
    @DisplayName("누락·문자열·소수·0·음수·Long 초과 회원번호를 거부한다")
    void rejectsMalformedProviderIds() {
        for (String body : Arrays.asList(
                "{}",
                "{\"id\":\"123\"}",
                "{\"id\":1.5}",
                "{\"id\":0}",
                "{\"id\":-1}",
                "{\"id\":9223372036854775808}")) {
            userInfoBody.set(body);
            assertGenericFailure();
        }

        assertThat(tokenRequests).hasValue(6);
        assertThat(userInfoRequests).hasValue(6);
    }

    @Test
    @DisplayName("userinfo HTTP 오류도 자동 재전송하지 않는다")
    void doesNotRetryUserInfoFailure() {
        userInfoStatus.set(429);

        assertGenericFailure();

        assertThat(tokenRequests).hasValue(1);
        assertThat(userInfoRequests).hasValue(1);
    }

    @Test
    @DisplayName("비활성 Kakao 로그인은 외부 호출 없이 거부한다")
    void disabledClientDoesNotMakeExternalCalls() {
        properties.setEnabled(false);

        assertLookupFailure(FAKE_CODE, FAKE_STATE);

        assertThat(tokenRequests).hasValue(0);
        assertThat(userInfoRequests).hasValue(0);
    }

    @Test
    @DisplayName("빈 값·공백·과도한 code는 외부 호출 전에 거부한다")
    void invalidAuthorizationCodeDoesNotMakeExternalCalls() {
        for (String invalidCode : new String[]{null, "", " ", " fake-code", "fake-code ", "x".repeat(2049)}) {
            assertLookupFailure(invalidCode, FAKE_STATE);
        }

        assertThat(tokenRequests).hasValue(0);
        assertThat(userInfoRequests).hasValue(0);
    }

    @Test
    @DisplayName("형식이 잘못된 state는 외부 호출 전에 거부한다")
    void invalidConsumedStateDoesNotMakeExternalCalls() {
        for (String invalidState : new String[]{null, "short-state", "!".repeat(43)}) {
            assertLookupFailure(FAKE_CODE, invalidState);
        }

        assertThat(tokenRequests).hasValue(0);
        assertThat(userInfoRequests).hasValue(0);
    }

    @Test
    @DisplayName("token endpoint 응답 지연은 제한 시간 후 한 번만 실패한다")
    void tokenTimeoutDoesNotRetryOrLeakDetails() {
        tokenTimeout.set(true);

        assertGenericFailure();

        assertThat(tokenRequests).hasValue(1);
        assertThat(userInfoRequests).hasValue(0);
        assertThat(redirectRequests).hasValue(0);
    }

    @Test
    @DisplayName("userinfo 응답 지연은 제한 시간 후 한 번만 실패한다")
    void userInfoTimeoutDoesNotRetryOrLeakDetails() {
        userInfoTimeout.set(true);

        assertGenericFailure();

        assertThat(tokenRequests).hasValue(1);
        assertThat(userInfoRequests).hasValue(1);
        assertThat(redirectRequests).hasValue(0);
    }

    @Test
    @DisplayName("token endpoint 연결이 응답 전에 끊겨도 재시도하거나 세부 정보를 노출하지 않는다")
    void tokenIoFailureDoesNotRetryOrLeakDetails() {
        tokenIoFailure.set(true);

        assertGenericFailure();

        assertThat(tokenRequests).hasValue(1);
        assertThat(userInfoRequests).hasValue(0);
    }

    @Test
    @DisplayName("userinfo 연결이 응답 전에 끊겨도 재시도하거나 세부 정보를 노출하지 않는다")
    void userInfoIoFailureDoesNotRetryOrLeakDetails() {
        userInfoIoFailure.set(true);

        assertGenericFailure();

        assertThat(tokenRequests).hasValue(1);
        assertThat(userInfoRequests).hasValue(1);
    }

    @Test
    @DisplayName("외부 연결 실패는 원인 없이 일반 예외로 변환한다")
    void hidesNetworkFailureWithoutRetry() {
        fakeKakaoServer.stop(0);

        assertGenericFailure();

        assertThat(tokenRequests).hasValue(0);
        assertThat(userInfoRequests).hasValue(0);
    }

    @Test
    @DisplayName("Secret 누락은 외부 요청 전에 거부하지만 client secret은 시작 설정을 막지 않는다")
    void failsClosedBeforeNetworkWhenClientSecretIsMissing() {
        properties.setClientSecret(null);

        assertGenericFailure();

        assertThat(tokenRequests).hasValue(0);
        assertThat(userInfoRequests).hasValue(0);
    }

    private void assertLookupFailure(String code, String state) {
        assertThatThrownBy(() -> identityClient.exchangeCodeAndLoadIdentity(code, state))
                .isExactlyInstanceOf(KakaoIdentityLookupException.class)
                .hasMessage("Kakao identity lookup failed")
                .hasNoCause();
    }

    private void assertGenericFailure() {
        assertThatThrownBy(() -> identityClient.exchangeCodeAndLoadIdentity(FAKE_CODE, FAKE_STATE))
                .isExactlyInstanceOf(KakaoIdentityLookupException.class)
                .hasMessage("Kakao identity lookup failed")
                .hasNoCause()
                .hasMessageNotContaining(FAKE_CODE)
                .hasMessageNotContaining(FAKE_SECRET)
                .hasMessageNotContaining(FAKE_ACCESS_TOKEN)
                .hasMessageNotContaining("provider-detail")
                .hasMessageNotContaining("upstream-detail");
    }

    private void handleTokenRequest(HttpExchange exchange) throws IOException {
        tokenRequests.incrementAndGet();
        receivedTokenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        receivedTokenPathAndQuery.set(exchange.getRequestURI().toString());
        receivedTokenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        if (tokenIoFailure.get()) {
            exchange.close();
            return;
        }
        if (tokenTimeout.get()) {
            delayBeyondClientReadTimeout(exchange, tokenStatus.get(), tokenBody.get());
            return;
        }
        respond(exchange, tokenStatus.get(), tokenBody.get());
    }

    private void handleUserInfoRequest(HttpExchange exchange) throws IOException {
        userInfoRequests.incrementAndGet();
        receivedUserInfoPathAndQuery.set(exchange.getRequestURI().toString());
        receivedUserInfoAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        receivedUserInfoContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        if (userInfoIoFailure.get()) {
            exchange.close();
            return;
        }
        if (userInfoTimeout.get()) {
            delayBeyondClientReadTimeout(exchange, userInfoStatus.get(), userInfoBody.get());
            return;
        }
        respond(exchange, userInfoStatus.get(), userInfoBody.get());
    }

    private void delayBeyondClientReadTimeout(HttpExchange exchange, int status, String body) {
        // 운영 client의 5초 read timeout을 넘겨 지연 실패가 실제로 적용되는지 검증합니다.
        try {
            TimeUnit.SECONDS.sleep(6);
            respond(exchange, status, body);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            exchange.close();
        } catch (IOException ignored) {
            // 클라이언트가 timeout으로 연결을 닫은 뒤의 응답 실패는 테스트 fixture에서만 무시합니다.
            exchange.close();
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        if (status == 302) {
            exchange.getResponseHeaders().add("Location", "/redirect");
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String tokenSuccessBody() {
        return "{\"access_token\":\"" + FAKE_ACCESS_TOKEN + "\",\"token_type\":\"bearer\",\"expires_in\":3600}";
    }

    private static Map<String, String> decodeForm(String form) {
        return Arrays.stream(form.split("&"))
                .map(DefaultKakaoIdentityClientTest::decodePair)
                .collect(LinkedHashMap::new, (result, entry) -> result.put(entry[0], entry[1]), Map::putAll);
    }

    private static Map<String, String> decodeQuery(String pathAndQuery) {
        int queryStart = pathAndQuery.indexOf('?');
        assertThat(queryStart).isGreaterThanOrEqualTo(0);
        return Arrays.stream(pathAndQuery.substring(queryStart + 1).split("&"))
                .map(DefaultKakaoIdentityClientTest::decodePair)
                .collect(LinkedHashMap::new, (result, entry) -> result.put(entry[0], entry[1]), Map::putAll);
    }

    private static String[] decodePair(String parameter) {
        String[] pair = parameter.split("=", 2);
        return new String[]{
                URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                URLDecoder.decode(pair.length == 1 ? "" : pair[1], StandardCharsets.UTF_8)};
    }

}

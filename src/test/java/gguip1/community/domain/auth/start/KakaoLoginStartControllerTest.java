package gguip1.community.domain.auth.start;

import gguip1.community.domain.auth.attempt.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP 경계의 상태·쿠키·오류 표현과 저장 전 거부를 검증합니다. 실제 DB는 통합 테스트에서 확인합니다. */
class KakaoLoginStartControllerTest {
    private static final String COOKIE = "__Host-wepick-login-binding";
    private static final String BINDING = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final String ATTEMPT = "attempt-01234567890123456789";
    private LoginAttemptStore store;
    private KakaoAuthorizationProperties properties;
    private MockMvc mvc;

    @BeforeEach
    void configureHttpBoundary() {
        store = mock(LoginAttemptStore.class);
        when(store.create(any())).thenReturn(LoginAttemptCreationResult.CREATED);
        properties = new KakaoAuthorizationProperties();
        properties.setEnabled(true);
        properties.setClientId("test-public-client&key");
        properties.setRedirectUri("https://wepick.example/api/auth/kakao/callback?fixed=a+b%26");
        properties.afterPropertiesSet();
        LoginAttemptInputValidator validator = new LoginAttemptInputValidator();
        SecureRandom random = new SecureRandom();
        mvc = MockMvcBuilders.standaloneSetup(new KakaoLoginStartController(properties,
                        new KakaoAuthorizationUrlBuilder(properties),
                        new LoginBrowserBindingCookie(properties, validator, random), validator,
                        new LoginAttemptStarter(store, validator, Clock.systemUTC(), random)))
                .setControllerAdvice(new LoginStartErrorAdvice()).build();
    }

    @Test
    @DisplayName("쿠키 준비는 HttpOnly·Secure·Lax·10분 쿠키만 발급하고 로그인 시도를 저장하지 않는다")
    void preparationCreatesOnlyABindingCookie() throws Exception {
        MvcResult result = mvc.perform(get("/auth/kakao/prepare"))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().doesNotExist("Location")).andReturn();
        String header = result.getResponse().getHeader("Set-Cookie");
        assertThat(header).startsWith(COOKIE + "=").contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/", "Max-Age=600")
                .doesNotContain("Domain=", "JSESSIONID", "SESSION");
        new LoginAttemptInputValidator().validateBrowserBinding(cookieValue(header));
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("준비는 유효 확인 값을 유지하고 단일 잘못된 값만 새 난수로 바꾼다")
    void preparationReusesValidAndReplacesMalformedSingleCookie() throws Exception {
        String reused = mvc.perform(get("/auth/kakao/prepare").cookie(new Cookie(COOKIE, BINDING)))
                .andExpect(status().isNoContent()).andReturn().getResponse().getHeader("Set-Cookie");
        assertThat(cookieValue(reused)).isEqualTo(BINDING);
        String replaced = mvc.perform(get("/auth/kakao/prepare").cookie(new Cookie(COOKIE, "invalid")))
                .andExpect(status().isNoContent()).andReturn().getResponse().getHeader("Set-Cookie");
        assertThat(cookieValue(replaced)).isNotEqualTo("invalid");
        new LoginAttemptInputValidator().validateBrowserBinding(cookieValue(replaced));
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("같은 이름의 확인 쿠키가 중복되면 prepare와 start 모두 회전·저장 없이 거부한다")
    void duplicateCookiesAreNeverSelectedOrRotated() throws Exception {
        for (String path : new String[]{"/auth/kakao/prepare", "/auth/kakao/start"}) {
            mvc.perform(get(path).param("attempt", ATTEMPT).param("returnTo", "/")
                            .cookie(new Cookie(COOKIE, BINDING), new Cookie(COOKIE, BINDING)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.fields[0].field").value("browserBinding"))
                    .andExpect(jsonPath("$.error.details.fields[0].code").value("INVALID_FORMAT"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
        // 컨테이너가 잘못된 값을 버리고 유효값만 넘겨도 원문 헤더의 중복을 검출합니다.
        // MockMvc의 cookie()가 원문 헤더를 재작성하므로 빌드 후 두 표현을 각각 지정합니다.
        mvc.perform(get("/auth/kakao/prepare").with(request -> {
                    request.setCookies(new Cookie(COOKIE, BINDING));
                    request.removeHeader("Cookie");
                    request.addHeader("Cookie", COOKIE + "=" + BINDING + "; " + COOKIE + "=\"bad\"");
                    return request;
                }))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("시작은 준비된 쿠키가 없거나 잘못됐으면 새 쿠키·시도 없이 400을 반환한다")
    void startRequiresPreparedCanonicalCookie() throws Exception {
        mvc.perform(get("/auth/kakao/start").param("attempt", ATTEMPT).param("returnTo", "/"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details.fields[0].code").value("REQUIRED"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/auth/kakao/start").param("attempt", ATTEMPT).param("returnTo", "/")
                        .cookie(new Cookie(COOKIE, "wrong")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details.fields[0].code").value("INVALID_FORMAT"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("입력 누락·길이·중복 query·위험 복귀 경로는 공개 필드 코드만 반환하고 저장하지 않는다")
    void validationReturnsOnlyPublicFieldCodes() throws Exception {
        mvc.perform(get("/auth/kakao/start").param("returnTo", "/"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details.fields[0].field").value("attempt"))
                .andExpect(jsonPath("$.error.details.fields[0].code").value("REQUIRED"));
        mvc.perform(get("/auth/kakao/start").param("attempt", "x".repeat(65)).param("returnTo", "/"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details.fields[0].code").value("TOO_LONG"));
        mvc.perform(get("/auth/kakao/start").param("attempt", ATTEMPT, ATTEMPT).param("returnTo", "/"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details.fields[0].code").value("INVALID_FORMAT"));
        for (String unsafe : new String[]{"//evil.example", "/%252f%252fevil", "/bad\\path", "/%0d%0aevil"}) {
            mvc.perform(get("/auth/kakao/start").param("attempt", ATTEMPT).param("returnTo", unsafe))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details.fields[0].field").value("returnTo"))
                    .andExpect(header().doesNotExist("Location")).andExpect(header().doesNotExist("Set-Cookie"));
        }
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("저장 성공만 고정 카카오 주소로 이동하며 state 외 복귀값·브라우저 확인 원문을 전달하지 않는다")
    void onlyPersistedStateIsSentToTheFixedAuthorizationUrl() throws Exception {
        String returnTo = "/picks/12?from=a+b&tag=%26#reason";
        MvcResult result = mvc.perform(get("/auth/kakao/start").param("attempt", ATTEMPT).param("returnTo", returnTo)
                        .cookie(new Cookie(COOKIE, BINDING)).header("Host", "evil.example").header("X-Forwarded-Host", "evil.example"))
                .andExpect(status().isFound()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer")).andReturn();
        URI location = URI.create(result.getResponse().getHeader("Location"));
        Map<String, String> query = decodeQuery(location);
        assertThat(location.getScheme()).isEqualTo("https");
        assertThat(location.getHost()).isEqualTo("kauth.kakao.com");
        assertThat(location.getPath()).isEqualTo("/oauth/authorize");
        assertThat(query).containsOnlyKeys("client_id", "redirect_uri", "response_type", "state");
        assertThat(query.get("redirect_uri")).isEqualTo(properties.getRedirectUri());
        assertThat(query.get("client_id")).isEqualTo(properties.getClientId());
        assertThat(query.get("state")).matches("[A-Za-z0-9_-]{43}");
        assertThat(cookieValue(result.getResponse().getHeader("Set-Cookie"))).isEqualTo(BINDING);
        verify(store).create(argThat(draft -> draft.clientAttemptId().equals(ATTEMPT)
                && draft.returnTo().equals(returnTo) && !draft.stateHash().equals(query.get("state"))));
    }

    @Test
    @DisplayName("활성 중복 409와 저장소 실패 500은 새 쿠키·redirect·원인 원문을 반환하지 않는다")
    void conflictsAndFailuresNeverReturnNewCookieOrSensitiveData() throws Exception {
        when(store.create(any())).thenReturn(LoginAttemptCreationResult.ACTIVE_ATTEMPT_EXISTS);
        mvc.perform(get("/auth/kakao/start").param("attempt", ATTEMPT).param("returnTo", "/").cookie(new Cookie(COOKIE, BINDING)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ATTEMPT_ALREADY_ACTIVE"))
                .andExpect(header().doesNotExist("Set-Cookie")).andExpect(header().doesNotExist("Location"));
        when(store.create(any())).thenThrow(new IllegalStateException("private-state-secret-config"));
        String body = mvc.perform(get("/auth/kakao/start").param("attempt", ATTEMPT).param("returnTo", "/").cookie(new Cookie(COOKIE, BINDING)))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(header().doesNotExist("Set-Cookie")).andExpect(header().doesNotExist("Location"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("private-state-secret-config", BINDING, ATTEMPT, properties.getClientId());
    }

    @Test
    @DisplayName("브라우저 HTML 오류 안내는 같은 상태를 유지하고 입력 원문 대신 고정 홈 링크만 표시한다")
    void navigationGetsStaticSafeHtmlAtTheSameStatus() throws Exception {
        String html = mvc.perform(get("/auth/kakao/start").param("attempt", "<script>private</script>")
                        .param("returnTo", "//evil.example").accept("text/html,application/xhtml+xml,*/*;q=0.8"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(header().string("Vary", "Accept")).andExpect(header().exists("Content-Security-Policy"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("로그인 안내", "홈으로 돌아가기", "href=\"/\"")
                .doesNotContain("private", "<script>", "evil.example", "http-equiv");
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("Accept의 JSON 우선·동점·HTML q=0·와일드카드·잘못된 헤더는 JSON을 유지한다")
    void contentNegotiationRespectsExplicitQualityAndDefaultsToJson() throws Exception {
        for (String accept : new String[]{"*/*", "application/json,text/html", "text/html;q=0,*/*;q=1",
                "application/json;q=1,text/html;q=0.5", "malformed"}) {
            mvc.perform(get("/auth/kakao/start").header("Accept", accept))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/json"))
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(get("/auth/kakao/start").accept("application/json;q=0,text/html;q=0.5"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    @Test
    @DisplayName("로그인 기능이 꺼져 있으면 준비와 시작은 저장 없이 일반 500을 반환한다")
    void disabledAuthorizationFailsBeforeAnyWrite() throws Exception {
        properties.setEnabled(false);
        mvc.perform(get("/auth/kakao/prepare"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/auth/kakao/start").param("attempt", ATTEMPT).param("returnTo", "/").cookie(new Cookie(COOKIE, BINDING)))
                .andExpect(status().isInternalServerError()).andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(store);
    }

    static String cookieValue(String header) {
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }

    @Test @DisplayName("attempt 64자는 허용하지만 빈 값·짧은 형식·returnTo 513자는 저장 전에 정확한 필드 오류로 거부한다")
    void queryBoundaryRejectsInvalidFormatsBeforeCookieAndStorage() throws Exception {
        mvc.perform(get("/auth/kakao/start").param("attempt", "a".repeat(64)).param("returnTo", "/")
                        .cookie(new Cookie(COOKIE, BINDING))).andExpect(status().isFound());
        clearInvocations(store);
        for (String invalid : new String[]{"", "short"}) {
            mvc.perform(get("/auth/kakao/start").param("attempt", invalid).param("returnTo", "/").cookie(new Cookie(COOKIE, BINDING)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details.fields[0].field").value("attempt"));
        }
        mvc.perform(get("/auth/kakao/start").param("attempt", ATTEMPT).param("returnTo", "/" + "a".repeat(512)).cookie(new Cookie(COOKIE, BINDING)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details.fields[0].code").value("TOO_LONG"));
        verifyNoInteractions(store);
    }

    @Test @DisplayName("Accept가 없거나 text 와일드카드만 있으면 JSON이고 명시적 HTML은 구체적인 JSON q보다 우선할 때만 선택한다")
    void acceptSpecificityControlsErrorRepresentation() throws Exception {
        mvc.perform(get("/auth/kakao/start")).andExpect(content().contentTypeCompatibleWith("application/json"));
        for (String accept : new String[]{"", "text/*", "text/*;q=1,application/json;q=0.5", "text/html;q=0.5,application/*;q=1,application/json;q=0.9"}) {
            mvc.perform(get("/auth/kakao/start").header("Accept", accept))
                    .andExpect(content().contentTypeCompatibleWith("application/json"));
        }
        for (String accept : new String[]{"text/html;q=0.9,application/*;q=1,application/json;q=0.1", "text/html;q=0.9,text/html;q=0.1,application/json;q=0.5"}) {
            mvc.perform(get("/auth/kakao/start").header("Accept", accept))
                    .andExpect(content().contentTypeCompatibleWith("text/html"));
        }
    }

    static Map<String, String> decodeQuery(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&")).map(part -> part.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
}

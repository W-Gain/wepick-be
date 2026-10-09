package gguip1.community.domain.auth.start;

import gguip1.community.domain.auth.attempt.LoginAttemptInputValidator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 쿠키 파서가 원문을 정리·누락하는 경우와 실제 발급 값의 독립성을 확인합니다. */
class LoginBrowserBindingCookieTest {
    private static final String NAME = "__Host-wepick-login-binding";
    private static final String VALUE = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private final LoginAttemptInputValidator validator = new LoginAttemptInputValidator();
    private final LoginBrowserBindingCookie cookies = new LoginBrowserBindingCookie(new KakaoAuthorizationProperties(), validator, new SecureRandom());

    @Test @DisplayName("서로 다른 첫 준비는 32바이트 확인 값을 새로 만들고 고정된 영값을 발급하지 않는다")
    void preparationsHaveIndependentRandomValues() {
        String first = cookies.prepare(new MockHttpServletRequest());
        String second = cookies.prepare(new MockHttpServletRequest());
        validator.validateBrowserBinding(first);
        validator.validateBrowserBinding(second);
        assertThat(first).isNotEqualTo(VALUE).isNotEqualTo(second);
        assertThat(Base64.getUrlDecoder().decode(first)).hasSize(32);
    }

    @Test @DisplayName("원문 헤더의 정확히 한 쿠키는 유지하고 다른 이름·세미콜론의 영향을 받지 않는다")
    void acceptsOneCanonicalValueAmongUnrelatedCookies() {
        MockHttpServletRequest request = raw("other=x; " + NAME + "=" + VALUE + "; flag; last=z", VALUE);
        assertThat(cookies.requirePrepared(request)).isEqualTo(VALUE);
        assertThat(cookies.prepare(request)).isEqualTo(VALUE);
    }

    @Test @DisplayName("원문 중복은 파서가 한 값만 남겨도 첫 값이나 마지막 값으로 선택하지 않는다")
    void rawDuplicatesRemainInvalidWhenContainerDropsOneValue() {
        for (String header : new String[]{NAME + "=" + VALUE + "; " + NAME + "=bad", NAME + "=" + VALUE + "; " + NAME}) {
            assertThatThrownBy(() -> cookies.prepare(raw(header, VALUE)))
                    .isInstanceOf(LoginStartValidationException.class);
        }
    }

    @Test @DisplayName("원문과 파서 값 불일치·파서 누락·빈 값은 start에서 거부하고 prepare에서만 교체한다")
    void ambiguousSingleValuesAreNeverUsedForStart() {
        for (MockHttpServletRequest request : new MockHttpServletRequest[]{
                raw(NAME + "=\"" + VALUE + "\"", VALUE), raw(NAME + "=" + VALUE, null), raw(NAME, VALUE)}) {
            assertThatThrownBy(() -> cookies.requirePrepared(request)).isInstanceOf(LoginStartValidationException.class);
            String replacement = cookies.prepare(request);
            validator.validateBrowserBinding(replacement);
            assertThat(replacement).isNotEqualTo(VALUE);
        }
    }

    @Test @DisplayName("명시적 로컬 HTTP 설정은 별도 이름·HttpOnly·Lax·600초를 유지하며 Secure를 넣지 않는다")
    void loopbackUsesSeparateNonSecureCookie() {
        var local = new KakaoAuthorizationProperties();
        local.setEnabled(true); local.setClientId("local-test");
        local.setRedirectUri("http://127.0.0.1:5173/api/auth/kakao/callback");
        local.setCookieSecure(false); local.setAllowInsecureLoopbackCookie(true); local.afterPropertiesSet();
        assertThat(local.isAllowInsecureLoopbackCookie()).isTrue();
        assertThat(new LoginBrowserBindingCookie(local, validator, new SecureRandom()).renewedHeader(VALUE))
                .startsWith("wepick-login-binding-local=").contains("HttpOnly", "SameSite=Lax", "Max-Age=600", "Path=/")
                .doesNotContain("Secure", "Domain=");
    }

    private static MockHttpServletRequest raw(String header, String parsedValue) {
        var request = new MockHttpServletRequest();
        if (parsedValue != null) request.setCookies(new Cookie(NAME, parsedValue));
        request.removeHeader("Cookie"); request.addHeader("Cookie", header);
        return request;
    }
}

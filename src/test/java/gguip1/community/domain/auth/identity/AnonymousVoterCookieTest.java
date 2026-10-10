package gguip1.community.domain.auth.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AnonymousVoterCookieTest {
    @Test
    @DisplayName("운영 Secure 쿠키와 HTTP 로컬 쿠키는 이름을 분리한다")
    void selectsSeparateProductionAndLocalNames() {
        assertThat(AnonymousVoterCookie.name(true)).isEqualTo("__Host-wepick-anon");
        assertThat(AnonymousVoterCookie.name(false)).isEqualTo("wepick-anon-local");
    }

    @Test
    @DisplayName("정규 32바이트 토큰만 해시하고 잘못된 값은 무시한다")
    void acceptsOnlyCanonicalOpaqueToken() {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

        assertThat(AnonymousVoterCookie.hashIfValid(token)).hasSize(64);
        assertThat(AnonymousVoterCookie.hashIfValid(null)).isNull();
        assertThat(AnonymousVoterCookie.hashIfValid("malformed")).isNull();
        assertThat(AnonymousVoterCookie.hashIfValid(token + "=")).isNull();
        assertThat(AnonymousVoterCookie.hashIfValid(token.substring(0, 42) + "B")).isNull();
    }
}

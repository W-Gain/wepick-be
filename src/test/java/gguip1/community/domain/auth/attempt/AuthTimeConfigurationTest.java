package gguip1.community.domain.auth.attempt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AuthTimeConfigurationTest {
    @Test
    @DisplayName("인증 시도용 Clock은 UTC이고 state 생성기는 SecureRandom이다")
    void providesUtcClockAndSecureRandom() {
        AuthTimeConfiguration configuration = new AuthTimeConfiguration();

        assertThat(configuration.authClock().getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(configuration.authSecureRandom()).isInstanceOf(SecureRandom.class);
    }
}

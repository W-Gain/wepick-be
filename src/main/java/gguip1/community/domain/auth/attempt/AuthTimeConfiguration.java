package gguip1.community.domain.auth.attempt;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Clock;

/** 인증 시도에 사용하는 UTC Clock과 암호학적 난수 생성기를 제공합니다. */
@Configuration
public class AuthTimeConfiguration {
    /** DB 경계와 일치하도록 UTC 기준 Clock을 만듭니다. */
    @Bean
    public Clock authClock() {
        return Clock.systemUTC();
    }

    /** state 원문 생성에 쓸 운영 SecureRandom을 제공합니다. */
    @Bean
    public SecureRandom authSecureRandom() {
        return new SecureRandom();
    }
}

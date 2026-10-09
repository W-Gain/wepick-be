package gguip1.community.domain.auth.attempt;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AuthTimeConfiguration {
    @Bean
    public Clock authClock() {
        return Clock.systemUTC();
    }
}

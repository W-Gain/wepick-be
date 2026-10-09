package gguip1.community.domain.auth.attempt;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** 앱 Clock 한 번으로 생성·소비 시각을 UTC 마이크로초 정밀도로 맞춥니다. */
final class LoginAttemptTime {
    private LoginAttemptTime() {
    }

    /** 지정된 Clock에서 한 번 읽어 UTC LocalDateTime으로 자릅니다. */
    static LocalDateTime nowUtc(Clock clock) {
        return LocalDateTime.ofInstant(
                Objects.requireNonNull(clock, "clock").instant().truncatedTo(ChronoUnit.MICROS),
                ZoneOffset.UTC);
    }
}

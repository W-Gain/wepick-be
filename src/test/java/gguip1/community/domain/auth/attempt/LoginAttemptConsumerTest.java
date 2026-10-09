package gguip1.community.domain.auth.attempt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LoginAttemptConsumerTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void missingStateFailsWithoutConsumingAnything(String state) {
        LoginAttemptStore store = mock(LoginAttemptStore.class);
        Clock clock = mock(Clock.class);
        assertThat(new LoginAttemptConsumer(store, clock).consume(state, "binding"))
                .isEmpty();
        verifyNoInteractions(store, clock);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void missingBindingFailsWithoutConsumingAnything(String binding) {
        LoginAttemptStore store = mock(LoginAttemptStore.class);
        Clock clock = mock(Clock.class);
        assertThat(new LoginAttemptConsumer(store, clock).consume("state", binding))
                .isEmpty();
        verifyNoInteractions(store, clock);
    }

    @Test
    void rejectedAttemptRemainsAFailure() {
        LoginAttemptStore store = mock(LoginAttemptStore.class);
        when(store.consume(any(), any(), any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        assertThat(new LoginAttemptConsumer(store, clock).consume("state", "binding")).isEmpty();
    }

    @Test
    void storageFailureIsNotHiddenAsAnInvalidAttempt() {
        LoginAttemptStore store = mock(LoginAttemptStore.class);
        var failure = new DataAccessResourceFailureException("storage unavailable");
        when(store.consume(any(), any(), any())).thenThrow(failure);
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC);
        assertThatThrownBy(() -> new LoginAttemptConsumer(store, clock).consume("state", "binding"))
                .isSameAs(failure);
    }

    @Test
    void hashesRawInputsAndPassesOneUtcMicrosecondTimestampToStore() {
        LoginAttemptStore store = mock(LoginAttemptStore.class);
        Clock clock = mock(Clock.class);
        Instant appNow = Instant.parse("2026-10-09T12:34:56.123456789Z");
        when(clock.instant()).thenReturn(appNow);
        LoginAttempt accepted = new LoginAttempt("attempt-1", "/topics");
        when(store.consume(
                "f8fb559306134eeeacc6a3ec137868c5d36f34a7d150340c784dae289d00aac9",
                "0bb001e614432270361e93b579871b367eb1442846bbab4ca545af0bb63adf11",
                LocalDateTime.of(2026, 10, 9, 12, 34, 56, 123_456_000)))
                .thenReturn(Optional.of(accepted));
        LoginAttemptConsumer consumer = new LoginAttemptConsumer(store, clock);

        assertThat(consumer.consume("raw-state-é", "browser-cookie-value"))
                .contains(accepted);
        verify(clock).instant();
        verify(store).consume(
                "f8fb559306134eeeacc6a3ec137868c5d36f34a7d150340c784dae289d00aac9",
                "0bb001e614432270361e93b579871b367eb1442846bbab4ca545af0bb63adf11",
                LocalDateTime.of(2026, 10, 9, 12, 34, 56, 123_456_000));
    }
}

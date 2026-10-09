package gguip1.community.domain.auth.attempt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class LoginAttemptStarterTest {
    private static final String BINDING = "A".repeat(43);
    private static final String ATTEMPT_ID = "f8c8fc69-5b88-4f14-8f30-a0e7df481bb0";

    @Test
    @DisplayName("새 시도는 32바이트 state를 해시 저장하고 UTC 10분 만료 시각을 저장한다")
    void createsAHashedOneTimeStateWithUtcMicrosecondExpiry() {
        LoginAttemptStore store = mock(LoginAttemptStore.class);
        when(store.create(any())).thenReturn(LoginAttemptCreationResult.CREATED);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-09T12:34:56.123456789Z"));
        FixedSecureRandom secureRandom = new FixedSecureRandom();
        LoginAttemptStarter starter = new LoginAttemptStarter(
                store, new LoginAttemptInputValidator(), clock, secureRandom);

        LoginAttemptStartResult result = starter.start(
                BINDING, ATTEMPT_ID, "/picks/1?x=a+b&value=%26#summary");

        assertThat(result.status()).isEqualTo(LoginAttemptCreationResult.CREATED);
        assertThat(result.state()).contains("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");
        assertThat(result.toString()).isEqualTo("LoginAttemptStartResult[status=CREATED, state=<redacted>]");
        assertThat(result.toString()).doesNotContain(result.state().orElseThrow(), BINDING);
        assertThat(secureRandom.calls).isEqualTo(1);
        assertThat(secureRandom.lastRequestedLength).isEqualTo(32);

        ArgumentCaptor<LoginAttemptDraft> draftCaptor = ArgumentCaptor.forClass(LoginAttemptDraft.class);
        verify(store).create(draftCaptor.capture());
        LoginAttemptDraft draft = draftCaptor.getValue();
        assertThat(draft.stateHash()).isEqualTo("ea866a757e4c38babfa8127cbe9a409d3e1f93a00ff1488ff735fcf917afffd0");
        assertThat(draft.browserBindingHash()).isEqualTo("0f007385b6f9d4b7eeb2748605afe1a984a0a3bfa3f014d09e2a784ce9e5cd1a");
        assertThat(draft.clientAttemptId()).isEqualTo(ATTEMPT_ID);
        assertThat(draft.returnTo()).isEqualTo("/picks/1?x=a+b&value=%26#summary");
        assertThat(draft.createdAt()).isEqualTo(LocalDateTime.of(2026, 10, 9, 12, 34, 56, 123_456_000));
        assertThat(draft.expiresAt()).isEqualTo(LocalDateTime.of(2026, 10, 9, 12, 44, 56, 123_456_000));
        verify(clock, times(1)).instant();
    }

    @Test
    @DisplayName("활성 중복 시도는 새 state를 노출하지 않는다")
    void doesNotExposeStateWhenTheAttemptIdIsAlreadyActive() {
        LoginAttemptStore store = mock(LoginAttemptStore.class);
        when(store.create(any())).thenReturn(LoginAttemptCreationResult.ACTIVE_ATTEMPT_EXISTS);
        LoginAttemptStarter starter = new LoginAttemptStarter(
                store, new LoginAttemptInputValidator(), Clock.systemUTC(), new FixedSecureRandom());

        LoginAttemptStartResult result = starter.start(BINDING, ATTEMPT_ID, "/picks/1");

        assertThat(result.status()).isEqualTo(LoginAttemptCreationResult.ACTIVE_ATTEMPT_EXISTS);
        assertThat(result.state()).isEmpty();
        assertThat(result.toString()).doesNotContain(BINDING);
    }

    @Test
    @DisplayName("binding 형식 오류는 Clock·난수·저장소 접근 전에 거부한다")
    void invalidBindingFailsBeforeReadingTimeGeneratingStateOrCallingStore() {
        LoginAttemptStore store = mock(LoginAttemptStore.class);
        Clock clock = mock(Clock.class);
        SecureRandom secureRandom = mock(SecureRandom.class);
        LoginAttemptStarter starter = new LoginAttemptStarter(
                store, new LoginAttemptInputValidator(), clock, secureRandom);

        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> starter.start("invalid", ATTEMPT_ID, "/picks/1"));

        verifyNoInteractions(store, clock, secureRandom);
    }

    @Test
    @DisplayName("저장소 장애를 활성 중복으로 숨기지 않고 전파한다")
    void storageFailurePropagatesWithoutBecomingAnActiveDuplicate() {
        LoginAttemptStore store = mock(LoginAttemptStore.class);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("storage unavailable");
        when(store.create(any())).thenThrow(failure);
        LoginAttemptStarter starter = new LoginAttemptStarter(
                store, new LoginAttemptInputValidator(), Clock.systemUTC(), new FixedSecureRandom());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> starter.start(BINDING, ATTEMPT_ID, "/picks/1"))
                .isSameAs(failure);
    }

    private static final class FixedSecureRandom extends SecureRandom {
        private int calls;
        private int lastRequestedLength;

        @Override
        public void nextBytes(byte[] bytes) {
            calls++;
            lastRequestedLength = bytes.length;
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) index;
            }
        }
    }
}

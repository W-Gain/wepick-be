package gguip1.community.domain.auth.callback;

import gguip1.community.domain.auth.attempt.LoginAttempt;
import gguip1.community.domain.auth.attempt.LoginAttemptConsumer;
import gguip1.community.domain.auth.attempt.LoginAttemptInputValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KakaoLoginCallbackCoordinatorTest {
    private static final String STATE = "S".repeat(43);
    private static final String BINDING = "A".repeat(43);
    private static final String CODE = "fake-authorization-code";

    private final LoginAttemptConsumer attempts = mock(LoginAttemptConsumer.class);
    private final KakaoLoginCallbackCoordinator coordinator = new KakaoLoginCallbackCoordinator(
            attempts, new LoginAttemptInputValidator());
    private final LoginAttempt consumedAttempt = new LoginAttempt("A".repeat(22), "/profile?tab=history");

    @BeforeEach
    void returnAnAlreadyCommittedAttemptWhenStateMatches() {
        when(attempts.consume(STATE, BINDING)).thenReturn(Optional.of(consumedAttempt));
    }

    @Test
    @DisplayName("유효한 code·state·browser binding은 먼저 시도 소비 후 다음 단계로 전달한다")
    void consumesAttemptBeforeReturningAuthorizationCode() {
        KakaoLoginCallbackDecision decision = coordinator.consume(
                new String[]{STATE}, BINDING, new String[]{CODE}, null).orElseThrow();

        assertThat(decision.outcome()).isEqualTo(KakaoLoginCallbackDecision.Outcome.AUTHORIZATION_CODE);
        assertThat(decision.attempt()).isEqualTo(consumedAttempt);
        assertThat(decision.authorizationCode()).isEqualTo(CODE);
        assertThat(decision.consumedState()).isEqualTo(STATE);
        assertThat(decision.toString()).doesNotContain(CODE, STATE, BINDING);
        verify(attempts).consume(STATE, BINDING);
    }

    @Test
    @DisplayName("카카오 사용 취소도 state를 소비한 뒤 cancelled로 분류한다")
    void consumesAttemptBeforeClassifyingAccessDenied() {
        KakaoLoginCallbackDecision decision = coordinator.consume(
                new String[]{STATE}, BINDING, null, new String[]{"access_denied"}).orElseThrow();

        assertThat(decision.outcome()).isEqualTo(KakaoLoginCallbackDecision.Outcome.CANCELLED);
        assertThat(decision.authorizationCode()).isNull();
        verify(attempts).consume(STATE, BINDING);
    }

    @Test
    @DisplayName("code 중복·code와 error 동시 입력은 소비된 시도에 대해 실패로 분류한다")
    void consumesAttemptThenRejectsAmbiguousCallbackParameters() {
        var duplicateCode = coordinator.consume(
                new String[]{STATE}, BINDING, new String[]{CODE, CODE}, null).orElseThrow();
        var mixedParameters = coordinator.consume(
                new String[]{STATE}, BINDING, new String[]{CODE}, new String[]{"access_denied"}).orElseThrow();

        assertThat(duplicateCode.outcome()).isEqualTo(KakaoLoginCallbackDecision.Outcome.FAILED);
        assertThat(duplicateCode.authorizationCode()).isNull();
        assertThat(mixedParameters.outcome()).isEqualTo(KakaoLoginCallbackDecision.Outcome.FAILED);
        verify(attempts, times(2)).consume(STATE, BINDING);
    }

    @Test
    @DisplayName("blank, padded, overlong, and control-bearing authorization values fail after consuming state")
    void consumesAttemptThenRejectsUnsafeAuthorizationValues() {
        for (String unsafe : new String[]{"", " padded", "padded ", "x".repeat(2049), "line\nbreak"}) {
            KakaoLoginCallbackDecision decision = coordinator.consume(
                    new String[]{STATE}, BINDING, new String[]{unsafe}, null).orElseThrow();
            assertThat(decision.outcome()).isEqualTo(KakaoLoginCallbackDecision.Outcome.FAILED);
            assertThat(decision.authorizationCode()).isNull();
        }
        verify(attempts, times(5)).consume(STATE, BINDING);
    }

    @Test
    @DisplayName("the maximum permitted authorization-code length remains accepted")
    void acceptsAuthorizationCodeAtTheLengthLimit() {
        String maximumLengthCode = "x".repeat(2048);
        KakaoLoginCallbackDecision decision = coordinator.consume(
                new String[]{STATE}, BINDING, new String[]{maximumLengthCode}, null).orElseThrow();

        assertThat(decision.outcome()).isEqualTo(KakaoLoginCallbackDecision.Outcome.AUTHORIZATION_CODE);
        assertThat(decision.authorizationCode()).isEqualTo(maximumLengthCode);
    }

    @Test
    @DisplayName("provider 오류 원문은 저장하지 않고 알 수 없는 오류를 failed로 처리한다")
    void doesNotRetainUnknownProviderError() {
        KakaoLoginCallbackDecision decision = coordinator.consume(
                new String[]{STATE}, BINDING, null, new String[]{"fake-provider-error"}).orElseThrow();

        assertThat(decision.outcome()).isEqualTo(KakaoLoginCallbackDecision.Outcome.FAILED);
        assertThat(decision.toString()).doesNotContain("fake-provider-error");
        verify(attempts).consume(STATE, BINDING);
    }

    @Test
    @DisplayName("state 누락·중복·비정규 값은 저장소를 호출하지 않는다")
    void rejectsAmbiguousStateBeforeAttemptLookup() {
        assertThat(coordinator.consume(null, BINDING, new String[]{CODE}, null)).isEmpty();
        assertThat(coordinator.consume(new String[]{STATE, STATE}, BINDING, new String[]{CODE}, null)).isEmpty();
        assertThat(coordinator.consume(new String[]{"invalid-state"}, BINDING, new String[]{CODE}, null)).isEmpty();

        verify(attempts, never()).consume(STATE, BINDING);
    }

    @Test
    @DisplayName("비정규 browser binding 또는 소비 기록이 없는 state는 callback을 인정하지 않는다")
    void rejectsInvalidBindingAndUnknownAttempt() {
        assertThat(coordinator.consume(new String[]{STATE}, "bad-binding", new String[]{CODE}, null)).isEmpty();
        when(attempts.consume(STATE, BINDING)).thenReturn(Optional.empty());
        assertThat(coordinator.consume(new String[]{STATE}, BINDING, new String[]{CODE}, null)).isEmpty();

        verify(attempts).consume(STATE, BINDING);
        verify(attempts, never()).consume(STATE, "bad-binding");
    }
}

package gguip1.community.domain.auth.callback;

import gguip1.community.domain.auth.attempt.LoginAttempt;

import java.util.Objects;

/** 원자 소비된 로그인 시도와 callback 분류 결과를 다음 조율 단계에 전달합니다. */
public final class KakaoLoginCallbackDecision {
    public enum Outcome {
        AUTHORIZATION_CODE,
        CANCELLED,
        FAILED
    }

    private final LoginAttempt attempt;
    private final Outcome outcome;
    private final String authorizationCode;
    private final String consumedState;

    KakaoLoginCallbackDecision(
            LoginAttempt attempt,
            Outcome outcome,
            String authorizationCode,
            String consumedState) {
        this.attempt = Objects.requireNonNull(attempt, "attempt");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.authorizationCode = authorizationCode;
        this.consumedState = Objects.requireNonNull(consumedState, "consumedState");
    }

    public LoginAttempt attempt() {
        return attempt;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** 성공 outcome에서만 다음 단계의 KakaoIdentityClient에 전달합니다. */
    public String authorizationCode() {
        return authorizationCode;
    }

    /** state 검증·소비 후 KakaoIdentityClient에 한 번 전달할 원문입니다. */
    public String consumedState() {
        return consumedState;
    }

    @Override
    public String toString() {
        return "KakaoLoginCallbackDecision[outcome=" + outcome + ", attempt=redacted, providerParameters=redacted]";
    }
}

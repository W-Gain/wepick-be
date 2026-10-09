package gguip1.community.domain.auth.attempt;

import java.util.Optional;

/** 생성 결과와 성공 시에만 사용할 일회 state를 보관하며 문자열 표시는 state를 가립니다. */
public final class LoginAttemptStartResult {
    private final LoginAttemptCreationResult status;
    private final String state;

    private LoginAttemptStartResult(LoginAttemptCreationResult status, String state) {
        this.status = status;
        this.state = state;
    }

    /** 저장에 성공한 경우에만 raw state를 결과에 담습니다. */
    public static LoginAttemptStartResult created(String state) {
        return new LoginAttemptStartResult(LoginAttemptCreationResult.CREATED, state);
    }

    /** 활성 중복 결과에는 새 state를 반환하지 않습니다. */
    public static LoginAttemptStartResult activeAttemptExists() {
        return new LoginAttemptStartResult(LoginAttemptCreationResult.ACTIVE_ATTEMPT_EXISTS, null);
    }

    public LoginAttemptCreationResult status() {
        return status;
    }

    public Optional<String> state() {
        return Optional.ofNullable(state);
    }

    @Override
    public String toString() {
        return "LoginAttemptStartResult[status=" + status + ", state=<redacted>]";
    }
}

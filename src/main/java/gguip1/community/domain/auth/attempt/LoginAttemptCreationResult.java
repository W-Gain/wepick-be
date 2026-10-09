package gguip1.community.domain.auth.attempt;

/** 내부 생성 결과이며 HTTP 상태 코드로 직접 노출하지 않습니다. */
public enum LoginAttemptCreationResult {
    CREATED,
    ACTIVE_ATTEMPT_EXISTS
}

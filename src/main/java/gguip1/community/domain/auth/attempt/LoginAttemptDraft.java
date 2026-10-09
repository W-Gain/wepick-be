package gguip1.community.domain.auth.attempt;

import java.time.LocalDateTime;

/** DB에 저장할 로그인 시도 초안으로, 원문 state와 binding은 포함하지 않습니다. */
public record LoginAttemptDraft(
        String stateHash,
        String browserBindingHash,
        String clientAttemptId,
        String returnTo,
        LocalDateTime createdAt,
        LocalDateTime expiresAt) {
}

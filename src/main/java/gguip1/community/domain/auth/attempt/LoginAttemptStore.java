package gguip1.community.domain.auth.attempt;

import java.time.LocalDateTime;
import java.util.Optional;

public interface LoginAttemptStore {
    /** 활성 중복을 유지하고, 중복이 아닐 때만 시도를 새로 저장합니다. */
    LoginAttemptCreationResult create(LoginAttemptDraft draft);

    /** 만료 전·미소비 state와 binding이 일치할 때 한 번만 소비합니다. */
    Optional<LoginAttempt> consume(String stateHash, String browserBindingHash, LocalDateTime nowUtc);
}

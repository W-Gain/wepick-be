package gguip1.community.domain.auth.attempt;

import java.time.LocalDateTime;
import java.util.Optional;

public interface LoginAttemptStore {
    Optional<LoginAttempt> consume(String stateHash, String browserBindingHash, LocalDateTime nowUtc);
}

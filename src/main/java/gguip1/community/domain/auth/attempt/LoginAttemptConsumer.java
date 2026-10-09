package gguip1.community.domain.auth.attempt;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** state와 browser binding을 해시해 저장소의 원자 일회 소비를 수행합니다. */
@Service
public class LoginAttemptConsumer {
    private final LoginAttemptStore store;
    private final Clock clock;

    public LoginAttemptConsumer(LoginAttemptStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 입력이 비어 있으면 실패 처리하고, 저장소 오류는 그대로 전파합니다. */
    public Optional<LoginAttempt> consume(String rawState, String rawBrowserBinding) {
        if (rawState == null || rawState.isBlank()
                || rawBrowserBinding == null || rawBrowserBinding.isBlank()) {
            return Optional.empty();
        }

        String stateHash = LoginAttemptHashing.sha256Hex(rawState);
        String browserBindingHash = LoginAttemptHashing.sha256Hex(rawBrowserBinding);
        var nowUtc = LoginAttemptTime.nowUtc(clock);

        return store.consume(stateHash, browserBindingHash, nowUtc);
    }
}

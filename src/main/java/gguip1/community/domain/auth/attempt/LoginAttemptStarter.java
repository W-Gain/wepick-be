package gguip1.community.domain.auth.attempt;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Objects;

/** 검증된 binding으로 일회 state를 만들고 저장된 경우에만 반환합니다. */
@Service
public class LoginAttemptStarter {
    private static final int TOKEN_BYTES = 32;
    private static final long TTL_MINUTES = 10;

    private final LoginAttemptStore store;
    private final LoginAttemptInputValidator validator;
    private final Clock clock;
    private final SecureRandom secureRandom;

    public LoginAttemptStarter(LoginAttemptStore store, LoginAttemptInputValidator validator,
                               Clock clock, SecureRandom secureRandom) {
        this.store = Objects.requireNonNull(store, "store");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    /** 잘못된 요청은 저장 전에 거부하고, 정상 요청은 state hash와 정확한 만료 시각을 저장합니다. */
    public LoginAttemptStartResult start(String browserBinding, String clientAttemptId, String returnTo) {
        String validatedBinding = validator.validateBrowserBinding(browserBinding);
        String validatedAttemptId = validator.validateClientAttemptId(clientAttemptId);
        String normalizedReturnTo = validator.normalizeReturnTo(returnTo);

        LocalDateTime createdAt = LoginAttemptTime.nowUtc(clock);
        byte[] stateBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(stateBytes);
        String rawState = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);
        LoginAttemptDraft draft = new LoginAttemptDraft(
                LoginAttemptHashing.sha256Hex(rawState),
                LoginAttemptHashing.sha256Hex(validatedBinding),
                validatedAttemptId,
                normalizedReturnTo,
                createdAt,
                createdAt.plusMinutes(TTL_MINUTES));

        LoginAttemptCreationResult creation = store.create(draft);
        if (creation == LoginAttemptCreationResult.CREATED) {
            return LoginAttemptStartResult.created(rawState);
        }
        if (creation == LoginAttemptCreationResult.ACTIVE_ATTEMPT_EXISTS) {
            return LoginAttemptStartResult.activeAttemptExists();
        }
        throw new IllegalStateException("Unexpected login attempt creation result");
    }
}

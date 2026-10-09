package gguip1.community.domain.auth.attempt;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

@Service
public class LoginAttemptConsumer {
    private final LoginAttemptStore store;
    private final Clock clock;

    public LoginAttemptConsumer(LoginAttemptStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<LoginAttempt> consume(String rawState, String rawBrowserBinding) {
        if (rawState == null || rawState.isBlank()
                || rawBrowserBinding == null || rawBrowserBinding.isBlank()) {
            return Optional.empty();
        }

        String stateHash = sha256Hex(rawState);
        String browserBindingHash = sha256Hex(rawBrowserBinding);
        LocalDateTime nowUtc = LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);

        return store.consume(stateHash, browserBindingHash, nowUtc);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

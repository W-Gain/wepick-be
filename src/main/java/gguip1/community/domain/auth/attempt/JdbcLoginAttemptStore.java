package gguip1.community.domain.auth.attempt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

@Repository
public class JdbcLoginAttemptStore implements LoginAttemptStore {
    private static final String CONSUME_SQL = """
            UPDATE login_attempts
            SET consumed_at = ?
            WHERE state_hash = ?
              AND browser_binding_hash = ?
              AND consumed_at IS NULL
              AND expires_at > ?
            """;

    private static final String READ_CONFIRMED_ATTEMPT_SQL = """
            SELECT client_attempt_id, return_to
            FROM login_attempts
            WHERE state_hash = ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcLoginAttemptStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<LoginAttempt> consume(String stateHash, String browserBindingHash, LocalDateTime nowUtc) {
        Objects.requireNonNull(stateHash, "stateHash");
        Objects.requireNonNull(browserBindingHash, "browserBindingHash");
        Objects.requireNonNull(nowUtc, "nowUtc");

        int updated = jdbc.update(CONSUME_SQL, nowUtc, stateHash, browserBindingHash, nowUtc);
        if (updated == 0) {
            return Optional.empty();
        }
        if (updated != 1) {
            throw new IllegalStateException("A login attempt consume must update exactly one row");
        }

        LoginAttempt attempt = jdbc.queryForObject(
                READ_CONFIRMED_ATTEMPT_SQL,
                (resultSet, rowNumber) -> new LoginAttempt(
                        resultSet.getString("client_attempt_id"),
                        resultSet.getString("return_to")),
                stateHash);
        return Optional.of(Objects.requireNonNull(attempt, "Consumed login attempt was not readable"));
    }
}

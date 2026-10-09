package gguip1.community.domain.auth.attempt;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/** V2 테이블에서 생성 중복을 잠그고 기존 state를 원자적으로 소비합니다. */
@Repository
public class JdbcLoginAttemptStore implements LoginAttemptStore {
    private static final int CREATE_LOCK_TIMEOUT_SECONDS = 5;
    private static final String GET_LOCK_SQL = "SELECT GET_LOCK(?, ?)";
    private static final String RELEASE_LOCK_SQL = "SELECT RELEASE_LOCK(?)";
    private static final String ACTIVE_ATTEMPT_SQL = """
            SELECT 1
            FROM login_attempts
            WHERE browser_binding_hash = ?
              AND BINARY client_attempt_id = BINARY ?
              AND consumed_at IS NULL
              AND expires_at > ?
            LIMIT 1
            """;
    private static final String CREATE_SQL = """
            INSERT INTO login_attempts (
                state_hash, browser_binding_hash, client_attempt_id, return_to,
                created_at, expires_at, consumed_at
            ) VALUES (?, ?, ?, ?, ?, ?, NULL)
            """;
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
    private final DataSource dataSource;

    public JdbcLoginAttemptStore(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** 같은 물리 세션의 named lock 안에서 활성 확인·insert·commit을 직렬화합니다. */
    @Override
    public LoginAttemptCreationResult create(LoginAttemptDraft draft) {
        Objects.requireNonNull(draft, "draft");
        // 외부 Spring 트랜잭션과 분리해 시작 시도는 자체 커밋 경계에서 확정합니다.
        try (Connection connection = dataSource.getConnection()) {
            return createOnConnection(connection, draft);
        } catch (SQLException exception) {
            throw new DataAccessResourceFailureException("Login attempt storage operation failed", exception);
        }
    }

    private LoginAttemptCreationResult createOnConnection(Connection connection, LoginAttemptDraft draft)
            throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        boolean transactionStarted = false;
        boolean connectionAborted = false;
        boolean acquiringLock = false;
        LockAcquisition lockAcquisition = new LockAcquisition();
        Throwable operationFailure = null;
        String databaseName = connection.getCatalog();
        if (databaseName == null || databaseName.isBlank()) {
            throw new SQLException("Current database name is unavailable");
        }
        String lockName = lockName(databaseName, draft.browserBindingHash(), draft.clientAttemptId());

        try {
            connection.setAutoCommit(false);
            transactionStarted = true;
            acquiringLock = true;
            acquireLock(connection, lockName, lockAcquisition);
            acquiringLock = false;

            LoginAttemptCreationResult result;
            // named lock을 기다린 뒤 이 트랜잭션의 첫 InnoDB 일관 읽기를 수행해 오래된 snapshot 중복 검사를 피합니다.
            if (hasActiveAttempt(connection, draft)) {
                result = LoginAttemptCreationResult.ACTIVE_ATTEMPT_EXISTS;
            } else {
                insert(connection, draft);
                result = LoginAttemptCreationResult.CREATED;
            }

            connection.commit();
            transactionStarted = false;
            return result;
        } catch (SQLException | RuntimeException exception) {
            operationFailure = exception;
            if (acquiringLock && (lockAcquisition.acquired || !lockAcquisition.resolved)) {
                // GET_LOCK 응답/close 시점이 모호하면 같은 물리 세션을 폐기해 lock 누수를 막습니다.
                connectionAborted = true;
                discardConnection(connection, exception);
            } else if (transactionStarted) {
                try {
                    connection.rollback();
                } catch (SQLException | RuntimeException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                    // 롤백 결과가 불명확하면 autocommit 복구가 데이터를 커밋할 수 있어 연결을 폐기합니다.
                    connectionAborted = true;
                    discardConnection(connection, rollbackFailure);
                }
            }
            throw exception;
        } finally {
            Exception cleanupFailure = null;
            if (lockAcquisition.acquired && !connectionAborted) {
                try {
                    // commit 또는 rollback으로 트랜잭션이 끝난 뒤 같은 세션에서 named lock을 해제해야 다른 생성 요청이 진행됩니다.
                    releaseLock(connection, lockName);
                } catch (SQLException | RuntimeException releaseFailure) {
                    cleanupFailure = releaseFailure;
                    connectionAborted = true;
                    discardConnection(connection, releaseFailure);
                }
            }
            if (!connectionAborted) {
                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (SQLException | RuntimeException restoreFailure) {
                    if (cleanupFailure == null) {
                        cleanupFailure = restoreFailure;
                    }
                    connectionAborted = true;
                    discardConnection(connection, cleanupFailure);
                }
            }
            if (cleanupFailure != null) {
                if (operationFailure != null) {
                    operationFailure.addSuppressed(cleanupFailure);
                } else if (cleanupFailure instanceof SQLException sqlFailure) {
                    throw sqlFailure;
                } else if (cleanupFailure instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                } else {
                    throw new IllegalStateException("Unexpected cleanup failure type", cleanupFailure);
                }
            }
        }
    }

    private static void acquireLock(Connection connection, String lockName, LockAcquisition lockAcquisition)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(GET_LOCK_SQL)) {
            statement.setString(1, lockName);
            statement.setInt(2, CREATE_LOCK_TIMEOUT_SECONDS);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Login attempt creation lock returned no result");
                }
                int acquired = result.getInt(1);
                boolean wasNull = result.wasNull();
                lockAcquisition.resolved = true;
                if (wasNull || acquired != 1) {
                    throw new SQLException("Login attempt creation lock was not acquired");
                }
                lockAcquisition.acquired = true;
            }
        }
    }

    private static final class LockAcquisition {
        private boolean resolved;
        private boolean acquired;
    }

    private static boolean hasActiveAttempt(Connection connection, LoginAttemptDraft draft) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ACTIVE_ATTEMPT_SQL)) {
            statement.setString(1, draft.browserBindingHash());
            statement.setString(2, draft.clientAttemptId());
            statement.setObject(3, draft.createdAt());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void insert(Connection connection, LoginAttemptDraft draft) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CREATE_SQL)) {
            statement.setString(1, draft.stateHash());
            statement.setString(2, draft.browserBindingHash());
            statement.setString(3, draft.clientAttemptId());
            statement.setString(4, draft.returnTo());
            statement.setObject(5, draft.createdAt());
            statement.setObject(6, draft.expiresAt());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Login attempt insert did not create one row");
            }
        }
    }

    private static void releaseLock(Connection connection, String lockName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RELEASE_LOCK_SQL)) {
            statement.setString(1, lockName);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Login attempt creation lock was not released");
                }
                int released = result.getInt(1);
                if (result.wasNull() || released != 1) {
                    throw new SQLException("Login attempt creation lock was not released");
                }
            }
        }
    }

    static String lockName(String databaseName, String browserBindingHash, String clientAttemptId) {
        Objects.requireNonNull(databaseName, "databaseName");
        Objects.requireNonNull(browserBindingHash, "browserBindingHash");
        Objects.requireNonNull(clientAttemptId, "clientAttemptId");
        String key = "login-attempt-create" + '\u0000' + databaseName
                + '\u0000' + browserBindingHash + '\u0000' + clientAttemptId;
        return LoginAttemptHashing.sha256Hex(key);
    }

    private void discardConnection(Connection connection, Throwable failureAnchor) {
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            try {
                // abort는 물리 세션을 닫지만 풀 엔트리를 퇴출하지 않아 Hikari에서도 명시적으로 eviction합니다.
                hikariDataSource.evictConnection(connection);
            } catch (RuntimeException evictionFailure) {
                failureAnchor.addSuppressed(evictionFailure);
            }
        }
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException abortFailure) {
            failureAnchor.addSuppressed(abortFailure);
        }
    }

    /** state와 binding이 일치하고 만료되지 않은 행만 조건부 UPDATE로 한 번 소비합니다. */
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

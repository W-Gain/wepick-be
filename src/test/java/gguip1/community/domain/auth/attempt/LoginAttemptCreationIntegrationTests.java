package gguip1.community.domain.auth.attempt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class LoginAttemptCreationIntegrationTests {
    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    private static final String RETURN_TO = "/picks/one?from=a+b&tag=%26#overview";

    @Autowired LoginAttemptStarter starter;
    @Autowired LoginAttemptConsumer consumer;
    @Autowired LoginAttemptStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearLoginAttempts() {
        jdbc.update("DELETE FROM login_attempts");
    }

    @Test
    @DisplayName("새 state는 원문 대신 해시로 저장되고 UTC 10분 뒤 만료되며 한 번 소비된다")
    void persistsOnlyHashesWithTenMinuteUtcLifetimeAndCanBeConsumed() {
        String binding = binding((byte) 0);
        String attemptId = attemptId(1);

        LoginAttemptStartResult start = starter.start(binding, attemptId, RETURN_TO);
        String rawState = start.state().orElseThrow();
        String stateHash = LoginAttemptHashing.sha256Hex(rawState);

        assertThat(start.status()).isEqualTo(LoginAttemptCreationResult.CREATED);
        assertThat(rawState).matches("[A-Za-z0-9_-]{43}");
        assertThat(jdbc.queryForObject("SELECT state_hash FROM login_attempts WHERE state_hash = ?",
                String.class, stateHash)).isEqualTo(stateHash);
        assertThat(jdbc.queryForObject("SELECT browser_binding_hash FROM login_attempts WHERE state_hash = ?",
                String.class, stateHash)).isEqualTo(LoginAttemptHashing.sha256Hex(binding));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE state_hash = ?",
                Integer.class, rawState)).isZero();
        assertThat(jdbc.queryForObject("SELECT client_attempt_id FROM login_attempts WHERE state_hash = ?",
                String.class, stateHash)).isEqualTo(attemptId);
        assertThat(jdbc.queryForObject("SELECT return_to FROM login_attempts WHERE state_hash = ?",
                String.class, stateHash)).isEqualTo(RETURN_TO);

        LocalDateTime createdAt = jdbc.queryForObject(
                "SELECT created_at FROM login_attempts WHERE state_hash = ?", LocalDateTime.class, stateHash);
        LocalDateTime expiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM login_attempts WHERE state_hash = ?", LocalDateTime.class, stateHash);
        assertThat(createdAt.getNano() % 1_000).isZero();
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofMinutes(10));

        assertThat(consumer.consume(rawState, binding)).contains(new LoginAttempt(attemptId, RETURN_TO));
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM login_attempts WHERE state_hash = ?",
                LocalDateTime.class, stateHash)).isNotNull();
    }

    @Test
    @DisplayName("한글 복귀 URI는 UTF-8 canonical percent encoding으로 저장하고 query·fragment 의미를 보존한다")
    void storesKoreanReturnToAsCanonicalAsciiUri() {
        String binding = binding((byte) 11);
        String attemptId = attemptId(64);
        String stateHash;
        String canonicalReturnTo = "/%EC%A3%BC%EC%A0%9C/%ED%95%9C%EA%B8%80?%EA%B2%80%EC%83%89=%EC%84%9C%EC%9A%B8+%EC%B9%B4%ED%8E%98&%ED%91%9C%EC%8B%9C=%26%3D#%EC%9A%94%EC%95%BD";

        LoginAttemptStartResult result = starter.start(binding, attemptId,
                "/주제/한글?검색=서울+카페&표시=%26%3D#요약");
        stateHash = LoginAttemptHashing.sha256Hex(result.state().orElseThrow());

        assertThat(jdbc.queryForObject("SELECT return_to FROM login_attempts WHERE state_hash = ?",
                String.class, stateHash)).isEqualTo(canonicalReturnTo);
        assertThat(canonicalReturnTo).contains("+", "%26%3D").doesNotContain("한글", "서울", "요약");
    }

    @Test
    @DisplayName("활성 중복은 덮어쓰지 않고 소비·만료된 시도 ID는 다시 사용할 수 있다")
    void activeDuplicateIsNotOverwrittenButConsumedAndExpiredIdsCanBeReused() {
        String binding = binding((byte) 0);
        String attemptId = attemptId(2);
        LoginAttemptStartResult first = starter.start(binding, attemptId, RETURN_TO);
        String firstState = first.state().orElseThrow();
        String firstHash = LoginAttemptHashing.sha256Hex(firstState);

        LoginAttemptStartResult duplicate = starter.start(binding, attemptId, "/different");
        assertThat(duplicate.status()).isEqualTo(LoginAttemptCreationResult.ACTIVE_ATTEMPT_EXISTS);
        assertThat(duplicate.state()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE browser_binding_hash = ? AND BINARY client_attempt_id = BINARY ?",
                Integer.class, LoginAttemptHashing.sha256Hex(binding), attemptId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT return_to FROM login_attempts WHERE state_hash = ?",
                String.class, firstHash)).isEqualTo(RETURN_TO);

        assertThat(consumer.consume(firstState, binding)).contains(new LoginAttempt(attemptId, RETURN_TO));
        LoginAttemptStartResult afterConsume = starter.start(binding, attemptId, "/after-consume");
        assertThat(afterConsume.status()).isEqualTo(LoginAttemptCreationResult.CREATED);
        assertThat(afterConsume.state()).isPresent();

        String expiringId = attemptId(3);
        LoginAttemptStartResult expiring = starter.start(binding, expiringId, "/before-expiry");
        String expiringHash = LoginAttemptHashing.sha256Hex(expiring.state().orElseThrow());
        jdbc.update("UPDATE login_attempts SET expires_at = ? WHERE state_hash = ?",
                LocalDateTime.of(2000, 1, 1, 0, 0), expiringHash);
        assertThat(starter.start(binding, expiringId, "/after-expiry").status())
                .isEqualTo(LoginAttemptCreationResult.CREATED);
    }

    @Test
    @DisplayName("여러 탭과 다른 binding은 독립 시도를 만들고 시도 ID 비교는 대소문자를 구분한다")
    void allowsIndependentIdsAcrossTabsAndTheSameIdInAnotherBinding() {
        String firstBinding = binding((byte) 0);
        String secondBinding = binding((byte) 1);
        String sharedAttemptId = attemptId(4);
        String differentCaseId = sharedAttemptId.toLowerCase(java.util.Locale.ROOT);

        assertThat(starter.start(firstBinding, sharedAttemptId, "/tab-one").status())
                .isEqualTo(LoginAttemptCreationResult.CREATED);
        assertThat(starter.start(firstBinding, attemptId(5), "/tab-two").status())
                .isEqualTo(LoginAttemptCreationResult.CREATED);
        assertThat(starter.start(firstBinding, differentCaseId, "/tab-case-sensitive").status())
                .isEqualTo(LoginAttemptCreationResult.CREATED);
        assertThat(starter.start(secondBinding, sharedAttemptId, "/other-browser").status())
                .isEqualTo(LoginAttemptCreationResult.CREATED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts", Integer.class)).isEqualTo(4);
    }

    @Test
    @DisplayName("같은 binding과 시도 ID의 동시 생성 10회마다 정확히 한 요청만 저장된다")
    void concurrentSamePairCreatesExactlyOneActiveRowAcrossTenRounds() throws Exception {
        // 매 라운드 8개 작업을 barrier에서 함께 출발시켜 조회-삽입 경합을 실제 MySQL에서 반복합니다.
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 10; round++) {
                String attemptId = attemptId(10 + round);
                CyclicBarrier barrier = new CyclicBarrier(8);
                List<Future<LoginAttemptStartResult>> results = new ArrayList<>();
                for (int request = 0; request < 8; request++) {
                    results.add(executor.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return starter.start(binding((byte) 0), attemptId, RETURN_TO);
                    }));
                }

                List<LoginAttemptStartResult> completed = new ArrayList<>();
                for (Future<LoginAttemptStartResult> result : results) {
                    completed.add(result.get(20, TimeUnit.SECONDS));
                }
                assertThat(completed.stream().filter(result -> result.status() == LoginAttemptCreationResult.CREATED))
                        .hasSize(1);
                assertThat(completed.stream().filter(result -> result.status() == LoginAttemptCreationResult.ACTIVE_ATTEMPT_EXISTS))
                        .hasSize(7);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE BINARY client_attempt_id = BINARY ?",
                        Integer.class, attemptId)).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("호출자 외부 트랜잭션이 rollback돼도 생성은 독립 커밋으로 남는다")
    void creationCommitsIndependentlyOfAnOuterTransactionRollback() {
        String binding = binding((byte) 0);
        String attemptId = attemptId(30);
        AtomicReference<LoginAttemptStartResult> created = new AtomicReference<>();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        outer.executeWithoutResult(status -> {
            created.set(starter.start(binding, attemptId, RETURN_TO));
            status.setRollbackOnly();
        });

        assertThat(created.get().status()).isEqualTo(LoginAttemptCreationResult.CREATED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE BINARY client_attempt_id = BINARY ?",
                Integer.class, attemptId)).isEqualTo(1);
    }

    @Test
    @DisplayName("state hash 고유 제약 오류는 rollback하고 같은 named lock 재시도를 허용한다")
    void stateHashUniqueFailureRollsBackAndReleasesThePairLock() {
        String binding = binding((byte) 0);
        String bindingHash = LoginAttemptHashing.sha256Hex(binding);
        String collisionHash = "a".repeat(64);
        LocalDateTime now = LocalDateTime.now(java.time.Clock.systemUTC()).truncatedTo(ChronoUnit.MICROS);
        String firstId = attemptId(40);
        String secondId = attemptId(41);

        assertThat(store.create(new LoginAttemptDraft(collisionHash, bindingHash, firstId, "/first",
                now, now.plusMinutes(10)))).isEqualTo(LoginAttemptCreationResult.CREATED);
        assertThatThrownBy(() -> store.create(new LoginAttemptDraft(collisionHash, bindingHash, secondId,
                "/collision", now, now.plusMinutes(10))))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE BINARY client_attempt_id = BINARY ?",
                Integer.class, secondId)).isZero();
        assertThat(starter.start(binding, secondId, "/retry-after-rollback").status())
                .isEqualTo(LoginAttemptCreationResult.CREATED);
    }

    @Test
    @DisplayName("named lock은 5초 대기 제한으로 실패하며 타 세션 잠금을 유지한다")
    void lockTimeoutIsBoundedAndTheLockCanBeAcquiredAfterFailureOnAnotherConnection() throws Exception {
        // 별도 세션이 lock을 보유한 상태에서 create timeout을 확인한 뒤 소유자와 다른 연결에서 재취득합니다.
        String binding = binding((byte) 0);
        String bindingHash = LoginAttemptHashing.sha256Hex(binding);
        String attemptId = attemptId(50);
        String databaseName = jdbc.queryForObject("SELECT DATABASE()", String.class);
        String lockName = JdbcLoginAttemptStore.lockName(databaseName, bindingHash, attemptId);

        try (Connection holder = dataSource.getConnection()) {
            try (PreparedStatement getLock = holder.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                getLock.setString(1, lockName);
                try (ResultSet result = getLock.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(1);
                }
            }

            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> starter.start(binding, attemptId, RETURN_TO))
                    .isInstanceOf(DataAccessResourceFailureException.class);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
            assertThat(elapsed).isLessThan(Duration.ofSeconds(8));

            try (PreparedStatement release = holder.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                release.setString(1, lockName);
                try (ResultSet result = release.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(1);
                }
            }
        }

        try (Connection probe = dataSource.getConnection();
             PreparedStatement getLock = probe.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            getLock.setString(1, lockName);
            try (ResultSet result = getLock.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
            try (PreparedStatement release = probe.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                release.setString(1, lockName);
                try (ResultSet result = release.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(1);
                }
            }
        }

        assertThat(starter.start(binding, attemptId, RETURN_TO).status())
                .isEqualTo(LoginAttemptCreationResult.CREATED);
        try (Connection pooled = dataSource.getConnection()) {
            assertThat(pooled.createStatement().executeQuery("SELECT 1").next()).isTrue();
        }
    }

    @Test
    @DisplayName("GET_LOCK 성공 뒤 결과 자원 close 실패도 물리 세션 폐기로 정리한다")
    void getLockResultCloseFailureReleasesLockAndEvictsPoolEntry() throws Exception {
        FaultingPool pool = faultingPool(FailurePoint.GET_LOCK_RESULTSET_CLOSE);
        try (pool) {
            String binding = binding((byte) 10);
            String bindingHash = LoginAttemptHashing.sha256Hex(binding);
            String attemptId = attemptId(63);
            String stateHash = "f".repeat(64);
            LocalDateTime now = LocalDateTime.now(java.time.Clock.systemUTC()).truncatedTo(ChronoUnit.MICROS);
            String lockName = JdbcLoginAttemptStore.lockName(mysql.getDatabaseName(), bindingHash, attemptId);
            JdbcLoginAttemptStore isolatedStore = new JdbcLoginAttemptStore(jdbc, pool.hikari);

            // GET_LOCK이 1을 반환한 뒤 ResultSet close에서 실패시켜 성공 응답 직후의 잠금 누수를 재현합니다.
            assertThatThrownBy(() -> isolatedStore.create(new LoginAttemptDraft(stateHash, bindingHash,
                    attemptId, "/lock-close-failure", now, now.plusMinutes(10))))
                    .isInstanceOf(DataAccessResourceFailureException.class)
                    .hasCauseInstanceOf(SQLException.class)
                    .satisfies(failure -> assertThat(failure.getCause())
                            .hasMessageContaining("simulated GET_LOCK result close failure"));

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE state_hash = ?",
                    Integer.class, stateHash)).isZero();
            assertFailedSessionWasEvicted(pool, lockName);
            assertThat(pool.faults.getLockResultCloseFailureTriggered.get()).isTrue();
        }
    }

    @Test
    @DisplayName("commit·rollback 실패 시 불명확한 트랜잭션을 버리고 MySQL 세션 잠금을 풀어준다")
    void rollbackFailureEvictsPhysicalSessionWithoutCommitting() throws Exception {
        FaultingPool pool = faultingPool(FailurePoint.COMMIT_AND_ROLLBACK);
        try (pool) {
            String binding = binding((byte) 7);
            String bindingHash = LoginAttemptHashing.sha256Hex(binding);
            String attemptId = attemptId(60);
            String stateHash = "c".repeat(64);
            LocalDateTime now = LocalDateTime.now(java.time.Clock.systemUTC()).truncatedTo(ChronoUnit.MICROS);
            String lockName = JdbcLoginAttemptStore.lockName(mysql.getDatabaseName(), bindingHash, attemptId);
            JdbcLoginAttemptStore isolatedStore = new JdbcLoginAttemptStore(jdbc, pool.hikari);

            // commit과 rollback을 모두 실패시켜 미확정 insert가 autocommit 복구로 커밋되지 않는지 확인합니다.
            assertThatThrownBy(() -> isolatedStore.create(new LoginAttemptDraft(stateHash, bindingHash,
                    attemptId, "/rollback-failure", now, now.plusMinutes(10))))
                    .isInstanceOf(DataAccessResourceFailureException.class)
                    .satisfies(failure -> {
                        assertThat(failure.getCause()).hasMessageContaining("simulated commit failure");
                        assertThat(failure.getCause().getSuppressed())
                                .anySatisfy(suppressed -> assertThat(suppressed)
                                        .hasMessageContaining("simulated rollback failure"));
                    });

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE state_hash = ?",
                    Integer.class, stateHash)).isZero();
            assertFailedSessionWasEvicted(pool, lockName);
            assertThat(pool.faults.commitFailureTriggered.get()).isTrue();
            assertThat(pool.faults.rollbackFailureTriggered.get()).isTrue();
        }
    }

    @Test
    @DisplayName("RELEASE_LOCK 실패 시 커밋 결과를 보존하고 Hikari 연결을 풀에서 퇴출한다")
    void releaseFailureEvictsPhysicalSessionAndKeepsCommittedRow() throws Exception {
        FaultingPool pool = faultingPool(FailurePoint.RELEASE_LOCK);
        try (pool) {
            String binding = binding((byte) 8);
            String bindingHash = LoginAttemptHashing.sha256Hex(binding);
            String attemptId = attemptId(61);
            String stateHash = "d".repeat(64);
            LocalDateTime now = LocalDateTime.now(java.time.Clock.systemUTC()).truncatedTo(ChronoUnit.MICROS);
            String lockName = JdbcLoginAttemptStore.lockName(mysql.getDatabaseName(), bindingHash, attemptId);
            JdbcLoginAttemptStore isolatedStore = new JdbcLoginAttemptStore(jdbc, pool.hikari);

            // named lock 해제 SQL을 실패시켜도 이미 끝난 commit을 롤백하지 않고 세션 폐기로 잠금을 해제합니다.
            assertThatThrownBy(() -> isolatedStore.create(new LoginAttemptDraft(stateHash, bindingHash,
                    attemptId, "/release-failure", now, now.plusMinutes(10))))
                    .isInstanceOf(DataAccessResourceFailureException.class)
                    .hasCauseInstanceOf(SQLException.class)
                    .satisfies(failure -> assertThat(failure.getCause())
                            .hasMessageContaining("simulated release failure"));

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE state_hash = ?",
                    Integer.class, stateHash)).isEqualTo(1);
            assertFailedSessionWasEvicted(pool, lockName);
            assertThat(pool.faults.releaseFailureTriggered.get()).isTrue();
        }
    }

    @Test
    @DisplayName("autocommit 복구 실패 시 이미 커밋된 행을 유지하고 연결을 풀에서 퇴출한다")
    void autocommitRestoreFailureEvictsPhysicalSessionAndKeepsCommittedRow() throws Exception {
        FaultingPool pool = faultingPool(FailurePoint.AUTOCOMMIT_RESTORE);
        try (pool) {
            String binding = binding((byte) 9);
            String bindingHash = LoginAttemptHashing.sha256Hex(binding);
            String attemptId = attemptId(62);
            String stateHash = "e".repeat(64);
            LocalDateTime now = LocalDateTime.now(java.time.Clock.systemUTC()).truncatedTo(ChronoUnit.MICROS);
            String lockName = JdbcLoginAttemptStore.lockName(mysql.getDatabaseName(), bindingHash, attemptId);
            JdbcLoginAttemptStore isolatedStore = new JdbcLoginAttemptStore(jdbc, pool.hikari);

            // lock 해제 뒤 autocommit 복구만 실패시켜 정리 오류가 행의 커밋 상태를 되돌리지 않음을 확인합니다.
            assertThatThrownBy(() -> isolatedStore.create(new LoginAttemptDraft(stateHash, bindingHash,
                    attemptId, "/autocommit-failure", now, now.plusMinutes(10))))
                    .isInstanceOf(DataAccessResourceFailureException.class)
                    .hasCauseInstanceOf(SQLException.class)
                    .satisfies(failure -> assertThat(failure.getCause())
                            .hasMessageContaining("simulated autocommit restore failure"));

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE state_hash = ?",
                    Integer.class, stateHash)).isEqualTo(1);
            assertFailedSessionWasEvicted(pool, lockName);
            assertThat(pool.faults.autocommitFailureTriggered.get()).isTrue();
        }
    }

    private void assertFailedSessionWasEvicted(FaultingPool pool, String lockName) throws SQLException {
        long failedConnectionId = pool.faults.lastConnectionId.get();
        assertThat(failedConnectionId).isPositive();
        assertThat(jdbc.queryForObject("SELECT IS_USED_LOCK(?) IS NULL", Boolean.class, lockName)).isTrue();

        // pool 크기를 1로 고정해 같은 Hikari 엔트리가 재사용되지 않고 새 물리 세션이 정상 동작하는지 봅니다.
        try (Connection replacement = pool.hikari.getConnection()) {
            assertThat(connectionId(replacement)).isNotEqualTo(failedConnectionId);
            try (Statement statement = replacement.createStatement();
                 ResultSet result = statement.executeQuery("SELECT 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }

    private FaultingPool faultingPool(FailurePoint failurePoint) {
        FaultInjectingDataSource faults = new FaultInjectingDataSource(failurePoint);
        HikariDataSource hikari = new HikariDataSource();
        hikari.setDataSource(faults);
        hikari.setMaximumPoolSize(1);
        hikari.setMinimumIdle(0);
        hikari.setConnectionTimeout(5_000);
        hikari.setPoolName("auth-start-fault-" + failurePoint.name().toLowerCase(java.util.Locale.ROOT));
        return new FaultingPool(hikari, faults);
    }

    private static long connectionId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT CONNECTION_ID()")) {
            if (!result.next()) {
                throw new SQLException("MySQL did not return a connection ID");
            }
            return result.getLong(1);
        }
    }

    private static String binding(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String attemptId(int value) {
        return "A" + String.format("%021d", value);
    }

    private enum FailurePoint {
        GET_LOCK_RESULTSET_CLOSE,
        COMMIT_AND_ROLLBACK,
        RELEASE_LOCK,
        AUTOCOMMIT_RESTORE
    }

    private record FaultingPool(HikariDataSource hikari, FaultInjectingDataSource faults) implements AutoCloseable {
        @Override
        public void close() {
            hikari.close();
        }
    }

    private static final class FaultInjectingDataSource implements DataSource {
        private final FailurePoint failurePoint;
        private final AtomicLong lastConnectionId = new AtomicLong(-1);
        private final AtomicBoolean transactionStarted = new AtomicBoolean();
        private final AtomicBoolean commitFailureTriggered = new AtomicBoolean();
        private final AtomicBoolean rollbackFailureTriggered = new AtomicBoolean();
        private final AtomicBoolean releaseFailureTriggered = new AtomicBoolean();
        private final AtomicBoolean autocommitFailureTriggered = new AtomicBoolean();
        private final AtomicBoolean getLockResultCloseFailureTriggered = new AtomicBoolean();

        private FaultInjectingDataSource(FailurePoint failurePoint) {
            this.failurePoint = failurePoint;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return createConnection(mysql.getUsername(), mysql.getPassword());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return createConnection(username, password);
        }

        private Connection createConnection(String username, String password) throws SQLException {
            Connection physical = java.sql.DriverManager.getConnection(mysql.getJdbcUrl(), username, password);
            lastConnectionId.set(connectionId(physical));
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("commit")
                                && failurePoint == FailurePoint.COMMIT_AND_ROLLBACK) {
                            commitFailureTriggered.set(true);
                            throw new SQLException("simulated commit failure");
                        }
                        if (method.getName().equals("rollback")
                                && failurePoint == FailurePoint.COMMIT_AND_ROLLBACK) {
                            rollbackFailureTriggered.set(true);
                            throw new SQLException("simulated rollback failure");
                        }
                        if (method.getName().equals("setAutoCommit") && args != null && args.length == 1) {
                            if (Boolean.FALSE.equals(args[0])) {
                                transactionStarted.set(true);
                            } else if (Boolean.TRUE.equals(args[0])
                                    && transactionStarted.get()
                                    && failurePoint == FailurePoint.AUTOCOMMIT_RESTORE
                                    && autocommitFailureTriggered.compareAndSet(false, true)) {
                                throw new SQLException("simulated autocommit restore failure");
                            }
                        }
                        Object result = invoke(method, physical, args);
                        if (method.getName().equals("prepareStatement") && args != null && args.length > 0
                                && args[0] instanceof String sql
                                && sql.contains("GET_LOCK")
                                && failurePoint == FailurePoint.GET_LOCK_RESULTSET_CLOSE) {
                            PreparedStatement statement = (PreparedStatement) result;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                    new Class<?>[]{PreparedStatement.class}, (statementProxy, statementMethod, statementArgs) -> {
                                        Object statementResult = invoke(statementMethod, statement, statementArgs);
                                        if (statementMethod.getName().equals("executeQuery")) {
                                            ResultSet resultSet = (ResultSet) statementResult;
                                            return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                                                    new Class<?>[]{ResultSet.class}, (resultProxy, resultMethod, resultArgs) -> {
                                                        if (resultMethod.getName().equals("close")
                                                                && getLockResultCloseFailureTriggered.compareAndSet(false, true)) {
                                                            invoke(resultMethod, resultSet, resultArgs);
                                                            throw new SQLException("simulated GET_LOCK result close failure");
                                                        }
                                                        return invoke(resultMethod, resultSet, resultArgs);
                                                    });
                                        }
                                        return statementResult;
                                    });
                        }
                        if (method.getName().equals("prepareStatement") && args != null && args.length > 0
                                && args[0] instanceof String sql
                                && sql.contains("RELEASE_LOCK")
                                && failurePoint == FailurePoint.RELEASE_LOCK) {
                            PreparedStatement statement = (PreparedStatement) result;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                    new Class<?>[]{PreparedStatement.class}, (statementProxy, statementMethod, statementArgs) -> {
                                        if (statementMethod.getName().equals("executeQuery")
                                                && releaseFailureTriggered.compareAndSet(false, true)) {
                                            throw new SQLException("simulated release failure");
                                        }
                                        return invoke(statementMethod, statement, statementArgs);
                                    });
                        }
                        return result;
                    });
        }

        private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getLogger("global"); }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) return iface.cast(this);
            throw new SQLException("Not a wrapper for " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}

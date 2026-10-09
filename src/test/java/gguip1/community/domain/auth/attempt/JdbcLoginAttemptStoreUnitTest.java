package gguip1.community.domain.auth.attempt;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcLoginAttemptStoreUnitTest {
    private static final String STATE = "a".repeat(64);
    private static final String BINDING = "b".repeat(64);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 0, 0);

    @Test
    @DisplayName("소비 갱신 행이 없으면 미확인 시도를 읽지 않고 실패한다")
    void noUpdatedRowReturnsEmptyWithoutReadingUnconfirmedData() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<LoginAttempt>>any(), anyString()))
                .thenThrow(new AssertionError("unconfirmed attempt must not be read"));
        assertThat(new JdbcLoginAttemptStore(jdbc, mock(DataSource.class)).consume(STATE, BINDING, NOW)).isEmpty();
    }

    @Test
    @DisplayName("확인된 소비 결과에서 저장된 시도 ID와 복귀 경로를 반환한다")
    void confirmedRowReturnsPersistedAttemptAndDestination() throws Exception {
        JdbcTemplate jdbc = jdbcWithUpdateCount(1);
        ResultSet row = mock(ResultSet.class);
        when(row.getString("client_attempt_id")).thenReturn("persisted-id");
        when(row.getString("return_to")).thenReturn("/history?from=login");
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<LoginAttempt>>any(), anyString()))
                .thenAnswer(invocation -> invocation.<RowMapper<LoginAttempt>>getArgument(1).mapRow(row, 0));
        assertThat(new JdbcLoginAttemptStore(jdbc, mock(DataSource.class)).consume(STATE, BINDING, NOW))
                .contains(new LoginAttempt("persisted-id", "/history?from=login"));
    }

    @Test
    @DisplayName("소비가 둘 이상의 행을 바꾸면 오류로 처리한다")
    void unexpectedAffectedRowCountIsAnError() {
        assertThatThrownBy(() -> new JdbcLoginAttemptStore(jdbcWithUpdateCount(2), mock(DataSource.class))
                .consume(STATE, BINDING, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("소비 저장소 오류는 invalid 시도로 숨기지 않는다")
    void readFailureIsNotHiddenAsAnInvalidAttempt() {
        JdbcTemplate jdbc = jdbcWithUpdateCount(1);
        var failure = new DataAccessResourceFailureException("storage unavailable");
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<LoginAttempt>>any(), anyString()))
                .thenThrow(failure);
        assertThatThrownBy(() -> new JdbcLoginAttemptStore(jdbc, mock(DataSource.class))
                .consume(STATE, BINDING, NOW)).isSameAs(failure);
    }

    @Test
    @DisplayName("생성은 대소문자 구분 활성 확인 후 commit하고 같은 named lock을 해제한다")
    void createChecksActiveAttemptInCaseSensitiveOrderAndCommitsBeforeReleasingTheLock() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        LoginAttemptDraft draft = draft("a".repeat(64));

        assertThat(fixture.store.create(draft)).isEqualTo(LoginAttemptCreationResult.CREATED);

        var ordered = inOrder(fixture.connection, fixture.lockStatement, fixture.activeStatement,
                fixture.insertStatement, fixture.releaseStatement);
        ordered.verify(fixture.connection).setAutoCommit(false);
        ordered.verify(fixture.lockStatement).executeQuery();
        ordered.verify(fixture.activeStatement).setString(2, "AttemptId-12345678901234567890");
        ordered.verify(fixture.insertStatement).executeUpdate();
        ordered.verify(fixture.connection).commit();
        ordered.verify(fixture.releaseStatement).executeQuery();
        ordered.verify(fixture.connection).setAutoCommit(true);
        verify(fixture.lockStatement).setString(1,
                JdbcLoginAttemptStore.lockName("wepick_test", BINDING, draft.clientAttemptId()));
        verify(fixture.lockStatement).setInt(2, 5);
        verify(fixture.activeStatement).setString(1, BINDING);
        verify(fixture.activeStatement).setString(2, draft.clientAttemptId());
        verify(fixture.activeStatement).setObject(3, NOW);
        verify(fixture.connection).prepareStatement(argThat(sql ->
                sql.contains("BINARY client_attempt_id = BINARY ?")
                        && sql.contains("consumed_at IS NULL")
                        && sql.contains("expires_at > ?")));
        verify(fixture.insertStatement).setString(1, draft.stateHash());
        verify(fixture.insertStatement).setString(2, draft.browserBindingHash());
        verify(fixture.insertStatement).setString(3, draft.clientAttemptId());
        verify(fixture.insertStatement).setString(4, draft.returnTo());
        verify(fixture.insertStatement).setObject(5, draft.createdAt());
        verify(fixture.insertStatement).setObject(6, draft.expiresAt());
        verify(fixture.releaseStatement).setString(1,
                JdbcLoginAttemptStore.lockName("wepick_test", BINDING, draft.clientAttemptId()));
    }

    @Test
    @DisplayName("활성 중복 생성은 기존 시도를 바꾸지 않고 새 행도 넣지 않는다")
    void activeDuplicateIsReturnedWithoutInsertAndWithoutChangingExistingRow() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        when(fixture.activeRows.next()).thenReturn(true);

        assertThat(fixture.store.create(draft("state-b")))
                .isEqualTo(LoginAttemptCreationResult.ACTIVE_ATTEMPT_EXISTS);

        verify(fixture.insertStatement, never()).executeUpdate();
        verify(fixture.connection).commit();
        verify(fixture.releaseStatement).executeQuery();
    }

    @Test
    @DisplayName("named lock timeout은 저장소 오류이며 소유하지 않은 lock은 해제하지 않는다")
    void lockTimeoutIsAStorageFailureAndDoesNotReleaseAnUnownedLock() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        when(fixture.lockRow.getInt(1)).thenReturn(0);

        assertThatThrownBy(() -> fixture.store.create(draft("state-c")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(fixture.connection).rollback();
        verify(fixture.activeStatement, never()).executeQuery();
        verify(fixture.releaseStatement, never()).executeQuery();
    }

    @Test
    @DisplayName("GET_LOCK 결과가 없으면 저장소 오류로 끝내고 insert하지 않는다")
    void emptyGetLockResultIsAStorageFailure() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getConnection()).thenReturn(fixture.connection);
        JdbcLoginAttemptStore store = new JdbcLoginAttemptStore(fixture.jdbc, hikari);
        when(fixture.lockRow.next()).thenReturn(false);

        assertThatThrownBy(() -> store.create(draft("state-empty-lock")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(fixture.connection).abort(any(Executor.class));
        verify(hikari).evictConnection(fixture.connection);
        verify(fixture.connection, never()).rollback();
        verify(fixture.connection, never()).setAutoCommit(true);
        verify(fixture.activeStatement, never()).executeQuery();
        verify(fixture.releaseStatement, never()).executeQuery();
    }

    @Test
    @DisplayName("GET_LOCK의 NULL 결과는 저장소 오류로 처리한다")
    void nullLockResultIsAStorageFailure() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        when(fixture.lockRow.getInt(1)).thenReturn(0);
        when(fixture.lockRow.wasNull()).thenReturn(true);

        assertThatThrownBy(() -> fixture.store.create(draft("state-null-lock")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(fixture.connection).rollback();
        verify(fixture.releaseStatement, never()).executeQuery();
    }

    @Test
    @DisplayName("GET_LOCK 응답을 읽기 전에 오류가 나면 소유 여부가 모호한 연결을 폐기한다")
    void ambiguousLockAcquisitionFailureEvictsConnection() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getConnection()).thenReturn(fixture.connection);
        JdbcLoginAttemptStore store = new JdbcLoginAttemptStore(fixture.jdbc, hikari);
        SQLException responseFailure = new SQLException("GET_LOCK response failed");
        when(fixture.lockStatement.executeQuery()).thenThrow(responseFailure);

        assertThatThrownBy(() -> store.create(draft("state-lock-response-failure")))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasCause(responseFailure);

        verify(fixture.connection).abort(any(Executor.class));
        verify(hikari).evictConnection(fixture.connection);
        verify(fixture.connection, never()).rollback();
        verify(fixture.connection, never()).setAutoCommit(true);
        verify(fixture.activeStatement, never()).executeQuery();
        verify(fixture.releaseStatement, never()).executeQuery();
    }

    @Test
    @DisplayName("GET_LOCK 성공 후 statement close 오류가 나면 그 세션을 물리 폐기한다")
    void successfulLockAcquisitionStatementCloseFailureEvictsConnection() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getConnection()).thenReturn(fixture.connection);
        JdbcLoginAttemptStore store = new JdbcLoginAttemptStore(fixture.jdbc, hikari);
        SQLException closeFailure = new SQLException("GET_LOCK statement close failed");
        doThrow(closeFailure).when(fixture.lockStatement).close();

        assertThatThrownBy(() -> store.create(draft("state-lock-close-failure")))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasCause(closeFailure);

        verify(fixture.connection).abort(any(Executor.class));
        verify(hikari).evictConnection(fixture.connection);
        verify(fixture.connection, never()).rollback();
        verify(fixture.connection, never()).setAutoCommit(true);
        verify(fixture.activeStatement, never()).executeQuery();
        verify(fixture.releaseStatement, never()).executeQuery();
    }

    @Test
    @DisplayName("DB namespace가 없으면 named lock 전에 실패한다")
    void missingDatabaseNamespaceFailsBeforeTakingALock() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        when(fixture.connection.getCatalog()).thenReturn(null);

        assertThatThrownBy(() -> fixture.store.create(draft("state-no-database")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(fixture.lockStatement, never()).executeQuery();

        JdbcFixture blankFixture = new JdbcFixture();
        when(blankFixture.connection.getCatalog()).thenReturn("");
        assertThatThrownBy(() -> blankFixture.store.create(draft("state-blank-database")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(blankFixture.lockStatement, never()).executeQuery();
    }

    @Test
    @DisplayName("insert가 0행이면 롤백하고 named lock을 해제한다")
    void zeroInsertedRowsRollBackAndReleaseTheLock() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        when(fixture.insertStatement.executeUpdate()).thenReturn(0);

        assertThatThrownBy(() -> fixture.store.create(draft("state-no-insert")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(fixture.connection).rollback();
        verify(fixture.releaseStatement).executeQuery();
    }

    @Test
    @DisplayName("롤백 오류는 최초 저장 오류에 억제 예외로 보존한다")
    void rollbackFailureIsSuppressedOnTheOriginalStorageFailure() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getConnection()).thenReturn(fixture.connection);
        JdbcLoginAttemptStore store = new JdbcLoginAttemptStore(fixture.jdbc, hikari);
        SQLException insertFailure = new SQLException("insert failed");
        SQLException rollbackFailure = new SQLException("rollback failed");
        when(fixture.insertStatement.executeUpdate()).thenThrow(insertFailure);
        doThrow(rollbackFailure).when(fixture.connection).rollback();

        assertThatThrownBy(() -> store.create(draft("state-rollback-failure")))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .satisfies(failure -> assertThat(failure.getCause()).isSameAs(insertFailure));

        assertThat(insertFailure.getSuppressed()).contains(rollbackFailure);
        verify(fixture.connection, never()).commit();
        verify(fixture.releaseStatement, never()).executeQuery();
        verify(fixture.connection, never()).setAutoCommit(true);
        verify(fixture.connection).abort(any(Executor.class));
        verify(hikari).evictConnection(fixture.connection);
    }

    @Test
    @DisplayName("named lock 해제 실패는 원래 오류에 보존하고 Hikari 연결을 폐기한다")
    void releaseFailureAbortsPooledConnectionAndIsSuppressedOnOriginalFailure() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getConnection()).thenReturn(fixture.connection);
        JdbcLoginAttemptStore store = new JdbcLoginAttemptStore(fixture.jdbc, hikari);
        SQLException insertFailure = new SQLException("insert failed");
        SQLException releaseFailure = new SQLException("release failed");
        SQLException abortFailure = new SQLException("abort failed");
        when(fixture.insertStatement.executeUpdate()).thenThrow(insertFailure);
        when(fixture.releaseStatement.executeQuery()).thenThrow(releaseFailure);
        doThrow(abortFailure).when(fixture.connection).abort(any(Executor.class));

        assertThatThrownBy(() -> store.create(draft("state-d")))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .satisfies(failure -> assertThat(failure.getCause()).isSameAs(insertFailure));

        assertThat(insertFailure.getSuppressed()).contains(releaseFailure);
        assertThat(releaseFailure.getSuppressed()).contains(abortFailure);
        verify(fixture.connection).rollback();
        verify(fixture.connection).abort(any(Executor.class));
        verify(fixture.connection, never()).setAutoCommit(true);
        verify(hikari).evictConnection(fixture.connection);
    }

    @Test
    @DisplayName("commit 뒤 named lock 해제 실패도 연결을 폐기하고 호출자에게 알린다")
    void releaseFailureAfterCommitStillAbortsConnectionAndPropagatesFailure() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getConnection()).thenReturn(fixture.connection);
        JdbcLoginAttemptStore store = new JdbcLoginAttemptStore(fixture.jdbc, hikari);
        SQLException releaseFailure = new SQLException("release failed");
        when(fixture.releaseStatement.executeQuery()).thenThrow(releaseFailure);

        assertThatThrownBy(() -> store.create(draft("state-e")))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasCause(releaseFailure);

        verify(fixture.connection).commit();
        verify(fixture.connection).abort(any(Executor.class));
        verify(hikari).evictConnection(fixture.connection);
    }

    @Test
    @DisplayName("named lock 해제 RuntimeException은 그대로 전달하고 Hikari 연결을 폐기한다")
    void runtimeReleaseFailureIsPropagatedAndEvictsConnection() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getConnection()).thenReturn(fixture.connection);
        JdbcLoginAttemptStore store = new JdbcLoginAttemptStore(fixture.jdbc, hikari);
        IllegalStateException releaseFailure = new IllegalStateException("release runtime failure");
        when(fixture.releaseStatement.executeQuery()).thenThrow(releaseFailure);

        assertThatThrownBy(() -> store.create(draft("state-runtime-release-failure")))
                .isSameAs(releaseFailure);

        verify(fixture.connection).commit();
        verify(fixture.connection).abort(any(Executor.class));
        verify(hikari).evictConnection(fixture.connection);
        verify(fixture.connection, never()).setAutoCommit(true);
    }

    @Test
    @DisplayName("풀 eviction 자체가 실패해도 그 오류를 보존하고 물리 abort를 시도한다")
    void evictionFailureIsSuppressedAndPhysicalAbortIsStillAttempted() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getConnection()).thenReturn(fixture.connection);
        JdbcLoginAttemptStore store = new JdbcLoginAttemptStore(fixture.jdbc, hikari);
        SQLException releaseFailure = new SQLException("release failed");
        RuntimeException evictionFailure = new IllegalStateException("eviction failed");
        when(fixture.releaseStatement.executeQuery()).thenThrow(releaseFailure);
        doThrow(evictionFailure).when(hikari).evictConnection(fixture.connection);

        assertThatThrownBy(() -> store.create(draft("state-eviction-failure")))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .satisfies(failure -> assertThat(failure.getCause()).isSameAs(releaseFailure));

        assertThat(releaseFailure.getSuppressed()).contains(evictionFailure);
        verify(fixture.connection).abort(any(Executor.class));
        verify(hikari).evictConnection(fixture.connection);
    }

    @Test
    @DisplayName("RELEASE_LOCK이 소유 해제를 확인하지 않으면 연결을 폐기한다")
    void lockReleaseResultMustConfirmTheOwnedLockWasReleased() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getConnection()).thenReturn(fixture.connection);
        JdbcLoginAttemptStore store = new JdbcLoginAttemptStore(fixture.jdbc, hikari);
        when(fixture.releaseRow.getInt(1)).thenReturn(0);

        assertThatThrownBy(() -> store.create(draft("state-not-released")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(fixture.connection).commit();
        verify(fixture.connection).abort(any(Executor.class));
        verify(hikari).evictConnection(fixture.connection);
    }

    @Test
    @DisplayName("RELEASE_LOCK의 NULL 결과에서 연결을 풀로 돌려보내지 않는다")
    void nullReleaseResultAbortsTheConnection() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getConnection()).thenReturn(fixture.connection);
        JdbcLoginAttemptStore store = new JdbcLoginAttemptStore(fixture.jdbc, hikari);
        when(fixture.releaseRow.getInt(1)).thenReturn(0);
        when(fixture.releaseRow.wasNull()).thenReturn(true);

        assertThatThrownBy(() -> store.create(draft("state-null-release")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(fixture.connection).commit();
        verify(fixture.connection).abort(any(Executor.class));
        verify(hikari).evictConnection(fixture.connection);
    }

    @Test
    @DisplayName("RELEASE_LOCK 결과 행이 없으면 연결을 풀에서 폐기한다")
    void missingReleaseResultAbortsTheConnection() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getConnection()).thenReturn(fixture.connection);
        JdbcLoginAttemptStore store = new JdbcLoginAttemptStore(fixture.jdbc, hikari);
        when(fixture.releaseRow.next()).thenReturn(false);

        assertThatThrownBy(() -> store.create(draft("state-empty-release")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(fixture.connection).commit();
        verify(fixture.connection).abort(any(Executor.class));
        verify(hikari).evictConnection(fixture.connection);
    }

    @Test
    @DisplayName("autocommit 복구 실패 시 Hikari 연결을 폐기한다")
    void autocommitRestoreFailureEvictsTheConnection() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getConnection()).thenReturn(fixture.connection);
        JdbcLoginAttemptStore store = new JdbcLoginAttemptStore(fixture.jdbc, hikari);
        SQLException restoreFailure = new SQLException("autocommit restore failed");
        doThrow(restoreFailure).when(fixture.connection).setAutoCommit(true);

        assertThatThrownBy(() -> store.create(draft("state-restore-failure")))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasCause(restoreFailure);

        verify(fixture.connection).commit();
        verify(fixture.connection).abort(any(Executor.class));
        verify(hikari).evictConnection(fixture.connection);
    }

    @Test
    @DisplayName("autocommit 복구 RuntimeException도 원래 오류에 보존하고 Hikari 연결을 폐기한다")
    void runtimeAutocommitRestoreFailureIsSuppressedOnOriginalFailureAndEvictsConnection() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getConnection()).thenReturn(fixture.connection);
        JdbcLoginAttemptStore store = new JdbcLoginAttemptStore(fixture.jdbc, hikari);
        SQLException insertFailure = new SQLException("insert failed");
        IllegalStateException restoreFailure = new IllegalStateException("autocommit restore runtime failure");
        when(fixture.insertStatement.executeUpdate()).thenThrow(insertFailure);
        doThrow(restoreFailure).when(fixture.connection).setAutoCommit(true);

        assertThatThrownBy(() -> store.create(draft("state-runtime-restore-failure")))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasCause(insertFailure);

        assertThat(insertFailure.getSuppressed()).contains(restoreFailure);
        verify(fixture.connection).rollback();
        verify(fixture.releaseStatement).executeQuery();
        verify(fixture.connection, never()).commit();
        verify(fixture.connection).abort(any(Executor.class));
        verify(hikari).evictConnection(fixture.connection);
    }

    @Test
    @DisplayName("autocommit 복구와 연결 폐기가 모두 실패해도 원래 오류를 보존한다")
    void abortFailureIsSuppressedOnAutocommitRestoreFailure() throws Exception {
        JdbcFixture fixture = new JdbcFixture();
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getConnection()).thenReturn(fixture.connection);
        JdbcLoginAttemptStore store = new JdbcLoginAttemptStore(fixture.jdbc, hikari);
        SQLException restoreFailure = new SQLException("autocommit restore failed");
        SQLException abortFailure = new SQLException("abort failed");
        doThrow(restoreFailure).when(fixture.connection).setAutoCommit(true);
        doThrow(abortFailure).when(fixture.connection).abort(any(Executor.class));

        assertThatThrownBy(() -> store.create(draft("state-restore-abort-failure")))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .satisfies(failure -> assertThat(failure.getCause()).isSameAs(restoreFailure));

        assertThat(restoreFailure.getSuppressed()).contains(abortFailure);
        verify(hikari).evictConnection(fixture.connection);
    }

    @Test
    @DisplayName("named lock key는 기능 namespace·DB·binding·원문 시도 ID를 구분한다")
    void namedLockKeyIncludesDatabaseAndExactAttemptIdAndIsSixtyFourHexCharacters() {
        String upper = JdbcLoginAttemptStore.lockName("wepick", BINDING, "AttemptId-12345678901234567890");
        String lower = JdbcLoginAttemptStore.lockName("wepick", BINDING, "attemptid-12345678901234567890");

        assertThat(upper).matches("[0-9a-f]{64}").isNotEqualTo(lower);
        assertThat(JdbcLoginAttemptStore.lockName("other", BINDING, "AttemptId-12345678901234567890"))
                .isNotEqualTo(upper);
    }

    private JdbcTemplate jdbcWithUpdateCount(int count) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(LocalDateTime.class), anyString(), anyString(), any(LocalDateTime.class)))
                .thenReturn(count);
        return jdbc;
    }

    private static LoginAttemptDraft draft(String stateHash) {
        return new LoginAttemptDraft(stateHash, BINDING, "AttemptId-12345678901234567890",
                "/picks/one?x=a+b", NOW, NOW.plusMinutes(10));
    }

    private static final class JdbcFixture {
        private final Connection connection = mock(Connection.class);
        private final PreparedStatement lockStatement = mock(PreparedStatement.class);
        private final PreparedStatement activeStatement = mock(PreparedStatement.class);
        private final PreparedStatement insertStatement = mock(PreparedStatement.class);
        private final PreparedStatement releaseStatement = mock(PreparedStatement.class);
        private final ResultSet lockRow = mock(ResultSet.class);
        private final ResultSet activeRows = mock(ResultSet.class);
        private final ResultSet releaseRow = mock(ResultSet.class);
        private final JdbcTemplate jdbc;
        private final JdbcLoginAttemptStore store;

        private JdbcFixture() throws Exception {
            DataSource dataSource = mock(DataSource.class);
            jdbc = mock(JdbcTemplate.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.getCatalog()).thenReturn("wepick_test");
            when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                if (sql.contains("GET_LOCK")) return lockStatement;
                if (sql.contains("RELEASE_LOCK")) return releaseStatement;
                if (sql.startsWith("SELECT 1")) return activeStatement;
                if (sql.startsWith("INSERT")) return insertStatement;
                throw new AssertionError("Unexpected SQL: " + sql);
            });
            when(lockStatement.executeQuery()).thenReturn(lockRow);
            when(lockRow.next()).thenReturn(true);
            when(lockRow.getInt(1)).thenReturn(1);
            when(activeStatement.executeQuery()).thenReturn(activeRows);
            when(activeRows.next()).thenReturn(false);
            when(insertStatement.executeUpdate()).thenReturn(1);
            when(releaseStatement.executeQuery()).thenReturn(releaseRow);
            when(releaseRow.next()).thenReturn(true);
            when(releaseRow.getInt(1)).thenReturn(1);
            store = new JdbcLoginAttemptStore(jdbc, dataSource);
        }
    }
}

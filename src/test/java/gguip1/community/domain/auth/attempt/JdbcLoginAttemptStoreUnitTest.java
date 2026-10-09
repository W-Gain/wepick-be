package gguip1.community.domain.auth.attempt;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcLoginAttemptStoreUnitTest {
    private static final String STATE = "a".repeat(64);
    private static final String BINDING = "b".repeat(64);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 0, 0);

    @Test
    void noUpdatedRowReturnsEmptyWithoutReadingUnconfirmedData() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<LoginAttempt>>any(), anyString()))
                .thenThrow(new AssertionError("unconfirmed attempt must not be read"));
        assertThat(new JdbcLoginAttemptStore(jdbc).consume(STATE, BINDING, NOW)).isEmpty();
    }

    @Test
    void confirmedRowReturnsPersistedAttemptAndDestination() throws Exception {
        JdbcTemplate jdbc = jdbcWithUpdateCount(1);
        ResultSet row = mock(ResultSet.class);
        when(row.getString("client_attempt_id")).thenReturn("persisted-id");
        when(row.getString("return_to")).thenReturn("/history?from=login");
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<LoginAttempt>>any(), anyString()))
                .thenAnswer(invocation -> invocation.<RowMapper<LoginAttempt>>getArgument(1).mapRow(row, 0));
        assertThat(new JdbcLoginAttemptStore(jdbc).consume(STATE, BINDING, NOW))
                .contains(new LoginAttempt("persisted-id", "/history?from=login"));
    }

    @Test
    void unexpectedAffectedRowCountIsAnError() {
        assertThatThrownBy(() -> new JdbcLoginAttemptStore(jdbcWithUpdateCount(2)).consume(STATE, BINDING, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readFailureIsNotHiddenAsAnInvalidAttempt() {
        JdbcTemplate jdbc = jdbcWithUpdateCount(1);
        var failure = new DataAccessResourceFailureException("storage unavailable");
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<LoginAttempt>>any(), anyString()))
                .thenThrow(failure);
        assertThatThrownBy(() -> new JdbcLoginAttemptStore(jdbc).consume(STATE, BINDING, NOW)).isSameAs(failure);
    }

    private JdbcTemplate jdbcWithUpdateCount(int count) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(LocalDateTime.class), anyString(), anyString(), any(LocalDateTime.class)))
                .thenReturn(count);
        return jdbc;
    }
}

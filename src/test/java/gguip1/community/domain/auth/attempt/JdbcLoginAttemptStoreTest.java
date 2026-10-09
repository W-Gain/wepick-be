package gguip1.community.domain.auth.attempt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@Import(JdbcLoginAttemptStoreTest.Config.class)
class JdbcLoginAttemptStoreTest {
    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 0, 0, 0, 123_456_000);
    private static final String STATE = "a".repeat(64);
    private static final String BINDING = "b".repeat(64);
    private static final LoginAttempt EXPECTED = new LoginAttempt("tab-one", "/topics?view=mine");

    @Autowired LoginAttemptStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired LoginAttemptConsumer consumer;

    @BeforeEach
    void clearAttempts() {
        jdbc.update("DELETE FROM login_attempts");
    }

    @Test
    void validAttemptReturnsStoredValuesAndCannotBeReused() {
        seed(STATE, BINDING, EXPECTED, NOW.plusMinutes(10));
        assertThat(store.consume(STATE, BINDING, NOW)).contains(EXPECTED);
        assertThat(consumedAt(STATE)).isEqualTo(NOW);
        assertThat(store.consume(STATE, BINDING, NOW.plusSeconds(1))).isEmpty();
        assertThat(consumedAt(STATE)).isEqualTo(NOW);
    }

    @Test
    void missingStateChangesNothing() {
        seed(STATE, BINDING, EXPECTED, NOW.plusMinutes(10));
        assertThat(store.consume("c".repeat(64), BINDING, NOW)).isEmpty();
        assertThat(consumedAt(STATE)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts", Integer.class)).isEqualTo(1);
    }

    @Test
    void wrongBrowserCannotBurnAnotherBrowsersAttempt() {
        seed(STATE, BINDING, EXPECTED, NOW.plusMinutes(10));
        assertThat(store.consume(STATE, "c".repeat(64), NOW)).isEmpty();
        assertThat(consumedAt(STATE)).isNull();
        assertThat(store.consume(STATE, BINDING, NOW)).contains(EXPECTED);
    }

    @Test
    void succeedsOneMicrosecondBeforeExpiry() {
        seed(STATE, BINDING, EXPECTED, NOW);
        LocalDateTime justBefore = NOW.minusNanos(1_000);
        assertThat(store.consume(STATE, BINDING, justBefore)).contains(EXPECTED);
        assertThat(consumedAt(STATE)).isEqualTo(justBefore);
    }

    @Test
    void failsExactlyAtExpiryWithoutConsuming() {
        seed(STATE, BINDING, EXPECTED, NOW);
        assertThat(store.consume(STATE, BINDING, NOW)).isEmpty();
        assertThat(consumedAt(STATE)).isNull();
    }

    @Test
    void failsAfterExpiryWithoutConsuming() {
        seed(STATE, BINDING, EXPECTED, NOW);
        assertThat(store.consume(STATE, BINDING, NOW.plusNanos(1_000))).isEmpty();
        assertThat(consumedAt(STATE)).isNull();
    }

    @Test
    void twoTabsCanConsumeInReverseOrder() {
        LoginAttempt second = new LoginAttempt("tab-two", "/history");
        String secondState = "c".repeat(64);
        seed(STATE, BINDING, EXPECTED, NOW.plusMinutes(10));
        seed(secondState, BINDING, second, NOW.plusMinutes(10));
        assertThat(store.consume(secondState, BINDING, NOW)).contains(second);
        assertThat(store.consume(STATE, BINDING, NOW.plusSeconds(1))).contains(EXPECTED);
        assertThat(store.consume(secondState, BINDING, NOW.plusSeconds(2))).isEmpty();
        assertThat(store.consume(STATE, BINDING, NOW.plusSeconds(2))).isEmpty();
        assertThat(consumedAt(secondState)).isEqualTo(NOW);
        assertThat(consumedAt(STATE)).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void consumptionStaysCommittedWhenLaterWorkRollsBack() {
        seed(STATE, BINDING, EXPECTED, NOW.plusMinutes(10));
        var laterWork = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> laterWork.executeWithoutResult(status -> {
            assertThat(store.consume(STATE, BINDING, NOW)).contains(EXPECTED);
            throw new IllegalStateException("later member work failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(consumedAt(STATE)).isEqualTo(NOW);
        assertThat(store.consume(STATE, BINDING, NOW.plusSeconds(1))).isEmpty();
    }

    @Test
    void consumerUsesUtcMicrosecondTimeAndOnlyPersistedValues() {
        seed("f8fb559306134eeeacc6a3ec137868c5d36f34a7d150340c784dae289d00aac9",
                "0bb001e614432270361e93b579871b367eb1442846bbab4ca545af0bb63adf11",
                EXPECTED, NOW.plusMinutes(10));
        assertThat(consumer.consume("raw-state-é", "browser-cookie-value")).contains(EXPECTED);
        assertThat(consumedAt("f8fb559306134eeeacc6a3ec137868c5d36f34a7d150340c784dae289d00aac9"))
                .isEqualTo(NOW);
    }

    @Test
    void eightConcurrentConsumersHaveOneWinnerInEachOfTenRounds() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int round = 0; round < 10; round++) {
                jdbc.update("DELETE FROM login_attempts");
                seed(STATE, BINDING, EXPECTED, NOW.plusMinutes(10));
                var barrier = new CyclicBarrier(8);
                var futures = new ArrayList<java.util.concurrent.Future<ConsumeResult>>();
                for (int request = 0; request < 8; request++) {
                    LocalDateTime requestedAt = NOW.plusNanos(request * 1_000L);
                    futures.add(executor.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return new ConsumeResult(requestedAt, store.consume(STATE, BINDING, requestedAt));
                    }));
                }
                var winners = new ArrayList<ConsumeResult>();
                for (var future : futures) {
                    ConsumeResult result = future.get(20, TimeUnit.SECONDS);
                    if (result.attempt().isPresent()) {
                        assertThat(result.attempt()).contains(EXPECTED);
                        winners.add(result);
                    }
                }
                assertThat(winners).as("round %s", round).hasSize(1);
                assertThat(consumedAt(STATE)).isEqualTo(winners.getFirst().requestedAt());
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts", Integer.class)).isEqualTo(1);
                assertThat(store.consume(STATE, BINDING, NOW.plusSeconds(1))).isEmpty();
                assertThat(consumedAt(STATE)).isEqualTo(winners.getFirst().requestedAt());
            }
        }
    }

    private void seed(String state, String binding, LoginAttempt attempt, LocalDateTime expiry) {
        jdbc.update("""
                INSERT INTO login_attempts
                    (state_hash, browser_binding_hash, client_attempt_id, return_to, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, state, binding, attempt.clientAttemptId(), attempt.returnTo(), NOW.minusMinutes(1), expiry);
    }

    private LocalDateTime consumedAt(String state) {
        return jdbc.queryForObject("SELECT consumed_at FROM login_attempts WHERE state_hash = ?",
                (rs, row) -> rs.getObject("consumed_at", LocalDateTime.class), state);
    }

    private record ConsumeResult(LocalDateTime requestedAt, Optional<LoginAttempt> attempt) {}

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-09T00:00:00.123456789Z"), ZoneId.of("Asia/Seoul"));
        }
    }
}

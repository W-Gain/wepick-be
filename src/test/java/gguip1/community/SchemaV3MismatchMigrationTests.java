package gguip1.community;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class SchemaV3MismatchMigrationTests {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Test
    void mismatchedLegacyVoteStopsCompositeForeignKeyWithoutChangingVoteData() {
        var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        Flyway baseline = Flyway.configure().dataSource(dataSource).target("1").load();
        assertThat(baseline.migrate().migrationsExecuted).isEqualTo(1);

        jdbc.update("INSERT INTO users (user_id, status, email, password, nickname) VALUES (1, 0, 'mismatch@example.com', 'password', 'mismatch')");
        jdbc.update("INSERT INTO topics (topic_id, target_date, title, status) VALUES (1, CURRENT_DATE(), 'option owner', 'OPEN')");
        jdbc.update("INSERT INTO topics (topic_id, target_date, title, status) VALUES (2, DATE_ADD(CURRENT_DATE(), INTERVAL 1 DAY), 'vote topic', 'OPEN')");
        jdbc.update("INSERT INTO topic_options (option_id, topic_id, vote_count, text, label) VALUES (1, 1, 1, 'A', 'A')");
        jdbc.update("INSERT INTO votes (vote_id, user_id, topic_id, option_id) VALUES (1, 1, 2, 1)");
        var originalVote = jdbc.queryForMap("SELECT vote_id, user_id, topic_id, option_id FROM votes WHERE vote_id = 1");

        Flyway latest = Flyway.configure().dataSource(dataSource).load();
        assertThatThrownBy(latest::migrate).isInstanceOf(FlywayException.class);

        assertThat(jdbc.queryForMap("SELECT vote_id, user_id, topic_id, option_id FROM votes WHERE vote_id = 1")).isEqualTo(originalVote);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM votes", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'topics' AND COLUMN_NAME = 'content_revision'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'topic_options'
                  AND INDEX_NAME = 'UK_topic_options_topic_option'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'votes' AND COLUMN_NAME = 'anonymous_voter_id'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'topic_reviews'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE version = '3' AND success = 0
                """, Integer.class)).isEqualTo(1);
        // MySQL commits earlier ALTER statements; callers must inspect and reconcile partial V3 DDL before Flyway repair.
    }
}

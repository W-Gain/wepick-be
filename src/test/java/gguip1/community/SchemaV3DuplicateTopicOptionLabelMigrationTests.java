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
class SchemaV3DuplicateTopicOptionLabelMigrationTests {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Test
    void duplicateNonNullTopicLabelsStopMigrationWithoutChangingLegacyOptions() {
        var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        Flyway baseline = Flyway.configure().dataSource(dataSource).target("1").load();
        assertThat(baseline.migrate().migrationsExecuted).isEqualTo(1);

        jdbc.update("INSERT INTO topics (topic_id, target_date, title, status) VALUES (1, CURRENT_DATE(), 'legacy', 'OPEN')");
        jdbc.update("INSERT INTO topic_options (option_id, topic_id, vote_count, text, label) VALUES (1, 1, 2, 'A1', 'A')");
        jdbc.update("INSERT INTO topic_options (option_id, topic_id, vote_count, text, label) VALUES (2, 1, 3, 'A2', 'A')");
        var originalOptions = jdbc.queryForList("""
                SELECT option_id, topic_id, vote_count, description, text, label
                FROM topic_options ORDER BY option_id
                """);

        Flyway latest = Flyway.configure().dataSource(dataSource).load();
        assertThatThrownBy(latest::migrate).isInstanceOf(FlywayException.class);

        assertThat(jdbc.queryForList("""
                SELECT option_id, topic_id, vote_count, description, text, label
                FROM topic_options ORDER BY option_id
                """)).isEqualTo(originalOptions);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'topics' AND COLUMN_NAME = 'content_revision'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'topic_options'
                  AND INDEX_NAME IN ('UK_topic_options_topic_option', 'UK_topic_options_topic_label')
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'votes' AND COLUMN_NAME = 'anonymous_voter_id'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE version = '3' AND success = 0
                """, Integer.class)).isEqualTo(1);
        // MySQL keeps the earlier topics ALTER while rejecting the atomic topic_options ALTER.
    }
}

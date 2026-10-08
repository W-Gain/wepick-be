package gguip1.community;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AuthStorageMigrationTests {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Test
    void v1DataSurvivesExpansionAndNewConstraintsAreEnforced() {
        var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        Flyway baseline = Flyway.configure().dataSource(dataSource).target("1").load();
        assertThat(baseline.migrate().migrationsExecuted).isEqualTo(1);

        jdbc.update("INSERT INTO images (image_id, status, created_at, storage_key) VALUES (1, 0, NOW(6), 'legacy-image')");
        jdbc.update("""
                INSERT INTO users (user_id, status, email, password, nickname, profile_image_id)
                VALUES (1, 0, 'legacy@example.com', 'legacy-password', 'legacy', 1)
                """);
        jdbc.update("INSERT INTO topics (topic_id, target_date, title, status) VALUES (1, CURRENT_DATE(), 'legacy', 'OPEN')");
        jdbc.update("INSERT INTO topic_options (option_id, topic_id, vote_count, text, label) VALUES (1, 1, 1, 'A', 'A')");
        jdbc.update("INSERT INTO votes (vote_id, user_id, topic_id, option_id) VALUES (1, 1, 1, 1)");
        jdbc.update("""
                INSERT INTO SPRING_SESSION
                    (PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME, MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME)
                VALUES ('legacy-primary', 'legacy-session', 1, 1, 1800, 1800001, '1')
                """);
        jdbc.update("""
                INSERT INTO SPRING_SESSION_ATTRIBUTES (SESSION_PRIMARY_ID, ATTRIBUTE_NAME, ATTRIBUTE_BYTES)
                VALUES ('legacy-primary', 'legacy-attribute', X'010203')
                """);
        var oldUser = jdbc.queryForMap("SELECT user_id, status, email, password, nickname, profile_image_id FROM users WHERE user_id = 1");
        var oldImage = jdbc.queryForMap("SELECT image_id, status, created_at, storage_key, orphaned_at FROM images WHERE image_id = 1");
        var oldVote = jdbc.queryForMap("SELECT * FROM votes WHERE vote_id = 1");
        var oldSession = jdbc.queryForMap("SELECT * FROM SPRING_SESSION WHERE PRIMARY_ID = 'legacy-primary'");

        Flyway latest = Flyway.configure().dataSource(dataSource).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForMap("SELECT user_id, status, email, password, nickname, profile_image_id FROM users WHERE user_id = 1")).isEqualTo(oldUser);
        assertThat(jdbc.queryForMap("SELECT image_id, status, created_at, storage_key, orphaned_at FROM images WHERE image_id = 1")).isEqualTo(oldImage);
        assertThat(jdbc.queryForMap("SELECT * FROM votes WHERE vote_id = 1")).isEqualTo(oldVote);
        assertThat(jdbc.queryForMap("SELECT * FROM SPRING_SESSION WHERE PRIMARY_ID = 'legacy-primary'")).isEqualTo(oldSession);
        assertThat(jdbc.queryForObject("SELECT HEX(ATTRIBUTE_BYTES) FROM SPRING_SESSION_ATTRIBUTES", String.class)).isEqualTo("010203");
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE user_id = 1", String.class)).isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT withdrawn_at FROM users WHERE user_id = 1", Object.class)).isNull();
        assertThat(jdbc.queryForMap("SELECT owner_user_id, purpose, expires_at FROM images WHERE image_id = 1").values()).containsOnlyNulls();
        for (String table : new String[]{"social_accounts", "anonymous_voters", "login_attempts", "external_unlink_jobs"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        }

        verifyIndexesAndForeignKeys(jdbc);
        verifyNewWritesAndConstraints(jdbc);
    }

    private void verifyIndexesAndForeignKeys(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(TABLE_NAME, ':', INDEX_NAME, ':', NON_UNIQUE, ':',
                    GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX))
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND
                    (TABLE_NAME IN ('social_accounts', 'anonymous_voters', 'login_attempts', 'external_unlink_jobs')
                        OR (TABLE_NAME = 'images' AND INDEX_NAME = 'IX_images_status_expires_at'))
                    AND INDEX_NAME <> 'PRIMARY'
                GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE
                """, String.class)).containsExactlyInAnyOrder(
                "social_accounts:UK_social_accounts_provider_identity:0:provider,provider_user_id",
                "social_accounts:UK_social_accounts_user_provider:0:user_id,provider",
                "anonymous_voters:UK_anonymous_voters_token_hash:0:token_hash",
                "anonymous_voters:IX_anonymous_voters_linked_user:1:linked_user_id",
                "anonymous_voters:IX_anonymous_voters_expires_at:1:expires_at",
                "login_attempts:UK_login_attempts_state_hash:0:state_hash",
                "login_attempts:IX_login_attempts_expires_at:1:expires_at",
                "external_unlink_jobs:IX_external_unlink_jobs_due:1:next_attempt_at,expires_at,unlink_job_id",
                "images:IX_images_status_expires_at:1:status,expires_at");
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(TABLE_NAME, ':', COLUMN_NAME, ':', REFERENCED_TABLE_NAME, ':', REFERENCED_COLUMN_NAME)
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL
                    AND CONSTRAINT_NAME IN ('FK_social_accounts_user', 'FK_anonymous_voters_linked_user', 'FK_images_owner_user')
                """, String.class)).containsExactlyInAnyOrder(
                "social_accounts:user_id:users:user_id",
                "anonymous_voters:linked_user_id:users:user_id",
                "images:owner_user_id:users:user_id");
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(TABLE_NAME, ':', COLUMN_NAME, ':', COLUMN_TYPE, ':', IS_NULLABLE)
                FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND
                    ((TABLE_NAME = 'users' AND COLUMN_NAME IN ('role', 'withdrawn_at'))
                        OR (TABLE_NAME = 'images' AND COLUMN_NAME IN ('owner_user_id', 'purpose', 'expires_at'))
                        OR (TABLE_NAME = 'login_attempts' AND COLUMN_NAME IN ('state_hash', 'browser_binding_hash', 'client_attempt_id', 'return_to'))
                        OR (TABLE_NAME = 'social_accounts' AND COLUMN_NAME IN ('provider', 'provider_user_id'))
                        OR (TABLE_NAME = 'anonymous_voters' AND COLUMN_NAME = 'token_hash'))
                """, String.class)).containsExactlyInAnyOrder(
                "users:role:enum('USER','ADMIN'):NO", "users:withdrawn_at:datetime(6):YES",
                "images:owner_user_id:bigint:YES", "images:purpose:varchar(20):YES", "images:expires_at:datetime(6):YES",
                "login_attempts:state_hash:char(64):NO", "login_attempts:browser_binding_hash:char(64):NO",
                "login_attempts:client_attempt_id:varchar(64):NO", "login_attempts:return_to:varchar(512):NO",
                "social_accounts:provider:varchar(20):NO", "social_accounts:provider_user_id:varchar(100):NO",
                "anonymous_voters:token_hash:varchar(255):NO");
    }

    private void verifyNewWritesAndConstraints(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO users (user_id, status, email, password, nickname) VALUES (2, 0, 'v2@example.com', 'password', 'v2')");
        jdbc.update("INSERT INTO images (image_id, status, created_at, storage_key) VALUES (2, 0, NOW(6), 'v2-image')");
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE user_id = 2", String.class)).isEqualTo("USER");
        jdbc.update("UPDATE images SET owner_user_id = 1, purpose = 'PROFILE', expires_at = NOW(6) WHERE image_id = 1");
        assertRejected(jdbc, "UPDATE images SET owner_user_id = 999999 WHERE image_id = 2");
        jdbc.update("INSERT INTO social_accounts (user_id, provider, provider_user_id, connected_at) VALUES (1, 'KAKAO', 'identity-1', NOW(6))");
        assertRejected(jdbc, "INSERT INTO social_accounts (user_id, provider, provider_user_id, connected_at) VALUES (2, 'KAKAO', 'identity-1', NOW(6))");
        assertRejected(jdbc, "INSERT INTO social_accounts (user_id, provider, provider_user_id, connected_at) VALUES (1, 'KAKAO', 'identity-2', NOW(6))");
        assertRejected(jdbc, "INSERT INTO social_accounts (user_id, provider, provider_user_id, connected_at) VALUES (999999, 'KAKAO', 'missing-user', NOW(6))");
        jdbc.update("INSERT INTO anonymous_voters (token_hash, linked_user_id, created_at, expires_at) VALUES ('hash-1', 1, NOW(6), NOW(6))");
        jdbc.update("INSERT INTO anonymous_voters (token_hash, created_at, expires_at) VALUES ('hash-2', NOW(6), NOW(6))");
        assertRejected(jdbc, "INSERT INTO anonymous_voters (token_hash, created_at, expires_at) VALUES ('hash-1', NOW(6), NOW(6))");
        assertRejected(jdbc, "UPDATE anonymous_voters SET linked_user_id = 999999 WHERE token_hash = 'hash-2'");
        jdbc.update("""
                INSERT INTO login_attempts (state_hash, browser_binding_hash, client_attempt_id, return_to, created_at, expires_at)
                VALUES (REPEAT('a', 64), REPEAT('b', 64), 'attempt-1', REPEAT('/', 512), NOW(6), DATE_ADD(NOW(6), INTERVAL 10 MINUTE))
                """);
        assertRejected(jdbc, """
                INSERT INTO login_attempts (state_hash, browser_binding_hash, client_attempt_id, return_to, created_at, expires_at)
                VALUES (REPEAT('a', 64), REPEAT('c', 64), 'attempt-2', '/', NOW(6), NOW(6))
                """);
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM login_attempts", Object.class)).isNull();
        jdbc.update("INSERT INTO external_unlink_jobs (provider, provider_user_id, next_attempt_at, expires_at, created_at) VALUES ('KAKAO', 'identity-1', NOW(6), NOW(6), NOW(6))");
        assertThat(jdbc.queryForObject("SELECT attempts FROM external_unlink_jobs", Integer.class)).isZero();
        // Removing an account must explicitly clear its links; no cascade silently deletes these records.
        assertRejected(jdbc, "DELETE FROM users WHERE user_id = 1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM votes", Integer.class)).isEqualTo(1);
    }

    private void assertRejected(JdbcTemplate jdbc, String sql) {
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }
}

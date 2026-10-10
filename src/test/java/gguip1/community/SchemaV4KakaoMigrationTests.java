package gguip1.community;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 파괴적 V4 guard와 DDL은 테스트 전용 새 MySQL에만 적용합니다. */
@Testcontainers
class SchemaV4KakaoMigrationTests {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Test
    @DisplayName("사용자 행이 있으면 V4가 DDL 전에 중단되고 사용자 데이터와 열을 보존한다")
    void refusesPopulatedUserDatabaseAndLeavesColumnsAndRowsIntact() {
        JdbcTemplate jdbc = migrateBlankDatabaseToV3();
        jdbc.update("INSERT INTO users (user_id, status, email, password, nickname) "
                + "VALUES (1, 0, 'legacy@test.invalid', 'unused', 'before-v4')");

        Flyway v4 = Flyway.configure().dataSource(dataSource()).load();
        assertThatThrownBy(v4::migrate).isInstanceOf(FlywayException.class)
                .hasRootCauseMessage("V4 requires an empty application database; no data was changed");

        assertV4DidNotSucceed(jdbc);
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE user_id = 1", String.class))
                .isEqualTo("legacy@test.invalid");
        assertThat(jdbc.queryForObject("SELECT password FROM users WHERE user_id = 1", String.class))
                .isEqualTo("unused");
        assertLegacySchemaRemains(jdbc);
    }

    @Test
    @DisplayName("사용자 외 응용 데이터만 있어도 V4는 전체 전환 DB guard로 중단한다")
    void refusesPopulatedNonUserApplicationTable() {
        JdbcTemplate jdbc = migrateBlankDatabaseToV3();
        jdbc.update("INSERT INTO topics (target_date, title, status) VALUES (CURRENT_DATE(), 'legacy topic', 'OPEN')");

        Flyway v4 = Flyway.configure().dataSource(dataSource()).load();
        assertThatThrownBy(v4::migrate).isInstanceOf(FlywayException.class)
                .hasRootCauseMessage("V4 requires an empty application database; no data was changed");

        assertV4DidNotSucceed(jdbc);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topics", Integer.class)).isEqualTo(1);
        assertLegacySchemaRemains(jdbc);
    }

    @Test
    @DisplayName("빈 데이터베이스만 V4를 적용하고 Kakao 회원과 익명 표 주체를 지원한다")
    void convertsEmptyDatabaseAndAllowsBothVoteSubjects() {
        JdbcTemplate jdbc = migrateBlankDatabaseToV3();
        Flyway v4 = Flyway.configure().dataSource(dataSource()).load();

        assertThat(v4.migrate().migrationsExecuted).isEqualTo(1);
        v4.validate();
        assertThat(v4.info().current().getVersion().getVersion()).isEqualTo("4");
        assertThat(jdbc.queryForList("""
                SELECT COLUMN_NAME FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
                """, String.class)).doesNotContain("email", "password");
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(COLUMN_TYPE, ':', IS_NULLABLE) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'nickname'
                """, String.class)).isEqualTo("varchar(30):YES");
        assertThat(jdbc.queryForObject("""
                SELECT IS_NULLABLE FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'votes' AND COLUMN_NAME = 'user_id'
                """, String.class)).isEqualTo("YES");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
                  AND CONSTRAINT_NAME = 'CHK_users_active_nickname'
                """, Integer.class)).isEqualTo(1);

        jdbc.update("INSERT INTO users (user_id, status, nickname, role) VALUES (1, 0, 'member', 'USER')");
        jdbc.update("INSERT INTO topics (topic_id, target_date, title, status) VALUES (1, CURRENT_DATE(), 'member vote', 'OPEN')");
        jdbc.update("INSERT INTO topic_options (option_id, topic_id, vote_count, text, label) VALUES (1, 1, 1, 'A', 'A')");
        jdbc.update("INSERT INTO votes (vote_id, user_id, topic_id, option_id) VALUES (1, 1, 1, 1)");

        jdbc.update("INSERT INTO topics (topic_id, target_date, title, status) "
                + "VALUES (2, DATE_ADD(CURRENT_DATE(), INTERVAL 1 DAY), 'anonymous vote', 'OPEN')");
        jdbc.update("INSERT INTO topic_options (option_id, topic_id, vote_count, text, label) VALUES (2, 2, 1, 'B', 'B')");
        jdbc.update("INSERT INTO anonymous_voters (anonymous_voter_id, token_hash, created_at, expires_at) "
                + "VALUES (1, 'anonymous-hash', NOW(6), DATE_ADD(NOW(6), INTERVAL 1 DAY))");
        jdbc.update("INSERT INTO votes (vote_id, user_id, anonymous_voter_id, topic_id, option_id) "
                + "VALUES (2, NULL, 1, 2, 2)");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO votes (user_id, anonymous_voter_id, topic_id, option_id) VALUES (NULL, NULL, 2, 2)"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO votes (user_id, anonymous_voter_id, topic_id, option_id) VALUES (1, 1, 2, 2)"))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM votes", Integer.class)).isEqualTo(2);
    }

    private JdbcTemplate migrateBlankDatabaseToV3() {
        DataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway v3 = Flyway.configure().dataSource(dataSource).target("3").load();
        assertThat(v3.migrate().migrationsExecuted).isEqualTo(3);
        return new JdbcTemplate(dataSource);
    }

    private DataSource dataSource() {
        return new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static void assertLegacySchemaRemains(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("""
                SELECT COLUMN_NAME FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
                """, String.class)).contains("email", "password");
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(COLUMN_TYPE, ':', IS_NULLABLE) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'nickname'
                """, String.class)).isEqualTo("varchar(255):NO");
        assertThat(jdbc.queryForObject("""
                SELECT IS_NULLABLE FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'votes' AND COLUMN_NAME = 'user_id'
                """, String.class)).isEqualTo("NO");
    }

    private static void assertV4DidNotSucceed(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE version = '4' AND success = 1
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("3");
    }
}

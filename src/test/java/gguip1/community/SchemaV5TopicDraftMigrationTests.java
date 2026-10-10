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

/** V5 schema 전환은 각 테스트에서만 만든 격리 MySQL로 검증합니다. */
@Testcontainers
class SchemaV5TopicDraftMigrationTests {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Test
    @DisplayName("clean V1부터 V5까지 DRAFT schema를 만들고 제목 키를 NOT NULL unique로 고정한다")
    void migratesCleanSchemaToV5() {
        JdbcTemplate jdbc = resetToVersion(null);

        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success=1 "
                + "ORDER BY installed_rank DESC LIMIT 1", String.class)).isEqualTo("5");
        assertThat(jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE "
                + "TABLE_SCHEMA=DATABASE() AND TABLE_NAME='topics' AND COLUMN_NAME='status'", String.class))
                .contains("DRAFT", "REJECTED", "HIDDEN");
        assertThat(jdbc.queryForObject("SELECT CONCAT(IS_NULLABLE,':',COLLATION_NAME) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='topics' AND COLUMN_NAME='normalized_title'", String.class))
                .isEqualTo("NO:utf8mb4_0900_bin");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE "
                + "TABLE_SCHEMA=DATABASE() AND TABLE_NAME='topics' AND INDEX_NAME='UK_topics_normalized_title' "
                + "AND NON_UNIQUE=0 AND COLUMN_NAME='normalized_title'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE "
                + "TABLE_SCHEMA=DATABASE() AND TABLE_NAME='topics' AND COLUMN_NAME='target_date'", String.class)).isEqualTo("YES");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO topics (target_date,title,status,normalized_title) "
                + "VALUES (CURRENT_DATE(),'null key','OPEN',NULL)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topics WHERE title='null key'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("V4 이후 기존 인증·legacy topic·option·vote를 유지하며 정규화 키만 backfill한다")
    void upgradesLegacyDataWithoutChangingLegacyRelationships() {
        JdbcTemplate jdbc = resetToVersion("4");
        insertLegacyFixture(jdbc, "café —질문");
        var voteBefore = jdbc.queryForMap("SELECT vote_id,user_id,topic_id,option_id FROM votes WHERE vote_id=1");
        var optionBefore = jdbc.queryForMap("SELECT option_id,topic_id,label,text FROM topic_options WHERE option_id=1");

        migrateV5();

        assertThat(jdbc.queryForObject("SELECT normalized_title FROM topics WHERE topic_id=1", String.class))
                .isEqualTo("café -질문");
        assertThat(jdbc.queryForMap("SELECT vote_id,user_id,topic_id,option_id FROM votes WHERE vote_id=1"))
                .isEqualTo(voteBefore);
        assertThat(jdbc.queryForMap("SELECT option_id,topic_id,label,text FROM topic_options WHERE option_id=1"))
                .isEqualTo(optionBefore);
        assertThat(jdbc.queryForObject("SELECT status FROM topics WHERE topic_id=1", String.class)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT category_code IS NULL AND created_by_user_id IS NULL FROM topics "
                + "WHERE topic_id=1", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM social_accounts WHERE social_account_id=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRIMARY_ID='legacy-session'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topics WHERE normalized_title IS NULL", Integer.class)).isZero();
    }

    @Test
    @DisplayName("정규화 제목 충돌은 V5 DDL·backfill 전에 멈추고 schema와 행을 보존한다")
    void collisionFailsBeforeSchemaOrDataMutation() {
        JdbcTemplate jdbc = resetToVersion("4");
        insertLegacyUser(jdbc);
        jdbc.update("INSERT INTO topics (topic_id,target_date,title,status) VALUES "
                + "(1,CURRENT_DATE(),'하나—둘','OPEN'),(2,DATE_ADD(CURRENT_DATE(),INTERVAL 1 DAY),'하나–둘','CLOSED')");

        assertThatThrownBy(this::migrateV5).isInstanceOf(FlywayException.class)
                .hasRootCauseMessage("V5 title preflight found duplicate normalized title keys");

        assertThat(jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE "
                + "TABLE_SCHEMA=DATABASE() AND TABLE_NAME='topics' AND COLUMN_NAME='status'", String.class))
                .isEqualTo("enum('CLOSED','OPEN')");
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE "
                + "TABLE_SCHEMA=DATABASE() AND TABLE_NAME='topics' AND COLUMN_NAME='target_date'", String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topics WHERE normalized_title IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topics", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("NFC 정규화 키가 255자를 넘는 legacy 제목은 V5 DDL 전 중단하고 스키마와 행을 보존한다")
    void overlongNormalizedKeyFailsBeforeSchemaOrDataMutation() {
        JdbcTemplate jdbc = resetToVersion("4");
        insertLegacyUser(jdbc);
        String title = "\u0958".repeat(128);
        jdbc.update("INSERT INTO topics (topic_id,target_date,title,status) VALUES (1,CURRENT_DATE(),?,'OPEN')", title);
        var rowBefore = jdbc.queryForMap("SELECT target_date,title,status,normalized_title FROM topics WHERE topic_id=1");
        String normalizedTitleDefinitionBefore = jdbc.queryForObject("SELECT CONCAT(IS_NULLABLE,':',COLLATION_NAME) "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='topics' "
                + "AND COLUMN_NAME='normalized_title'", String.class);
        int namedUniqueIndexesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE "
                + "TABLE_SCHEMA=DATABASE() AND TABLE_NAME='topics' AND INDEX_NAME='UK_topics_normalized_title'", Integer.class);

        assertThatThrownBy(this::migrateV5).isInstanceOf(FlywayException.class)
                .satisfies(failure -> {
                    assertThat(failure).hasStackTraceContaining("V5 title preflight rejected invalid title data");
                    assertThat(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure))
                            .hasMessage("key too long");
                });

        assertThat(jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE "
                + "TABLE_SCHEMA=DATABASE() AND TABLE_NAME='topics' AND COLUMN_NAME='status'", String.class))
                .isEqualTo("enum('CLOSED','OPEN')");
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE "
                + "TABLE_SCHEMA=DATABASE() AND TABLE_NAME='topics' AND COLUMN_NAME='target_date'", String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("SELECT CONCAT(IS_NULLABLE,':',COLLATION_NAME) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='topics' AND COLUMN_NAME='normalized_title'", String.class))
                .isEqualTo(normalizedTitleDefinitionBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE "
                + "TABLE_SCHEMA=DATABASE() AND TABLE_NAME='topics' AND INDEX_NAME='UK_topics_normalized_title'", Integer.class))
                .isEqualTo(namedUniqueIndexesBefore);
        assertThat(jdbc.queryForMap("SELECT target_date,title,status,normalized_title FROM topics WHERE topic_id=1"))
                .isEqualTo(rowBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topics", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success=1 "
                + "ORDER BY installed_rank DESC LIMIT 1", String.class)).isEqualTo("4");
    }

    @Test
    @DisplayName("정확한 V5 partial backfill prefix는 null 행만 채우고 이어서 완료한다")
    void resumesKnownPartialBackfillPrefix() {
        JdbcTemplate jdbc = resetToVersion("4");
        insertLegacyUser(jdbc);
        jdbc.update("INSERT INTO topics (topic_id,target_date,title,status) VALUES "
                + "(1,CURRENT_DATE(),'첫 질문','OPEN'),(2,DATE_ADD(CURRENT_DATE(),INTERVAL 1 DAY),'둘째 질문','CLOSED')");
        jdbc.update("ALTER TABLE topics MODIFY COLUMN status ENUM ('CLOSED','OPEN','DRAFT','APPROVED','SCHEDULED','PUBLISHED','REJECTED','HIDDEN') NOT NULL, "
                + "MODIFY COLUMN target_date DATE NULL");
        jdbc.update("ALTER TABLE topics MODIFY COLUMN normalized_title VARCHAR(255) CHARACTER SET utf8mb4 "
                + "COLLATE utf8mb4_0900_bin NULL");
        jdbc.update("UPDATE topics SET normalized_title='첫 질문' WHERE topic_id=1");

        migrateV5();

        assertThat(jdbc.queryForList("SELECT normalized_title FROM topics ORDER BY topic_id", String.class))
                .containsExactly("첫 질문", "둘째 질문");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE "
                + "TABLE_SCHEMA=DATABASE() AND TABLE_NAME='topics' AND INDEX_NAME='UK_topics_normalized_title'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("NOT NULL 적용 뒤 UNIQUE 전 prefix에서 재시도하면 인덱스까지 이어서 완료한다")
    void resumesNotNullBeforeUniquePrefix() {
        JdbcTemplate jdbc = resetToVersion("4");
        insertLegacyUser(jdbc);
        jdbc.update("INSERT INTO topics (topic_id,target_date,title,status) VALUES "
                + "(1,CURRENT_DATE(),'첫 질문','OPEN'),(2,DATE_ADD(CURRENT_DATE(),INTERVAL 1 DAY),'둘째 질문','CLOSED')");
        jdbc.update("ALTER TABLE topics MODIFY COLUMN status ENUM ('CLOSED','OPEN','DRAFT','APPROVED','SCHEDULED','PUBLISHED','REJECTED','HIDDEN') NOT NULL, "
                + "MODIFY COLUMN target_date DATE NULL");
        jdbc.update("ALTER TABLE topics MODIFY COLUMN normalized_title VARCHAR(255) CHARACTER SET utf8mb4 "
                + "COLLATE utf8mb4_0900_bin NULL");
        jdbc.update("UPDATE topics SET normalized_title=CASE topic_id WHEN 1 THEN '첫 질문' ELSE '둘째 질문' END");
        jdbc.update("ALTER TABLE topics MODIFY COLUMN normalized_title VARCHAR(255) CHARACTER SET utf8mb4 "
                + "COLLATE utf8mb4_0900_bin NOT NULL");

        assertThat(jdbc.queryForObject("SELECT CONCAT(IS_NULLABLE,':',COLLATION_NAME) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='topics' AND COLUMN_NAME='normalized_title'", String.class))
                .isEqualTo("NO:utf8mb4_0900_bin");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE "
                + "TABLE_SCHEMA=DATABASE() AND TABLE_NAME='topics' AND INDEX_NAME='UK_topics_normalized_title'", Integer.class))
                .isZero();

        migrateV5();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE "
                + "TABLE_SCHEMA=DATABASE() AND TABLE_NAME='topics' AND INDEX_NAME='UK_topics_normalized_title' "
                + "AND NON_UNIQUE=0 AND COLUMN_NAME='normalized_title'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT normalized_title FROM topics ORDER BY topic_id", String.class))
                .containsExactly("첫 질문", "둘째 질문");
    }

    @Test
    @DisplayName("문서화되지 않은 V5 partial schema는 덮어쓰지 않고 중단한다")
    void stopsAtUnexpectedPartialSchema() {
        JdbcTemplate jdbc = resetToVersion("4");
        insertLegacyUser(jdbc);
        jdbc.update("INSERT INTO topics (topic_id,target_date,title,status) VALUES (1,CURRENT_DATE(),'제목','OPEN')");
        jdbc.update("ALTER TABLE topics MODIFY COLUMN status ENUM ('CLOSED','OPEN','DRAFT','APPROVED','SCHEDULED','PUBLISHED','REJECTED','HIDDEN') NOT NULL, "
                + "MODIFY COLUMN target_date DATE NULL");
        jdbc.update("ALTER TABLE topics MODIFY COLUMN normalized_title VARCHAR(255) CHARACTER SET utf8mb4 "
                + "COLLATE utf8mb4_unicode_ci NULL");

        assertThatThrownBy(this::migrateV5).isInstanceOf(FlywayException.class)
                .hasRootCauseMessage("V5 schema is outside a known retry prefix");
        assertThat(jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE "
                + "TABLE_SCHEMA=DATABASE() AND TABLE_NAME='topics' AND COLUMN_NAME='normalized_title'", String.class))
                .isEqualTo("utf8mb4_unicode_ci");
        assertThat(jdbc.queryForObject("SELECT normalized_title FROM topics WHERE topic_id=1", String.class)).isNull();
    }

    private JdbcTemplate resetToVersion(String version) {
        DataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway flyway = Flyway.configure().dataSource(dataSource).target(version == null ? "5" : version).load();
        flyway.migrate();
        return new JdbcTemplate(dataSource);
    }

    private void migrateV5() {
        Flyway.configure().dataSource(dataSource()).load().migrate();
    }

    private void insertLegacyFixture(JdbcTemplate jdbc, String title) {
        insertLegacyUser(jdbc);
        jdbc.update("INSERT INTO social_accounts (social_account_id,user_id,provider,provider_user_id,connected_at) "
                + "VALUES (1,1,'KAKAO','fixture-member',NOW(6))");
        jdbc.update("INSERT INTO topics (topic_id,target_date,title,status,created_at) VALUES "
                + "(1,'2026-10-10',?,'OPEN','2026-10-10 00:00:00.123456')", title);
        jdbc.update("INSERT INTO topic_options (option_id,topic_id,label,text,vote_count) VALUES (1,1,'A','예',1),(2,1,'B','아니요',0)");
        jdbc.update("INSERT INTO votes (vote_id,user_id,topic_id,option_id,created_at) "
                + "VALUES (1,1,1,1,'2026-10-10 00:00:01.000001')");
        jdbc.update("INSERT INTO SPRING_SESSION (PRIMARY_ID,SESSION_ID,CREATION_TIME,LAST_ACCESS_TIME,MAX_INACTIVE_INTERVAL,EXPIRY_TIME,PRINCIPAL_NAME) "
                + "VALUES ('legacy-session','legacy-session-id',1,1,3600,9999999999,'1')");
    }

    private void insertLegacyUser(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO users (user_id,status,nickname,role) VALUES (1,0,'fixture-admin','ADMIN')");
    }

    private DataSource dataSource() {
        return new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }
}

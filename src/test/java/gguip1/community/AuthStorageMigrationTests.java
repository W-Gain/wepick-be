package gguip1.community;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
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
        jdbc.update("INSERT INTO topic_options (option_id, topic_id, vote_count, text, label) VALUES (2, NULL, 3, 'orphan', 'B')");
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
        var oldTopic = jdbc.queryForMap("SELECT topic_id, target_date, created_at, updated_at, description, title, status FROM topics WHERE topic_id = 1");
        var oldOption = jdbc.queryForMap("SELECT option_id, topic_id, vote_count, description, text, label FROM topic_options WHERE option_id = 1");
        var oldOrphanOption = jdbc.queryForMap("SELECT option_id, topic_id, vote_count, description, text, label FROM topic_options WHERE option_id = 2");
        var oldVote = jdbc.queryForMap("SELECT vote_id, user_id, topic_id, option_id FROM votes WHERE vote_id = 1");
        var oldSession = jdbc.queryForMap("SELECT * FROM SPRING_SESSION WHERE PRIMARY_ID = 'legacy-primary'");

        Flyway v2 = Flyway.configure().dataSource(dataSource).target("2").load();
        assertThat(v2.migrate().migrationsExecuted).isEqualTo(1);
        v2.validate();
        assertThat(v2.info().current().getVersion().getVersion()).isEqualTo("2");
        Flyway latest = Flyway.configure().dataSource(dataSource).target("3").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("3");
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForMap("SELECT user_id, status, email, password, nickname, profile_image_id FROM users WHERE user_id = 1")).isEqualTo(oldUser);
        assertThat(jdbc.queryForMap("SELECT image_id, status, created_at, storage_key, orphaned_at FROM images WHERE image_id = 1")).isEqualTo(oldImage);
        assertThat(jdbc.queryForMap("SELECT topic_id, target_date, created_at, updated_at, description, title, status FROM topics WHERE topic_id = 1")).isEqualTo(oldTopic);
        assertThat(jdbc.queryForMap("SELECT option_id, topic_id, vote_count, description, text, label FROM topic_options WHERE option_id = 1")).isEqualTo(oldOption);
        assertThat(jdbc.queryForMap("SELECT option_id, topic_id, vote_count, description, text, label FROM topic_options WHERE option_id = 2")).isEqualTo(oldOrphanOption);
        assertThat(jdbc.queryForMap("SELECT vote_id, user_id, topic_id, option_id FROM votes WHERE vote_id = 1")).isEqualTo(oldVote);
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
        verifyV3IndexesAndForeignKeys(jdbc);
        verifyV3WritesAndConstraints(jdbc);
    }

    private void verifyV3IndexesAndForeignKeys(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(TABLE_NAME, ':', COLUMN_NAME, ':', COLUMN_TYPE, ':', IS_NULLABLE)
                FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND
                    ((TABLE_NAME = 'topics' AND COLUMN_NAME IN ('normalized_title', 'category_code', 'content_revision', 'scheduled_kst_date', 'published_at', 'created_by_user_id'))
                        OR (TABLE_NAME = 'topic_options' AND COLUMN_NAME = 'topic_id')
                        OR (TABLE_NAME = 'votes' AND COLUMN_NAME IN ('anonymous_voter_id', 'user_id')))
                """, String.class)).containsExactlyInAnyOrder(
                "topics:normalized_title:varchar(255):YES", "topics:category_code:varchar(30):YES",
                "topics:content_revision:int:NO", "topics:scheduled_kst_date:date:YES",
                "topics:published_at:datetime(6):YES", "topics:created_by_user_id:bigint:YES",
                "topic_options:topic_id:bigint:YES", "votes:anonymous_voter_id:bigint:YES", "votes:user_id:bigint:NO");
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(TABLE_NAME, ':', INDEX_NAME, ':', NON_UNIQUE, ':',
                    GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX))
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND INDEX_NAME IN (
                    'UK_topics_scheduled_kst_date', 'UK_topic_options_topic_option', 'UK_topic_options_topic_label',
                    'UK_votes_topic_anonymous', 'UK_votes_id_user', 'IX_votes_topic_option',
                    'IX_votes_user_created_vote', 'IX_votes_anonymous_topic',
                    'UK_topic_review_checks_review_criterion', 'UK_pick_assignments_active_slot',
                    'IX_pick_assignments_ended', 'IX_pick_assignments_topic_ended',
                    'UK_opinions_active_vote', 'IX_opinions_vote_status_like_created',
                    'IX_opinions_status_created', 'UK_opinion_likes_opinion_user',
                    'IX_opinion_moderation_events_opinion_occurred')
                GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE
                """, String.class)).containsExactlyInAnyOrder(
                "topics:UK_topics_scheduled_kst_date:0:scheduled_kst_date",
                "topic_options:UK_topic_options_topic_option:0:topic_id,option_id",
                "topic_options:UK_topic_options_topic_label:0:topic_id,label",
                "votes:UK_votes_topic_anonymous:0:topic_id,anonymous_voter_id",
                "votes:UK_votes_id_user:0:vote_id,user_id", "votes:IX_votes_topic_option:1:topic_id,option_id",
                "votes:IX_votes_user_created_vote:1:user_id,created_at,vote_id",
                "votes:IX_votes_anonymous_topic:1:anonymous_voter_id,topic_id",
                "topic_review_checks:UK_topic_review_checks_review_criterion:0:review_id,criterion_code",
                "pick_assignments:UK_pick_assignments_active_slot:0:active_slot",
                "pick_assignments:IX_pick_assignments_ended:1:ended_at,pick_assignment_id",
                "pick_assignments:IX_pick_assignments_topic_ended:1:topic_id,ended_at,pick_assignment_id",
                "opinions:UK_opinions_active_vote:0:active_vote_id",
                "opinions:IX_opinions_vote_status_like_created:1:vote_id,status,like_count,created_at,opinion_id",
                "opinions:IX_opinions_status_created:1:status,created_at,opinion_id",
                "opinion_likes:UK_opinion_likes_opinion_user:0:opinion_id,user_id",
                "opinion_moderation_events:IX_opinion_moderation_events_opinion_occurred:1:opinion_id,occurred_at");
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(CONSTRAINT_NAME, ':', TABLE_NAME, ':', COLUMN_NAME, ':', REFERENCED_TABLE_NAME, ':', REFERENCED_COLUMN_NAME)
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_NAME IN (
                    'FK_topics_created_by_user', 'FK_votes_anonymous_voter', 'FK_votes_topic_option',
                    'FK_topic_reviews_topic', 'FK_topic_reviews_reviewer', 'FK_topic_review_checks_review',
                    'FK_topic_status_events_topic', 'FK_topic_status_events_actor', 'FK_pick_assignments_topic',
                    'FK_opinions_vote_author', 'FK_opinions_author', 'FK_opinion_likes_opinion', 'FK_opinion_likes_user',
                    'FK_opinion_moderation_events_opinion', 'FK_opinion_moderation_events_actor')
                ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION
                """, String.class)).containsExactly(
                "FK_opinion_likes_opinion:opinion_likes:opinion_id:opinions:opinion_id",
                "FK_opinion_likes_user:opinion_likes:user_id:users:user_id",
                "FK_opinion_moderation_events_actor:opinion_moderation_events:actor_user_id:users:user_id",
                "FK_opinion_moderation_events_opinion:opinion_moderation_events:opinion_id:opinions:opinion_id",
                "FK_opinions_author:opinions:author_user_id:users:user_id",
                "FK_opinions_vote_author:opinions:vote_id:votes:vote_id",
                "FK_opinions_vote_author:opinions:author_user_id:votes:user_id",
                "FK_pick_assignments_topic:pick_assignments:topic_id:topics:topic_id",
                "FK_topic_review_checks_review:topic_review_checks:review_id:topic_reviews:review_id",
                "FK_topic_reviews_reviewer:topic_reviews:reviewer_user_id:users:user_id",
                "FK_topic_reviews_topic:topic_reviews:topic_id:topics:topic_id",
                "FK_topic_status_events_actor:topic_status_events:actor_user_id:users:user_id",
                "FK_topic_status_events_topic:topic_status_events:topic_id:topics:topic_id",
                "FK_topics_created_by_user:topics:created_by_user_id:users:user_id",
                "FK_votes_anonymous_voter:votes:anonymous_voter_id:anonymous_voters:anonymous_voter_id",
                "FK_votes_topic_option:votes:topic_id:topic_options:topic_id",
                "FK_votes_topic_option:votes:option_id:topic_options:option_id");
        assertThat(jdbc.queryForList("""
                SELECT CONSTRAINT_NAME FROM information_schema.CHECK_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'CHK_votes_exactly_one_subject'
                """, String.class)).containsExactly("CHK_votes_exactly_one_subject");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                  AND GENERATION_EXPRESSION <> ''
                  AND ((TABLE_NAME = 'pick_assignments' AND COLUMN_NAME = 'active_slot')
                    OR (TABLE_NAME = 'opinions' AND COLUMN_NAME = 'active_vote_id'))
                """, Integer.class)).isEqualTo(2);
    }

    private void verifyV3WritesAndConstraints(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT content_revision FROM topics WHERE topic_id = 1", Integer.class)).isEqualTo(1);
        jdbc.update("INSERT INTO topic_options (option_id, topic_id, vote_count, text, label) VALUES (3, 1, 0, 'B', 'B')");
        assertRejected(jdbc, "INSERT INTO topic_options (option_id, topic_id, vote_count, text, label) VALUES (4, 1, 0, 'duplicate A', 'A')");
        jdbc.update("INSERT INTO topics (topic_id, target_date, title, status) VALUES (2, DATE_ADD(CURRENT_DATE(), INTERVAL 1 DAY), 'legacy two', 'OPEN')");
        jdbc.update("UPDATE topics SET scheduled_kst_date = CURRENT_DATE(), created_by_user_id = 1 WHERE topic_id = 1");
        assertThat(jdbc.queryForObject("SELECT content_revision FROM topics WHERE topic_id = 2", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topics WHERE normalized_title IS NULL AND category_code IS NULL AND created_by_user_id IS NULL", Integer.class)).isEqualTo(1);
        assertRejected(jdbc, "UPDATE topics SET scheduled_kst_date = CURRENT_DATE() WHERE topic_id = 2");
        assertRejected(jdbc, "UPDATE topics SET created_by_user_id = 999999 WHERE topic_id = 2");

        jdbc.update("INSERT INTO anonymous_voters (token_hash, created_at, expires_at) VALUES ('v3-token', NOW(6), DATE_ADD(NOW(6), INTERVAL 30 DAY))");
        Long anonymousVoterId = jdbc.queryForObject("SELECT anonymous_voter_id FROM anonymous_voters WHERE token_hash = 'v3-token'", Long.class);
        assertRejected(jdbc, "UPDATE votes SET anonymous_voter_id = " + anonymousVoterId + " WHERE vote_id = 1");
        assertThat(jdbc.queryForObject("SELECT anonymous_voter_id FROM votes WHERE vote_id = 1", Object.class)).isNull();
        assertRejected(jdbc, "INSERT INTO votes (user_id, topic_id, option_id, anonymous_voter_id) VALUES (1, 1, 1, 999999)");
        assertRejected(jdbc, "INSERT INTO votes (user_id, topic_id, option_id) VALUES (1, 2, 1)");

        jdbc.update("INSERT INTO topic_reviews (topic_id, content_revision, reviewer_user_id, decision, reviewed_at) VALUES (1, 99, 1, 'APPROVED', NOW(6))");
        Integer reviewId = jdbc.queryForObject("SELECT review_id FROM topic_reviews", Integer.class);
        jdbc.update("INSERT INTO topic_review_checks (review_id, criterion_code, passed) VALUES (?, 'criterion-1', TRUE)", reviewId);
        assertRejected(jdbc, "INSERT INTO topic_review_checks (review_id, criterion_code, passed) VALUES (" + reviewId + ", 'criterion-1', FALSE)");
        jdbc.update("INSERT INTO topic_status_events (topic_id, actor_user_id, from_status, to_status, occurred_at) VALUES (1, NULL, NULL, 'PUBLISHED', NOW(6))");
        jdbc.update("INSERT INTO pick_assignments (topic_id, started_at, ended_at, is_fallback) VALUES (1, NOW(6), NULL, FALSE)");
        assertThat(jdbc.queryForObject("SELECT active_slot FROM pick_assignments", Integer.class)).isEqualTo(1);
        assertRejected(jdbc, "INSERT INTO pick_assignments (topic_id, started_at, ended_at) VALUES (2, NOW(6), NULL)");
        jdbc.update("INSERT INTO pick_assignments (topic_id, started_at, ended_at) VALUES (2, NOW(6), NOW(6))");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pick_assignments WHERE active_slot IS NULL", Integer.class)).isEqualTo(1);

        jdbc.update("INSERT INTO users (user_id, status, email, password, nickname) VALUES (3, 0, 'v3-other@example.com', 'password', 'v3-other')");
        jdbc.update("INSERT INTO opinions (vote_id, author_user_id, body, status, created_at) VALUES (1, 1, 'member reason', 'ACTIVE', NOW(6))");
        Integer opinionId = jdbc.queryForObject("SELECT opinion_id FROM opinions", Integer.class);
        assertThat(jdbc.queryForObject("SELECT active_vote_id FROM opinions WHERE opinion_id = ?", Long.class, opinionId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT like_count FROM opinions WHERE opinion_id = ?", Integer.class, opinionId)).isZero();
        assertRejected(jdbc, "INSERT INTO opinions (vote_id, author_user_id, body, status, created_at) VALUES (1, 1, 'second active', 'ACTIVE', NOW(6))");
        assertRejected(jdbc, "INSERT INTO opinions (vote_id, author_user_id, body, status, created_at) VALUES (1, 3, 'wrong author', 'ACTIVE', NOW(6))");
        jdbc.update("INSERT INTO opinions (vote_id, author_user_id, body, status, created_at) VALUES (1, 1, 'hidden reason', 'HIDDEN', NOW(6))");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM opinions WHERE active_vote_id IS NULL", Integer.class)).isEqualTo(1);
        jdbc.update("INSERT INTO opinion_likes (opinion_id, user_id, created_at) VALUES (?, 3, NOW(6))", opinionId);
        assertRejected(jdbc, "INSERT INTO opinion_likes (opinion_id, user_id, created_at) VALUES (" + opinionId + ", 3, NOW(6))");
        jdbc.update("INSERT INTO opinion_moderation_events (opinion_id, actor_user_id, action, reason, occurred_at) VALUES (?, 1, 'HIDE', NULL, NOW(6))", opinionId);
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
        assertThatThrownBy(() -> jdbc.update(sql))
                .satisfiesAnyOf(
                        failure -> assertThat(failure).isInstanceOf(DataIntegrityViolationException.class),
                        failure -> {
                            assertThat(failure).isInstanceOf(UncategorizedSQLException.class);
                            assertThat(((UncategorizedSQLException) failure).getSQLException().getErrorCode()).isEqualTo(3819);
                        });
    }
}
